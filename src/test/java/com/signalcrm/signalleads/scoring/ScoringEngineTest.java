package com.signalcrm.signalleads.scoring;

import com.signalcrm.signalleads.api.FactorResult;
import com.signalcrm.signalleads.api.ScoringConfig;
import com.signalcrm.signalleads.api.TargetProfile;
import com.signalcrm.signalleads.brreg.RegnskapYear;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ScoringEngineTest {

    private final ScoringEngine engine = new ScoringEngine();
    private final TargetProfile profile = new TargetProfile("Elektro", 50, 500, null, null,
            List.of("Elektrisk installasjonsarbeid"), List.of("43.21"), null, "NO", null, null, null, null, null);
    private final ScoringEngine.IndustryCriteria industry =
            new ScoringEngine.IndustryCriteria(List.of("43.21"), List.of());
    private final ScoringConfig weights = new ScoringConfig(Map.of("industry", 20.0, "size", 15.0,
            "buyingSignals", 25.0, "needsFit", 20.0), null, null);

    private CompanyFacts facts(String nace, Integer employees, List<RegnskapYear> regnskap) {
        return new CompanyFacts(nace, "Elektrisk installasjonsarbeid", employees, null, regnskap, null, null, "Ikke analysert", true);
    }

    @Test
    void ukjentTellerSomNullOgGirLavDekning() {
        ScoreResult r = engine.score(profile, weights, industry, ReferenceProfile.empty(), facts("43.210", 120, null));
        // bransje og størrelse treffer (35 av 80 i vekt), KI-faktorene er ukjente
        assertEquals(44, r.score());
        assertEquals(0.44, r.coverage(), 0.001);
        assertEquals("C", r.priority());
        assertEquals("UNKNOWN", status(r, "buyingSignals"));
    }

    @Test
    void kriterieUtenVerdiTellerIkke() {
        TargetProfile noSize = new TargetProfile("x", null, null, null, null, null, List.of("43.21"),
                null, null, null, null, null, null, null);
        ScoreResult r = engine.score(noSize, new ScoringConfig(Map.of("industry", 1.0, "size", 1.0), null, null),
                industry, ReferenceProfile.empty(), facts("43.210", 3, null));
        assertEquals("NOT_APPLICABLE", status(r, "size"));
        assertEquals(100, r.score());
        assertEquals("A", r.priority());
    }

    @Test
    void feilBransjeErNegativtIkkeUkjent() {
        ScoreResult r = engine.score(profile, new ScoringConfig(Map.of("industry", 1.0), null, null),
                industry, ReferenceProfile.empty(), facts("47.110", 100, null));
        assertEquals("NEGATIVE", status(r, "industry"));
        assertEquals(0, r.score());
        assertEquals(1.0, r.coverage(), 0.001);
    }

    @Test
    void storrelseUtenforGirGradvisLavereScore() {
        ScoringConfig sizeOnly = new ScoringConfig(Map.of("size", 1.0), null, null);
        int close = engine.score(profile, sizeOnly, industry, ReferenceProfile.empty(), facts("43.210", 40, null)).score();
        int far = engine.score(profile, sizeOnly, industry, ReferenceProfile.empty(), facts("43.210", 5, null)).score();
        assertTrue(close > far);
        assertEquals(64, close);
    }

    @Test
    void konkursDiskvalifiserer() {
        CompanyFacts f = new CompanyFacts("43.210", "x", 100, "Registrert konkurs.", null, null, null, null, true);
        ScoreResult r = engine.score(profile, weights, industry, ReferenceProfile.empty(), f);
        assertTrue(r.disqualified());
        assertEquals(0, r.score());
    }

    @Test
    void vekstOgOkonomiFraRegnskap() {
        List<RegnskapYear> r = List.of(
                new RegnskapYear(2024, new BigDecimal("120"), new BigDecimal("5"), new BigDecimal("50"), null),
                new RegnskapYear(2023, new BigDecimal("100"), new BigDecimal("4"), new BigDecimal("45"), null));
        ScoreResult res = engine.score(profile, new ScoringConfig(Map.of("growth", 1.0, "financialHealth", 1.0), null, null),
                industry, ReferenceProfile.empty(), facts("43.210", 100, r));
        assertEquals(100, res.score());
    }

    @Test
    void likhetKreverMinstTreReferanser() {
        ScoringConfig cfg = new ScoringConfig(Map.of("lookalike", 1.0), null, null);
        ReferenceProfile two = new ReferenceProfile(List.of(new ReferenceProfile.Ref("1", "43.210", 100),
                new ReferenceProfile.Ref("2", "43.210", 80)));
        assertEquals("NOT_APPLICABLE", status(engine.score(profile, cfg, industry, two, facts("43.210", 100, null)), "lookalike"));
        ReferenceProfile three = new ReferenceProfile(List.of(new ReferenceProfile.Ref("1", "43.210", 100),
                new ReferenceProfile.Ref("2", "43.210", 80), new ReferenceProfile.Ref("3", "71.129", 3000)));
        ScoreResult r = engine.score(profile, cfg, industry, three, facts("43.210", 100, null));
        assertEquals("POSITIVE", status(r, "lookalike"));
    }

    @Test
    void naceLikhet() {
        assertEquals(100, ScoringEngine.naceSimilarity("43.210", "43.210"));
        assertEquals(85, ScoringEngine.naceSimilarity("43.211", "43.219"));
        assertEquals(70, ScoringEngine.naceSimilarity("43.210", "43.290"));
        assertEquals(55, ScoringEngine.naceSimilarity("43.210", "43.110"));
        assertEquals(0, ScoringEngine.naceSimilarity("43.210", "47.110"));
    }

    private static String status(ScoreResult r, String key) {
        return r.factors().stream().filter(f -> f.key().equals(key)).map(FactorResult::status).findFirst().orElseThrow();
    }
}
