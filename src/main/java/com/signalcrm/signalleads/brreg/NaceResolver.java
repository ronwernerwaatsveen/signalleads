package com.signalcrm.signalleads.brreg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Tolker bransjetekstene i en kundeprofil til NACE-kodeprefikser (SN2007), slik at bransje kan
 * sammenlignes presist på kode og ikke på tekst. Brreg sine detaljerte bransjebeskrivelser
 * inneholder aldri seksjonsnavnet ordrett ("Bygge- og anleggsvirksomhet"), så tekstsammenligning
 * alene gir feil svar.
 *
 * Rekkefølge (samme som SignalCRMs BrregApiClient#advancedSearch): (1) en kode skrevet direkte,
 * (2) detaljert SN2007-tekst fra SSB Klass (hentes live og caches), (3) de 88 divisjonene,
 * (4) de 21 seksjonene. Tabellene er offentlig kodeverk, ikke kundeinnstillinger.
 */
@Component
public class NaceResolver {

    private static final Logger log = LoggerFactory.getLogger(NaceResolver.class);
    private static final Pattern CODE = Pattern.compile("^\\d{2}(\\.\\d{1,3})?$");
    private static final String SSB_KLASS_LEAF_CODES_URL =
            "https://data.ssb.no/api/klass/v1/classifications/6/codes?from=2020-01-01&selectLevel=5&includeFuture=false";

    private static final Map<String, int[]> SECTIONS = new LinkedHashMap<>();
    private static final Map<String, Integer> DIVISIONS = new LinkedHashMap<>();
    static {
        SECTIONS.put("jordbruk, skogbruk og fiske", new int[]{1, 3});
        SECTIONS.put("bergverksdrift og utvinning", new int[]{5, 9});
        SECTIONS.put("industri", new int[]{10, 33});
        SECTIONS.put("elektrisitets-, gass-, damp- og varmtvannsforsyning", new int[]{35, 35});
        SECTIONS.put("vannforsyning, avløps- og renovasjonsvirksomhet", new int[]{36, 39});
        SECTIONS.put("bygge- og anleggsvirksomhet", new int[]{41, 43});
        SECTIONS.put("varehandel, reparasjon av motorvogner", new int[]{45, 47});
        SECTIONS.put("transport og lagring", new int[]{49, 53});
        SECTIONS.put("overnattings- og serveringsvirksomhet", new int[]{55, 56});
        SECTIONS.put("informasjon og kommunikasjon", new int[]{58, 63});
        SECTIONS.put("finansierings- og forsikringsvirksomhet", new int[]{64, 66});
        SECTIONS.put("omsetning og drift av fast eiendom", new int[]{68, 68});
        SECTIONS.put("faglig, vitenskapelig og teknisk tjenesteyting", new int[]{69, 75});
        SECTIONS.put("forretningsmessig tjenesteyting", new int[]{77, 82});
        SECTIONS.put("offentlig administrasjon og forsvar, og trygdeordninger underlagt offentlig forvaltning", new int[]{84, 84});
        SECTIONS.put("undervisning", new int[]{85, 85});
        SECTIONS.put("helse- og sosialtjenester", new int[]{86, 88});
        SECTIONS.put("kulturell virksomhet, underholdning og fritidsaktiviteter", new int[]{90, 93});
        SECTIONS.put("annen tjenesteyting", new int[]{94, 96});
        SECTIONS.put("lønnet arbeid i private husholdninger", new int[]{97, 98});
        SECTIONS.put("internasjonale organisasjoner og organer", new int[]{99, 99});
        DIVISIONS.put("jordbruk og tjenester tilknyttet jordbruk, jakt og viltstell", 1);
        DIVISIONS.put("skogbruk og tjenester tilknyttet skogbruk", 2);
        DIVISIONS.put("fiske, fangst og akvakultur", 3);
        DIVISIONS.put("bryting av steinkull og brunkull", 5);
        DIVISIONS.put("utvinning av råolje og naturgass", 6);
        DIVISIONS.put("bryting av metallholdig malm", 7);
        DIVISIONS.put("bryting og bergverksdrift ellers", 8);
        DIVISIONS.put("tjenester tilknyttet bergverksdrift og utvinning", 9);
        DIVISIONS.put("produksjon av nærings- og nytelsesmidler", 10);
        DIVISIONS.put("produksjon av drikkevarer", 11);
        DIVISIONS.put("produksjon av tobakksvarer", 12);
        DIVISIONS.put("produksjon av tekstiler", 13);
        DIVISIONS.put("produksjon av klær", 14);
        DIVISIONS.put("produksjon av lær og lærvarer", 15);
        DIVISIONS.put("produksjon av trelast og varer av tre, kork, strå og flettematerialer, unntatt møbler", 16);
        DIVISIONS.put("produksjon av papir og papirvarer", 17);
        DIVISIONS.put("trykking og reproduksjon av innspilte opptak", 18);
        DIVISIONS.put("produksjon av kull- og raffinerte petroleumsprodukter", 19);
        DIVISIONS.put("produksjon av kjemikalier og kjemiske produkter", 20);
        DIVISIONS.put("produksjon av farmasøytiske råvarer og preparater", 21);
        DIVISIONS.put("produksjon av gummi- og plastprodukter", 22);
        DIVISIONS.put("produksjon av andre ikke-metallholdige mineralprodukter", 23);
        DIVISIONS.put("produksjon av metaller", 24);
        DIVISIONS.put("produksjon av metallvarer, unntatt maskiner og utstyr", 25);
        DIVISIONS.put("produksjon av datamaskiner og elektroniske og optiske produkter", 26);
        DIVISIONS.put("produksjon av elektrisk utstyr", 27);
        DIVISIONS.put("produksjon av maskiner og utstyr til generell bruk, ikke nevnt annet sted", 28);
        DIVISIONS.put("produksjon av motorvogner og tilhengere", 29);
        DIVISIONS.put("produksjon av andre transportmidler", 30);
        DIVISIONS.put("produksjon av møbler", 31);
        DIVISIONS.put("annen industriproduksjon", 32);
        DIVISIONS.put("reparasjon og installasjon av maskiner og utstyr", 33);
        DIVISIONS.put("elektrisitets-, gass-, damp- og varmtvannsforsyning", 35);
        DIVISIONS.put("uttak fra kilde, rensing og distribusjon av vann", 36);
        DIVISIONS.put("oppsamling og behandling av avløpsvann", 37);
        DIVISIONS.put("innsamling, behandling, disponering og gjenvinning av avfall", 38);
        DIVISIONS.put("miljørydding, miljørensing og lignende virksomhet", 39);
        DIVISIONS.put("oppføring av bygninger", 41);
        DIVISIONS.put("anleggsvirksomhet", 42);
        DIVISIONS.put("spesialisert bygge- og anleggsvirksomhet", 43);
        DIVISIONS.put("handel med og reparasjon av motorvogner", 45);
        DIVISIONS.put("agentur- og engroshandel, unntatt med motorvogner", 46);
        DIVISIONS.put("detaljhandel, unntatt med motorvogner", 47);
        DIVISIONS.put("landtransport og rørtransport", 49);
        DIVISIONS.put("sjøfart", 50);
        DIVISIONS.put("lufttransport", 51);
        DIVISIONS.put("lagring og andre tjenester tilknyttet transport", 52);
        DIVISIONS.put("post og distribusjonsvirksomhet", 53);
        DIVISIONS.put("overnattingsvirksomhet", 55);
        DIVISIONS.put("serveringsvirksomhet", 56);
        DIVISIONS.put("forlagsvirksomhet", 58);
        DIVISIONS.put("film-, video- og fjernsynsprogramproduksjon, utgivelse av musikk- og lydopptak", 59);
        DIVISIONS.put("radio- og fjernsynskringkasting", 60);
        DIVISIONS.put("telekommunikasjon", 61);
        DIVISIONS.put("tjenester tilknyttet informasjonsteknologi", 62);
        DIVISIONS.put("informasjonstjenester", 63);
        DIVISIONS.put("finansieringsvirksomhet", 64);
        DIVISIONS.put("forsikringsvirksomhet og pensjonskasser, unntatt trygdeordninger underlagt offentlig forvaltning", 65);
        DIVISIONS.put("tjenester tilknyttet finansierings- og forsikringsvirksomhet", 66);
        DIVISIONS.put("omsetning og drift av fast eiendom", 68);
        DIVISIONS.put("juridisk og regnskapsmessig tjenesteyting", 69);
        DIVISIONS.put("hovedkontortjenester, administrativ rådgivning", 70);
        DIVISIONS.put("arkitektvirksomhet og teknisk konsulentvirksomhet, og teknisk prøving og analyse", 71);
        DIVISIONS.put("forskning og utviklingsarbeid", 72);
        DIVISIONS.put("annonse- og reklamevirksomhet og markedsundersøkelser", 73);
        DIVISIONS.put("annen faglig, vitenskapelig og teknisk virksomhet", 74);
        DIVISIONS.put("veterinærtjenester", 75);
        DIVISIONS.put("utleie- og leasingvirksomhet", 77);
        DIVISIONS.put("arbeidskrafttjenester", 78);
        DIVISIONS.put("reisebyrå- og reisearrangørvirksomhet og tilknyttede tjenester", 79);
        DIVISIONS.put("vakttjeneste og etterforsking", 80);
        DIVISIONS.put("tjenester tilknyttet eiendomsdrift", 81);
        DIVISIONS.put("annen forretningsmessig tjenesteyting", 82);
        DIVISIONS.put("offentlig administrasjon og forsvar, og trygdeordninger underlagt offentlig forvaltning", 84);
        DIVISIONS.put("undervisning", 85);
        DIVISIONS.put("helsetjenester", 86);
        DIVISIONS.put("pleie- og omsorgstjenester i institusjon", 87);
        DIVISIONS.put("sosiale omsorgstjenester uten botilbud", 88);
        DIVISIONS.put("kunstnerisk virksomhet og underholdningsvirksomhet", 90);
        DIVISIONS.put("drift av biblioteker, arkiver, museer og annen kulturvirksomhet", 91);
        DIVISIONS.put("lotteri og totalisatorspill", 92);
        DIVISIONS.put("sports- og fritidsaktiviteter og drift av fornøyelsesetablissementer", 93);
        DIVISIONS.put("aktiviteter i medlemsorganisasjoner", 94);
        DIVISIONS.put("reparasjon av datamaskiner, husholdningsvarer og varer til personlig bruk", 95);
        DIVISIONS.put("annen personlig tjenesteyting", 96);
        DIVISIONS.put("lønnet arbeid i private husholdninger", 97);
        DIVISIONS.put("internasjonale organisasjoner og organer", 99);
    }

    /** Norsk praksis avviker fra standarden: bilforhandlere og -verksteder står på 47.81 og 95.3,
     *  ikke på divisjon 45 (se SignalCRMs BrregApiClient#DIVISION_CODE_OVERRIDES). */
    private static final Map<Integer, List<String>> DIVISION_OVERRIDES = Map.of(45, List.of("47.81", "95.3"));

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile Map<String, String> leafNameToCode;

    public NaceResolver(BrregHttp http) {
        this.restTemplate = http.restTemplate();
    }

    /** Kodeprefikser for én bransjetekst; tom liste hvis teksten ikke gjenkjennes (da brukes tekstsammenligning). */
    public List<String> resolve(String industryText) {
        List<String> out = new ArrayList<>();
        if (industryText == null || industryText.isBlank()) return out;
        String key = industryText.trim().toLowerCase(Locale.ROOT);
        if (CODE.matcher(key).matches()) {
            out.add(key);
            return out;
        }
        String leaf = leafCodes().get(key);
        if (leaf != null) {
            out.add(leaf);
            return out;
        }
        Integer division = DIVISIONS.get(key);
        if (division != null) return divisionCodes(division);
        int[] range = SECTIONS.get(key);
        if (range != null) {
            for (int d = range[0]; d <= range[1]; d++) out.addAll(divisionCodes(d));
        }
        return out;
    }

    /** Alle prefikser for en liste bransjetekster og eksplisitte koder, uten duplikater. */
    public List<String> resolveAll(List<String> industries, List<String> codes) {
        Set<String> all = new LinkedHashSet<>();
        if (codes != null) for (String c : codes) if (c != null && !c.isBlank()) all.add(c.trim());
        if (industries != null) for (String i : industries) all.addAll(resolve(i));
        return new ArrayList<>(all);
    }

    /** Tekster som ikke lot seg tolke til kode - de sammenlignes som tekst mot bransjebeskrivelsen. */
    public List<String> unresolved(List<String> industries) {
        List<String> out = new ArrayList<>();
        if (industries == null) return out;
        for (String i : industries) if (i != null && !i.isBlank() && resolve(i).isEmpty()) out.add(i.trim());
        return out;
    }

    public static boolean codeMatches(String companyCode, List<String> prefixes) {
        if (companyCode == null || prefixes == null) return false;
        for (String p : prefixes) if (companyCode.startsWith(p)) return true;
        return false;
    }

    private static List<String> divisionCodes(int division) {
        List<String> o = DIVISION_OVERRIDES.get(division);
        return o != null ? o : List.of(String.format("%02d", division));
    }

    private Map<String, String> leafCodes() {
        Map<String, String> m = leafNameToCode;
        if (m != null) return m;
        synchronized (this) {
            if (leafNameToCode != null) return leafNameToCode;
            Map<String, String> map = new HashMap<>();
            try {
                String body = restTemplate.getForObject(SSB_KLASS_LEAF_CODES_URL, String.class);
                JsonNode list = objectMapper.readTree(body).path("codes");
                for (JsonNode c : list) {
                    String code = c.path("code").asText(null);
                    String name = c.path("name").asText(null);
                    if (code != null && name != null) map.putIfAbsent(name.toLowerCase(Locale.ROOT), code);
                }
            } catch (Exception e) {
                // Ikke cache en tom tabell for alltid - prøv igjen ved neste oppslag.
                log.warn("Klarte ikke hente SN2007-kodeverk fra SSB Klass: {}", e.getMessage());
                return map;
            }
            leafNameToCode = map;
            return map;
        }
    }
}
