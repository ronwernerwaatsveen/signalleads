package com.signalcrm.signalleads.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LeadAnalyzerTest {

    private final LeadAnalyzer analyzer = new LeadAnalyzer(null);
    private final List<SourceChunk> sources = List.of(
            new SourceChunk("E1", "BRREG", "https://data.brreg.no/x", "Enhetsregisteret", "Navn: Test AS. Antall ansatte: 120."),
            new SourceChunk("E3", "WEBSITE", "https://test.no/om", "Om oss",
                    "Vi søker en  KVALITETSLEDER som skal bygge opp vårt styringssystem etter ISO 9001."));

    @Test
    void godtarBareSitaterSomStaarIKilden() throws Exception {
        String json = """
                {"summary":"s",
                 "needsFit":{"score":80,"reason":"r","sources":["E3","E9"]},
                 "buyingSignals":[
                   {"text":"Ansetter kvalitetsleder","strength":3,"source":"E3","quote":"søker en kvalitetsleder som skal bygge opp"},
                   {"text":"ISO 14001-sertifisert","strength":2,"source":"E3","quote":"sertifisert etter ISO 14001"},
                   {"text":"Ukjent kilde","strength":2,"source":"E7","quote":"noe som ikke finnes her"}],
                 "painPoints":[{"text":"Mangler system","source":null,"quote":null},
                               {"text":"Påstått","source":"E3","quote":"har store HMS-problemer"}],
                 "confidence":1.7}
                """;
        AnalysisOutcome out = analyzer.validate(new ObjectMapper().readTree(json), sources,
                new LlmResponse("", 10, 5, "m"));
        assertEquals(1, out.ai().buyingSignals().size());
        assertEquals(3, out.ai().buyingSignals().get(0).strength());
        assertEquals(3, out.ai().rejectedClaims()); // to ugyldige signaler + ett behov med falsk kilde
        assertEquals(2, out.ai().painPoints().size());
        assertTrue(out.ai().painPoints().stream().allMatch(p -> p.hypothesis()));
        assertEquals(80, out.needsFitScore());
        assertEquals(List.of("E3"), out.needsFitEvidenceIds());
        assertEquals(1.0, out.ai().confidence());
        assertEquals("https://test.no/om", out.quotedEvidence().get(0).url());
    }

    @Test
    void behovsmatchUtenGyldigKildeBlirUkjent() throws Exception {
        String json = "{\"needsFit\":{\"score\":90,\"sources\":[\"E8\"]}}";
        AnalysisOutcome out = analyzer.validate(new ObjectMapper().readTree(json), sources, new LlmResponse("", 0, 0, "m"));
        assertNull(out.needsFitScore());
    }
}
