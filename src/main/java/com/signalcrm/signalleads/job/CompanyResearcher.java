package com.signalcrm.signalleads.job;

import com.signalcrm.signalleads.ai.AnalysisOutcome;
import com.signalcrm.signalleads.ai.LeadAnalyzer;
import com.signalcrm.signalleads.ai.SourceChunk;
import com.signalcrm.signalleads.api.CandidateResult;
import com.signalcrm.signalleads.api.ContextItem;
import com.signalcrm.signalleads.api.Evidence;
import com.signalcrm.signalleads.api.FactorResult;
import com.signalcrm.signalleads.brreg.BrregClient;
import com.signalcrm.signalleads.brreg.RegnskapYear;
import com.signalcrm.signalleads.scoring.CompanyFacts;
import com.signalcrm.signalleads.scoring.ScoringEngine;
import com.signalcrm.signalleads.web.WebPage;
import com.signalcrm.signalleads.web.WebsiteFetcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Stegene for én bedrift: berik (regnskap, nettside), analyser med KI, score, og bygg resultatet
 * med kildene. Brukes både av enkeltanalyse og av prospektering (der bare de beste går videre
 * til de dyre stegene).
 */
@Component
class CompanyResearcher {

    private static final Logger log = LoggerFactory.getLogger(CompanyResearcher.class);
    private static final int MAX_CONTEXT_CHARS = 6000;
    private static final Set<String> BRREG_FACTORS = Set.of(ScoringEngine.INDUSTRY, ScoringEngine.SIZE);
    private static final Set<String> REGNSKAP_FACTORS = Set.of(ScoringEngine.REVENUE, ScoringEngine.HEALTH, ScoringEngine.GROWTH);

    private final BrregClient brreg;
    private final WebsiteFetcher web;
    private final LeadAnalyzer analyzer;
    private final ScoringEngine engine;

    CompanyResearcher(BrregClient brreg, WebsiteFetcher web, LeadAnalyzer analyzer, ScoringEngine engine) {
        this.brreg = brreg;
        this.web = web;
        this.analyzer = analyzer;
        this.engine = engine;
    }

    boolean aiAvailable() {
        return analyzer.isAvailable();
    }

    /** Regnskap og nettside - billige, offentlige oppslag. */
    void enrich(Candidate c, ResearchContext ctx) {
        if (c.orgNumber != null && c.regnskap == null) c.regnskap = brreg.fetchRegnskap(c.orgNumber, ctx.usage());
        if (c.pages == null) {
            if (c.website != null && !c.website.isBlank() && ctx.pagesPerSite() > 0) {
                c.pages = web.fetchSite(c.website, ctx.websiteKeywords(), ctx.pagesPerSite());
                ctx.usage().websites.incrementAndGet();
            } else {
                c.pages = List.of();
            }
        }
    }

    /** KI-analyse. Feil stopper ikke jobben - bedriften scores da uten KI-faktorene (UNKNOWN). */
    void analyze(Candidate c, ResearchContext ctx) {
        if (!analyzer.isAvailable()) {
            c.aiNote = "KI er ikke slått på.";
            return;
        }
        List<SourceChunk> sources = sources(c);
        try {
            AnalysisOutcome out = analyzer.analyze(ctx.seller(), ctx.profile(), c.name, sources, ctx.aiModel());
            c.ai = out;
            ctx.usage().aiCalls.incrementAndGet();
            ctx.usage().inputTokens.addAndGet(out.inputTokens());
            ctx.usage().outputTokens.addAndGet(out.outputTokens());
        } catch (Exception e) {
            ctx.usage().aiCalls.incrementAndGet();
            c.aiNote = "KI-analysen feilet: " + e.getMessage();
            log.warn("KI-analyse feilet for {}: {}", c.name, e.getMessage());
        }
    }

    void score(Candidate c, ResearchContext ctx) {
        String inactive = null;
        if (c.enhet != null && c.enhet.inactive()) {
            inactive = c.enhet.bankrupt() ? "Registrert konkurs." : "Under avvikling.";
        }
        int webChars = c.pages == null ? 0 : c.pages.stream().mapToInt(p -> p.text().length()).sum();
        int ctxChars = c.context.stream().mapToInt(i -> i.text() == null ? 0 : i.text().length()).sum();
        CompanyFacts facts = new CompanyFacts(c.naceCode, c.naceDescription, c.employees, inactive, c.regnskap,
                c.pages == null && c.context.isEmpty() ? null : webChars + ctxChars,
                c.ai, c.aiNote, analyzer.isAvailable());
        c.score = engine.score(ctx.profile(), ctx.scoring(), ctx.industry(), ctx.references(), facts);
    }

    /** Kildene i fast rekkefølge: E1 Brreg, E2 regnskap, deretter nettsider og kontekst fra SignalCRM. */
    List<SourceChunk> sources(Candidate c) {
        List<SourceChunk> out = new ArrayList<>();
        if (c.orgNumber != null) {
            StringBuilder b = new StringBuilder("Navn: ").append(c.name).append(". ");
            if (c.naceCode != null) b.append("Næringskode: ").append(c.naceCode).append(" ").append(nz(c.naceDescription)).append(". ");
            if (c.employees != null) b.append("Antall ansatte: ").append(c.employees).append(". ");
            if (c.city != null) b.append("Forretningsadresse: ").append(c.city).append(". ");
            if (c.enhet != null && c.enhet.foundedDate() != null) b.append("Stiftet: ").append(c.enhet.foundedDate()).append(". ");
            if (c.enhet != null && c.enhet.parentOrgNumber() != null) b.append("Underenhet/datter av org.nr. ").append(c.enhet.parentOrgNumber()).append(". ");
            if (c.enhet != null && c.enhet.sectorDescription() != null) b.append("Sektor: ").append(c.enhet.sectorDescription()).append(". ");
            out.add(new SourceChunk("E1", "BRREG", BrregClient.OPPSLAG_URL + c.orgNumber, "Enhetsregisteret", b.toString().trim()));
        }
        if (c.regnskap != null && !c.regnskap.isEmpty()) {
            StringBuilder b = new StringBuilder();
            for (RegnskapYear y : c.regnskap.subList(0, Math.min(3, c.regnskap.size()))) {
                b.append(y.year()).append(": omsetning ").append(money(y.revenue()));
                if (y.resultBeforeTax() != null) b.append(", resultat før skatt ").append(money(y.resultBeforeTax()));
                if (y.equity() != null) b.append(", egenkapital ").append(money(y.equity()));
                if (y.wageCost() != null) b.append(", lønnskostnader ").append(money(y.wageCost()));
                b.append(". ");
            }
            out.add(new SourceChunk("E2", "REGNSKAP", "https://data.brreg.no/regnskapsregisteret/regnskap/" + c.orgNumber,
                    "Regnskapsregisteret", b.toString().trim()));
        }
        int n = 3;
        if (c.pages != null) {
            for (WebPage p : c.pages) out.add(new SourceChunk("E" + n++, "WEBSITE", p.url(), p.title(), p.text()));
        }
        for (ContextItem i : c.context) {
            if (i.text() == null || i.text().isBlank()) continue;
            String t = i.text().length() > MAX_CONTEXT_CHARS ? i.text().substring(0, MAX_CONTEXT_CHARS) : i.text();
            out.add(new SourceChunk("E" + n++, "CONTEXT", i.url(), (i.type() == null ? "" : i.type() + ": ") + nz(i.title()), t));
        }
        return out;
    }

    CandidateResult toResult(Candidate c) {
        List<Evidence> evidence = new ArrayList<>();
        String now = Instant.now().toString();
        boolean hasBrreg = false, hasRegnskap = false;
        for (SourceChunk s : sources(c)) {
            // Nettsidetekst sendes ikke tilbake i sin helhet - bare adresse og tittel; sitatene ligger i Q-kildene.
            String quote = "WEBSITE".equals(s.source()) || "CONTEXT".equals(s.source()) ? null : s.text();
            evidence.add(new Evidence(s.id(), s.source(), s.url(), s.title(), quote, now));
            hasBrreg |= "E1".equals(s.id());
            hasRegnskap |= "E2".equals(s.id());
        }
        if (c.ai != null) evidence.addAll(c.ai.quotedEvidence());

        List<FactorResult> factors = new ArrayList<>();
        for (FactorResult f : c.score.factors()) {
            boolean known = f.score() != null;
            if (known && f.evidenceIds().isEmpty() && hasBrreg && BRREG_FACTORS.contains(f.key())) {
                f = withEvidence(f, "E1");
            } else if (known && f.evidenceIds().isEmpty() && hasRegnskap && REGNSKAP_FACTORS.contains(f.key())) {
                f = withEvidence(f, "E2");
            }
            factors.add(f);
        }
        RegnskapYear latest = c.regnskap == null || c.regnskap.isEmpty() ? null : c.regnskap.get(0);
        return new CandidateResult(c.orgNumber, c.name, c.naceCode, c.naceDescription, c.employees,
                latest == null ? null : latest.revenue(), latest == null ? null : latest.year(), c.city,
                c.website, c.score.score(), c.score.priority(), c.score.coverage(), c.score.disqualified(),
                c.score.disqualifiedReason(), c.ai != null ? c.ai.ai().summary() : null, factors, evidence,
                c.ai != null ? c.ai.ai() : null, c.roles);
    }

    private static FactorResult withEvidence(FactorResult f, String id) {
        return new FactorResult(f.key(), f.label(), f.weight(), f.status(), f.score(), f.explanation(), List.of(id));
    }

    private static String money(java.math.BigDecimal v) {
        return v == null ? "-" : v.setScale(0, RoundingMode.HALF_UP).toPlainString();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
