package com.signalcrm.signalleads.brreg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.signalcrm.signalleads.api.PublicRole;
import com.signalcrm.signalleads.config.TtlCache;
import com.signalcrm.signalleads.config.UsageTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Brønnøysundregistrenes åpne API-er: Enhetsregisteret (grunndata, søk, roller) og
 * Regnskapsregisteret (årsregnskap). Parsingen er hentet fra SignalCRMs BrregApiClient, der
 * feltnavnene er bekreftet mot live-svar.
 *
 * Alt er defensivt: feil gir tomt resultat, aldri unntak - en analyse skal fortsette med det den har.
 * Grunndata og regnskap caches (offentlige data, like for alle). Roller caches IKKE: de inneholder
 * personnavn, og tjenesten skal ikke lagre persondata.
 */
@Component
public class BrregClient {

    private static final Logger log = LoggerFactory.getLogger(BrregClient.class);

    private static final String ENHET_URL = "https://data.brreg.no/enhetsregisteret/api/enheter/";
    private static final String ENHETER_URL = "https://data.brreg.no/enhetsregisteret/api/enheter";
    private static final String KOMMUNER_URL = "https://data.brreg.no/enhetsregisteret/api/kommuner?size=500";
    private static final String REGNSKAP_URL = "https://data.brreg.no/regnskapsregisteret/regnskap/";
    public static final String OPPSLAG_URL = "https://data.brreg.no/enhetsregisteret/oppslag/enheter/";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TtlCache<String, Enhet> enhetCache = new TtlCache<>(Duration.ofHours(24), 50_000);
    private final TtlCache<String, List<RegnskapYear>> regnskapCache = new TtlCache<>(Duration.ofHours(24), 20_000);
    private volatile Map<String, String> kommuneNameToNumber;

    public BrregClient(BrregHttp http) {
        this.restTemplate = http.restTemplate();
    }

    public Optional<Enhet> fetchEnhet(String orgNumber, UsageTracker usage) {
        String orgNr = normalize(orgNumber);
        if (orgNr == null) return Optional.empty();
        return enhetCache.get(orgNr, () -> {
            try {
                count(usage);
                JsonNode root = get(ENHET_URL + orgNr);
                return root == null ? Optional.empty() : Optional.ofNullable(parseEnhet(root));
            } catch (HttpClientErrorException.NotFound | HttpClientErrorException.Gone e) {
                return Optional.empty();
            } catch (Exception e) {
                log.warn("Klarte ikke hente enhet {}: {}", orgNr, e.getMessage());
                return Optional.empty();
            }
        });
    }

    /**
     * Én side med kriteriesøk. Bransje (naeringskode, prefikser kommaseparert UKODET - kommaet er
     * listeskilletegn hos Brreg), antall ansatte og kommunenummer er ekte server-side filtre.
     */
    public SearchPage search(List<String> naceCodes, Integer minEmployees, Integer maxEmployees,
                             String municipalityNumber, int page, int size, UsageTracker usage) {
        StringBuilder url = new StringBuilder(ENHETER_URL).append("?size=").append(size).append("&page=").append(page);
        if (minEmployees != null) url.append("&fraAntallAnsatte=").append(minEmployees);
        if (maxEmployees != null) url.append("&tilAntallAnsatte=").append(maxEmployees);
        if (naceCodes != null && !naceCodes.isEmpty()) url.append("&naeringskode=").append(String.join(",", naceCodes));
        if (municipalityNumber != null) url.append("&kommunenummer=").append(municipalityNumber);
        // Konkurs/avvikling er uinteressant for prospektering - filtreres hos Brreg.
        url.append("&konkurs=false&underAvvikling=false&underTvangsavviklingEllerTvangsopplosning=false");
        List<Enhet> out = new ArrayList<>();
        try {
            count(usage);
            JsonNode root = get(url.toString());
            if (root == null) return new SearchPage(out, page, 0, 0);
            for (JsonNode e : root.path("_embedded").path("enheter")) {
                Enhet en = parseEnhet(e);
                if (en != null) {
                    out.add(en);
                    enhetCache.get(en.orgNumber(), () -> Optional.of(en)); // varm cache for senere oppslag
                }
            }
            JsonNode p = root.path("page");
            return new SearchPage(out, page, p.path("totalPages").asInt(0), p.path("totalElements").asLong(0));
        } catch (Exception e) {
            log.warn("Brreg-søk feilet ({}): {}", url, e.getMessage());
            return new SearchPage(out, page, 0, 0);
        }
    }

    /** Årsregnskap, nyeste først. Tom liste ved feil eller manglende regnskap. */
    public List<RegnskapYear> fetchRegnskap(String orgNumber, UsageTracker usage) {
        String orgNr = normalize(orgNumber);
        if (orgNr == null) return List.of();
        return regnskapCache.get(orgNr, () -> {
            List<RegnskapYear> out = new ArrayList<>();
            try {
                count(usage);
                JsonNode root = get(REGNSKAP_URL + orgNr);
                if (root == null) return Optional.of(out);
                List<JsonNode> entries = new ArrayList<>();
                if (root.isArray()) root.forEach(entries::add); else if (root.isObject()) entries.add(root);
                // Morselskap i konsern har både SELSKAP- og KONSERN-regnskap for samme år. Bruk
                // selskapsregnskapet (samme enhet som ansatte/bransje), ett per år.
                Map<Integer, RegnskapYear> byYear = new HashMap<>();
                Map<Integer, Boolean> isKonsern = new HashMap<>();
                for (JsonNode entry : entries) {
                    LocalDate end = parseDate(text(entry.path("regnskapsperiode"), "tilDato"));
                    if (end == null) continue;
                    JsonNode res = entry.path("resultatregnskapResultat");
                    BigDecimal revenue = decimal(res.path("driftsresultat").path("driftsinntekter").path("sumDriftsinntekter"));
                    if (revenue == null) continue;
                    boolean konsern = "KONSERN".equalsIgnoreCase(entry.path("regnskapstype").asText(""));
                    Boolean existingKonsern = isKonsern.get(end.getYear());
                    if (existingKonsern != null && (konsern || !existingKonsern)) continue;
                    byYear.put(end.getYear(), new RegnskapYear(end.getYear(), revenue,
                            decimal(res.path("ordinaertResultatFoerSkattekostnad")),
                            decimal(entry.path("egenkapitalGjeld").path("egenkapital").path("sumEgenkapital")),
                            decimal(res.path("driftsresultat").path("driftskostnad").path("loennskostnad"))));
                    isKonsern.put(end.getYear(), konsern);
                }
                out.addAll(byYear.values());
                out.sort(Comparator.comparingInt(RegnskapYear::year).reversed());
            } catch (HttpClientErrorException e) {
                log.debug("Ingen regnskap for {} ({})", orgNr, e.getStatusCode());
            } catch (Exception e) {
                log.warn("Klarte ikke hente regnskap for {}: {}", orgNr, e.getMessage());
            }
            return Optional.of(out);
        }).orElse(List.of());
    }

    /** Daglig leder og styreleder (aktive). Ikke cachet - personnavn lagres ikke i tjenesten. */
    public List<PublicRole> fetchKeyRoles(String orgNumber, UsageTracker usage) {
        String orgNr = normalize(orgNumber);
        List<PublicRole> out = new ArrayList<>();
        if (orgNr == null) return out;
        try {
            count(usage);
            JsonNode root = get(ENHET_URL + orgNr + "/roller");
            if (root == null) return out;
            for (JsonNode gruppe : root.path("rollegrupper")) {
                for (JsonNode rolle : gruppe.path("roller")) {
                    if (rolle.path("avregistrert").asBoolean(false)) continue;
                    String code = text(rolle.path("type"), "kode");
                    if (!"DAGL".equals(code) && !"LEDE".equals(code)) continue;
                    JsonNode navn = rolle.path("person").path("navn");
                    if (navn.isMissingNode()) continue;
                    String name = (orEmpty(text(navn, "fornavn")) + " " + orEmpty(text(navn, "mellomnavn")) + " "
                            + orEmpty(text(navn, "etternavn"))).replaceAll("\\s+", " ").trim();
                    if (!name.isBlank()) out.add(new PublicRole(text(rolle.path("type"), "beskrivelse"), name));
                }
            }
        } catch (Exception e) {
            log.debug("Klarte ikke hente roller for {}: {}", orgNr, e.getMessage());
        }
        return out;
    }

    /** Kommunenummer for et stedsnavn (eksakt, ellers delvis treff). Null hvis ukjent. */
    public String municipalityNumber(String name) {
        if (name == null || name.isBlank()) return null;
        Map<String, String> map = kommuner();
        String needle = name.trim().toLowerCase(Locale.ROOT);
        if (needle.matches("\\d{4}")) return needle;
        String exact = map.get(needle);
        if (exact != null) return exact;
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (e.getKey().contains(needle)) return e.getValue();
        }
        return null;
    }

    private Map<String, String> kommuner() {
        Map<String, String> m = kommuneNameToNumber;
        if (m != null) return m;
        synchronized (this) {
            if (kommuneNameToNumber != null) return kommuneNameToNumber;
            Map<String, String> map = new HashMap<>();
            try {
                JsonNode root = get(KOMMUNER_URL);
                for (JsonNode k : root.path("_embedded").path("kommuner")) {
                    String nr = text(k, "nummer");
                    String navn = text(k, "navn");
                    if (nr != null && navn != null) map.put(navn.toLowerCase(Locale.ROOT), nr);
                }
            } catch (Exception e) {
                log.warn("Klarte ikke hente kommuneliste: {}", e.getMessage());
                return map;
            }
            kommuneNameToNumber = map;
            return map;
        }
    }

    private Enhet parseEnhet(JsonNode root) {
        String orgNr = text(root, "organisasjonsnummer");
        String name = text(root, "navn");
        if (orgNr == null || name == null) return null;
        JsonNode nace = root.path("naeringskode1");
        JsonNode addr = root.path("forretningsadresse");
        if (addr.isMissingNode() || addr.isNull()) addr = root.path("postadresse");
        JsonNode sektor = root.path("institusjonellSektorkode");
        return new Enhet(orgNr, name, text(nace, "kode"), text(nace, "beskrivelse"),
                root.hasNonNull("antallAnsatte") ? root.path("antallAnsatte").asInt() : null,
                root.path("konkurs").asBoolean(false),
                root.path("underAvvikling").asBoolean(false),
                root.path("underTvangsavviklingEllerTvangsopplosning").asBoolean(false),
                text(root, "hjemmeside"), text(addr, "poststed"), text(addr, "kommunenummer"),
                text(root, "overordnetEnhet"), text(sektor, "kode"), text(sektor, "beskrivelse"),
                parseDate(text(root, "stiftelsesdato")));
    }

    private JsonNode get(String url) throws Exception {
        String body = restTemplate.getForObject(url, String.class);
        return body == null || body.isBlank() ? null : objectMapper.readTree(body);
    }

    private static void count(UsageTracker usage) {
        if (usage != null) usage.brregCalls.incrementAndGet();
    }

    public static String normalize(String orgNumber) {
        if (orgNumber == null) return null;
        String digits = orgNumber.replaceAll("[^0-9]", "");
        return digits.length() == 9 ? digits : null;
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() || v.asText().isBlank() ? null : v.asText();
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private static BigDecimal decimal(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        try { return new BigDecimal(node.asText()); } catch (Exception e) { return null; }
    }

    private static LocalDate parseDate(String v) {
        if (v == null || v.length() < 10) return null;
        try { return LocalDate.parse(v.substring(0, 10)); } catch (Exception e) { return null; }
    }

}
