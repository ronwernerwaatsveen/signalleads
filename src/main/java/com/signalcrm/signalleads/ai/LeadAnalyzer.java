package com.signalcrm.signalleads.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.signalcrm.signalleads.api.AiAnalysis;
import com.signalcrm.signalleads.api.Evidence;
import com.signalcrm.signalleads.api.ProductInfo;
import com.signalcrm.signalleads.api.SellerContext;
import com.signalcrm.signalleads.api.Signal;
import com.signalcrm.signalleads.api.TargetProfile;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * KI-analyse av én bedrift mot selgerens produkter og profil.
 *
 * Instruksjonen bygges KUN fra det SignalCRM sender (selger, produkter, profil) - ingenting om et
 * bestemt produkt eller firma er skrevet inn her. KI-en får kildene nummerert (E1, E2 ...) og må
 * sitere ordrett. {@link #validate} kaster alle kjøpssignaler der sitatet ikke finnes i kilden:
 * KI skal aldri kunne «finne» en ISO-sertifisering eller et behov som ikke står noe sted.
 * Mulige behov uten kilde beholdes, men merkes som hypotese.
 */
@Component
public class LeadAnalyzer {

    private static final int MAX_PRODUCT_CHARS = 1500;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GeminiClient gemini;

    public LeadAnalyzer(GeminiClient gemini) {
        this.gemini = gemini;
    }

    public boolean isAvailable() {
        return gemini.isConfigured();
    }

    public AnalysisOutcome analyze(SellerContext seller, TargetProfile profile, String companyName,
                                   List<SourceChunk> sources, String model) {
        String prompt = buildPrompt(seller, profile, companyName, sources);
        LlmResponse res = gemini.completeJson(prompt, model);
        JsonNode root;
        try {
            String t = res.text();
            int s = t.indexOf('{'), e = t.lastIndexOf('}');
            root = objectMapper.readTree(s >= 0 && e > s ? t.substring(s, e + 1) : t);
        } catch (Exception ex) {
            throw new IllegalStateException("KI-svaret var ikke gyldig JSON");
        }
        return validate(root, sources, res);
    }

    String buildPrompt(SellerContext seller, TargetProfile profile, String companyName, List<SourceChunk> sources) {
        boolean english = seller != null && seller.language() != null && seller.language().toLowerCase(Locale.ROOT).startsWith("en");
        StringBuilder sb = new StringBuilder();
        sb.append("Du er en nøktern B2B-salgsanalytiker. Vurder om bedriften under er en aktuell kunde for selgerens produkter.\n\n");
        sb.append("REGLER:\n")
          .append("- Bruk KUN kildene E1, E2 ... under. Ikke bruk egen kunnskap om bedriften.\n")
          .append("- Et kjøpssignal MÅ ha \"source\" (kilde-id) og \"quote\": et ordrett utdrag på 8-200 tegn kopiert fra den kilden. Uten slikt sitat: ikke ta det med.\n")
          .append("- Aldri påstå sertifiseringer, problemer, systemer eller planer som ikke står ordrett i en kilde.\n")
          .append("- Mulige behov uten direkte kilde er tillatt, men sett \"source\": null - de vises som hypoteser.\n")
          .append("- Skill fakta fra antakelser. List manglende informasjon.\n")
          .append("- Skriv på ").append(english ? "engelsk" : "norsk bokmål").append(".\n\n");

        sb.append("SELGER: ").append(seller != null && seller.sellerName() != null ? seller.sellerName() : "(ikke oppgitt)").append("\n");
        if (seller != null && seller.products() != null && !seller.products().isEmpty()) {
            sb.append("PRODUKTER:\n");
            for (ProductInfo p : seller.products()) {
                sb.append("- ").append(nz(p.name()));
                if (p.summary() != null) sb.append(": ").append(p.summary());
                sb.append("\n");
                if (p.description() != null && !p.description().isBlank()) {
                    sb.append("  ").append(truncate(p.description().replaceAll("\\s+", " "), MAX_PRODUCT_CHARS)).append("\n");
                }
            }
        }
        sb.append("\nMÅLGRUPPE (kundeprofil \"").append(nz(profile.name())).append("\"):\n");
        appendList(sb, "Bransjer", profile.industries());
        if (profile.minEmployees() != null || profile.maxEmployees() != null)
            sb.append("- Ansatte: ").append(range(profile.minEmployees(), profile.maxEmployees())).append("\n");
        if (profile.minRevenue() != null || profile.maxRevenue() != null)
            sb.append("- Omsetning: ").append(range(profile.minRevenue(), profile.maxRevenue())).append("\n");
        appendList(sb, "Ønskede/relevante sertifiseringer", profile.certifications());
        appendList(sb, "Relevante beslutningstakerroller", profile.relevantRoles());

        sb.append("\nBEDRIFT: ").append(nz(companyName)).append("\n\nKILDER:\n");
        for (SourceChunk c : sources) {
            sb.append("[").append(c.id()).append("] ").append(c.source());
            if (c.url() != null) sb.append(" ").append(c.url());
            if (c.title() != null && !c.title().isBlank()) sb.append(" - ").append(c.title());
            sb.append("\n").append(c.text()).append("\n\n");
        }
        sb.append("""
                Svar KUN med ett JSON-objekt med nøyaktig disse nøklene:
                {
                  "summary": "2-3 setninger om bedriften og hvorfor den passer eller ikke",
                  "needsFit": {"score": 0-100, "reason": "...", "sources": ["E1"]},
                  "buyingSignals": [{"text": "...", "strength": 1-3, "source": "E2", "quote": "ordrett utdrag"}],
                  "painPoints": [{"text": "...", "source": "E3" eller null, "quote": "ordrett utdrag" eller null}],
                  "decisionMakerRoles": ["rolle"],
                  "salesApproach": "forslag til personlig, saklig salgsinngang basert på kildene",
                  "recommendedProducts": ["produktnavn fra listen over"],
                  "assumptions": ["..."],
                  "missingInformation": ["..."],
                  "confidence": 0.0-1.0
                }
                needsFit.score: hvor godt produktene passer bedriftens faktiske virksomhet slik kildene beskriver den. Kilde-id-er er påkrevd.
                strength: 3 = konkret behov eller anskaffelse nå, 2 = tydelig relevant forhold, 1 = svak indikasjon.
                """);
        return sb.toString();
    }

    AnalysisOutcome validate(JsonNode root, List<SourceChunk> sources, LlmResponse res) {
        Map<String, SourceChunk> byId = sources.stream().collect(Collectors.toMap(SourceChunk::id, Function.identity()));
        List<Evidence> evidence = new ArrayList<>();
        int rejected = 0;
        String now = Instant.now().toString();

        List<Signal> signals = new ArrayList<>();
        for (JsonNode s : root.path("buyingSignals")) {
            Evidence ev = quoteEvidence(byId, s, evidence.size() + 1, now);
            if (ev == null) { rejected++; continue; }
            evidence.add(ev);
            int strength = Math.max(1, Math.min(3, s.path("strength").asInt(1)));
            signals.add(new Signal(s.path("text").asText(""), ev.id(), strength, false));
        }

        List<Signal> pains = new ArrayList<>();
        for (JsonNode p : root.path("painPoints")) {
            String text = p.path("text").asText("");
            if (text.isBlank()) continue;
            Evidence ev = quoteEvidence(byId, p, evidence.size() + 1, now);
            if (ev != null) {
                evidence.add(ev);
                pains.add(new Signal(text, ev.id(), null, false));
            } else {
                // En påstått kilde som ikke holder er en avvist påstand; uten kilde er det bare en hypotese.
                if (!p.path("source").isNull() && !p.path("source").asText("").isBlank()) rejected++;
                pains.add(new Signal(text, null, null, true));
            }
        }

        JsonNode nf = root.path("needsFit");
        Set<String> nfIds = new LinkedHashSet<>();
        for (JsonNode id : nf.path("sources")) if (byId.containsKey(id.asText())) nfIds.add(id.asText());
        Integer needsFit = null;
        if (!nfIds.isEmpty() && nf.has("score") && nf.path("score").isNumber()) {
            needsFit = Math.max(0, Math.min(100, nf.path("score").asInt()));
        }
        Double confidence = root.path("confidence").isNumber()
                ? Math.max(0, Math.min(1, root.path("confidence").asDouble())) : null;

        AiAnalysis ai = new AiAnalysis(root.path("summary").asText(null), signals, pains,
                strings(root.path("decisionMakerRoles")), root.path("salesApproach").asText(null),
                strings(root.path("recommendedProducts")), strings(root.path("assumptions")),
                strings(root.path("missingInformation")), confidence, rejected, res.model());
        return new AnalysisOutcome(ai, needsFit, nf.path("reason").asText(null), new ArrayList<>(nfIds),
                evidence, res.inputTokens(), res.outputTokens());
    }

    /** Godtar kun et sitat som faktisk står i kilden (uavhengig av store/små bokstaver og mellomrom). */
    private Evidence quoteEvidence(Map<String, SourceChunk> byId, JsonNode item, int n, String now) {
        SourceChunk src = byId.get(item.path("source").asText(""));
        String quote = item.path("quote").asText("").trim();
        if (src == null || quote.length() < 8) return null;
        if (!normalize(src.text()).contains(normalize(quote))) return null;
        return new Evidence("Q" + n, src.source(), src.url(), src.title(), truncate(quote, 300), now);
    }

    static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[\\s\\u00a0]+", " ")
                .replaceAll("[\"«»“”'’]", "").trim();
    }

    private static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : arr) if (!n.asText("").isBlank()) out.add(n.asText());
        return out;
    }

    private static void appendList(StringBuilder sb, String label, List<String> values) {
        if (values != null && !values.isEmpty()) sb.append("- ").append(label).append(": ").append(String.join(", ", values)).append("\n");
    }

    private static String range(Object min, Object max) {
        if (min != null && max != null) return min + " - " + max;
        return min != null ? "fra " + min : "opp til " + max;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + " …";
    }
}
