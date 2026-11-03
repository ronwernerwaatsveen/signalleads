package com.signalcrm.signalleads.scoring;

import com.signalcrm.signalleads.api.FactorResult;
import com.signalcrm.signalleads.api.ScoringConfig;
import com.signalcrm.signalleads.api.Signal;
import com.signalcrm.signalleads.api.TargetProfile;
import com.signalcrm.signalleads.brreg.NaceResolver;
import com.signalcrm.signalleads.brreg.RegnskapYear;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Regner ut scoren. Fakta fra registre (bransje, størrelse, omsetning, økonomi, vekst, likhet med
 * egne kunder) regnes deterministisk her - KI-en bidrar bare med to faktorer (behovsmatch og
 * dokumenterte kjøpssignaler), og bare med det som har gyldig kilde.
 *
 * Totalen: sum(vekt x score) / sum(vekt) over faktorer som gjelder. En faktor som gjelder men
 * mangler data (UNKNOWN) teller som 0 - manglende informasjon kan aldri gi høy score - og trekker
 * ned dekningsgraden. En faktor uten kriterium i profilen (NOT_APPLICABLE) eller uten vekt teller
 * ikke. Prioritet A krever i tillegg minst 50 % dekning; ellers nedgraderes den til B.
 */
@Component
public class ScoringEngine {

    public static final String INDUSTRY = "industry", SIZE = "size", REVENUE = "revenue", HEALTH = "financialHealth",
            GROWTH = "growth", LOOKALIKE = "lookalike", NEEDS = "needsFit", SIGNALS = "buyingSignals";
    public static final List<String> ALL = List.of(INDUSTRY, SIZE, REVENUE, HEALTH, GROWTH, LOOKALIKE, NEEDS, SIGNALS);
    private static final int MIN_REFS = 3;

    public record IndustryCriteria(List<String> prefixes, List<String> texts) {
        boolean isSet() {
            return !prefixes.isEmpty() || !texts.isEmpty();
        }
    }

    public ScoreResult score(TargetProfile p, ScoringConfig cfg, IndustryCriteria industry,
                             ReferenceProfile refs, CompanyFacts f) {
        List<FactorResult> factors = new ArrayList<>();
        factors.add(industry(weight(cfg, INDUSTRY), industry, f));
        factors.add(size(weight(cfg, SIZE), p, f));
        factors.add(revenue(weight(cfg, REVENUE), p, f));
        factors.add(health(weight(cfg, HEALTH), f));
        factors.add(growth(weight(cfg, GROWTH), f));
        factors.add(lookalike(weight(cfg, LOOKALIKE), refs, f));
        factors.add(needs(weight(cfg, NEEDS), f));
        factors.add(signals(weight(cfg, SIGNALS), f));

        double total = 0, known = 0, sum = 0;
        for (FactorResult fr : factors) {
            if ("NOT_APPLICABLE".equals(fr.status())) continue;
            total += fr.weight();
            if (!"UNKNOWN".equals(fr.status())) {
                known += fr.weight();
                sum += fr.weight() * fr.score();
            }
        }
        int score = total > 0 ? (int) Math.round(sum / total) : 0;
        double coverage = total > 0 ? Math.round(known / total * 100) / 100.0 : 0;

        if (f.inactiveReason() != null) {
            return new ScoreResult(0, "C", coverage, true, f.inactiveReason(), factors);
        }
        int a = cfg != null && cfg.thresholdA() != null ? cfg.thresholdA() : 75;
        int b = cfg != null && cfg.thresholdB() != null ? cfg.thresholdB() : 50;
        String priority = score >= a ? "A" : score >= b ? "B" : "C";
        if ("A".equals(priority) && coverage < 0.5) priority = "B";
        return new ScoreResult(score, priority, coverage, false, null, factors);
    }

    /** Vekt fra SignalCRM. Uten vektkart vektes alle likt; med vektkart er en manglende nøkkel = 0. */
    static double weight(ScoringConfig cfg, String key) {
        if (cfg == null || cfg.weights() == null || cfg.weights().isEmpty()) return 1.0;
        Double w = cfg.weights().get(key);
        return w == null || w < 0 ? 0 : w;
    }

    private FactorResult industry(double w, IndustryCriteria c, CompanyFacts f) {
        String label = "Bransje";
        if (w == 0 || !c.isSet()) return na(INDUSTRY, label, w, "Ingen bransje er satt i kundeprofilen.");
        if (f.naceCode() == null && f.naceDescription() == null) return unknown(INDUSTRY, label, w, "Bransjen er ikke kjent.");
        if (NaceResolver.codeMatches(f.naceCode(), c.prefixes())) {
            return known(INDUSTRY, label, w, 100, "Næringskode " + f.naceCode() + " er innenfor målbransjene.");
        }
        String desc = f.naceDescription() == null ? "" : f.naceDescription().toLowerCase(Locale.ROOT);
        for (String t : c.texts()) {
            String tl = t.toLowerCase(Locale.ROOT);
            if (!desc.isBlank() && (desc.contains(tl) || tl.contains(desc))) {
                return known(INDUSTRY, label, w, 100, "Bransjen «" + f.naceDescription() + "» samsvarer med «" + t + "».");
            }
        }
        return known(INDUSTRY, label, w, 0, "Bransjen «" + orDash(f.naceDescription()) + "» er utenfor målbransjene.");
    }

    private FactorResult size(double w, TargetProfile p, CompanyFacts f) {
        String label = "Antall ansatte";
        if (w == 0 || (p.minEmployees() == null && p.maxEmployees() == null))
            return na(SIZE, label, w, "Ingen størrelse er satt i kundeprofilen.");
        if (f.employees() == null) return unknown(SIZE, label, w, "Antall ansatte er ikke registrert.");
        return rangeFactor(SIZE, label, w, f.employees(), p.minEmployees(), p.maxEmployees(), f.employees() + " ansatte");
    }

    private FactorResult revenue(double w, TargetProfile p, CompanyFacts f) {
        String label = "Omsetning";
        if (w == 0 || (p.minRevenue() == null && p.maxRevenue() == null))
            return na(REVENUE, label, w, "Ingen omsetning er satt i kundeprofilen.");
        RegnskapYear latest = latest(f);
        if (latest == null) return unknown(REVENUE, label, w, "Ingen regnskapstall tilgjengelig.");
        return rangeFactor(REVENUE, label, w, latest.revenue().doubleValue(),
                p.minRevenue() == null ? null : p.minRevenue().doubleValue(),
                p.maxRevenue() == null ? null : p.maxRevenue().doubleValue(),
                "Omsetning " + latest.year() + ": " + latest.revenue().setScale(0, RoundingMode.HALF_UP).toPlainString());
    }

    private FactorResult health(double w, CompanyFacts f) {
        String label = "Økonomisk status";
        if (w == 0) return na(HEALTH, label, w, null);
        if (f.inactiveReason() != null) return known(HEALTH, label, w, 0, f.inactiveReason());
        RegnskapYear latest = latest(f);
        if (latest == null) return unknown(HEALTH, label, w, "Ingen regnskapstall tilgjengelig.");
        if (latest.equity() != null && latest.equity().signum() < 0)
            return known(HEALTH, label, w, 15, "Negativ egenkapital i " + latest.year() + ".");
        if (latest.resultBeforeTax() != null && latest.resultBeforeTax().signum() < 0)
            return known(HEALTH, label, w, 50, "Underskudd før skatt i " + latest.year() + ".");
        return known(HEALTH, label, w, 100, "Positiv egenkapital og resultat i " + latest.year() + ".");
    }

    private FactorResult growth(double w, CompanyFacts f) {
        String label = "Vekst";
        if (w == 0) return na(GROWTH, label, w, null);
        List<RegnskapYear> r = f.regnskap();
        if (r == null || r.size() < 2 || r.get(1).revenue().signum() == 0)
            return unknown(GROWTH, label, w, "Trenger regnskap for minst to år.");
        BigDecimal now = r.get(0).revenue(), before = r.get(1).revenue();
        double g = now.subtract(before).doubleValue() / Math.abs(before.doubleValue());
        int pct = (int) Math.round(g * 100);
        int s = g >= 0.15 ? 100 : g >= 0.05 ? 80 : g >= -0.05 ? 55 : g >= -0.15 ? 30 : 10;
        return known(GROWTH, label, w, s, "Omsetningsendring " + r.get(1).year() + "-" + r.get(0).year() + ": "
                + (pct > 0 ? "+" : "") + pct + " %.");
    }

    private FactorResult lookalike(double w, ReferenceProfile refs, CompanyFacts f) {
        String label = "Likhet med egne kunder";
        if (w == 0) return na(LOOKALIKE, label, w, null);
        if (refs == null || refs.refs().size() < MIN_REFS)
            return na(LOOKALIKE, label, w, "For få kjente kunder (minst " + MIN_REFS + ") til å sammenligne.");
        if (f.naceCode() == null) return unknown(LOOKALIKE, label, w, "Bransjekoden er ikke kjent.");
        int best = 0, sameDivision = 0;
        for (ReferenceProfile.Ref ref : refs.refs()) {
            int level = naceSimilarity(f.naceCode(), ref.naceCode());
            best = Math.max(best, level);
            if (level >= 55) sameDivision++;
        }
        int sizeSim = -1, withSize = 0, nearSize = 0;
        if (f.employees() != null) {
            for (ReferenceProfile.Ref ref : refs.refs()) {
                if (ref.employees() == null) continue;
                withSize++;
                double lo = f.employees() / 2.5, hi = Math.max(1, f.employees()) * 2.5;
                if (ref.employees() >= lo && ref.employees() <= hi) nearSize++;
            }
            if (withSize > 0) sizeSim = (int) Math.min(100, Math.round(200.0 * nearSize / withSize));
        }
        int s = sizeSim < 0 ? best : (int) Math.round(best * 0.65 + sizeSim * 0.35);
        String why = sameDivision + " av " + refs.refs().size() + " kunder er i samme bransjegruppe"
                + (sizeSim < 0 ? "." : ", " + nearSize + " av " + withSize + " har lignende størrelse.");
        return known(LOOKALIKE, label, w, s, why);
    }

    /** 100 = samme 5-sifrede kode, 85 = samme undergruppe (43.21), 70 = gruppe (43.2), 55 = divisjon (43). */
    static int naceSimilarity(String a, String b) {
        if (a == null || b == null) return 0;
        if (a.equals(b)) return 100;
        if (a.length() >= 5 && b.length() >= 5 && a.substring(0, 5).equals(b.substring(0, 5))) return 85;
        if (a.length() >= 4 && b.length() >= 4 && a.substring(0, 4).equals(b.substring(0, 4))) return 70;
        if (a.length() >= 2 && b.length() >= 2 && a.substring(0, 2).equals(b.substring(0, 2))) return 55;
        return 0;
    }

    private FactorResult needs(double w, CompanyFacts f) {
        String label = "Behov for produktene";
        if (w == 0) return na(NEEDS, label, w, null);
        if (!f.aiGloballyAvailable()) return na(NEEDS, label, w, "KI er ikke slått på.");
        if (f.ai() == null) return unknown(NEEDS, label, w, f.aiNote() != null ? f.aiNote() : "Ikke analysert med KI.");
        if (f.ai().needsFitScore() == null)
            return unknown(NEEDS, label, w, "KI fant ingen kilde som beskriver virksomheten godt nok.");
        return new FactorResult(NEEDS, label, w, status(f.ai().needsFitScore()), f.ai().needsFitScore(),
                f.ai().needsFitReason(), f.ai().needsFitEvidenceIds());
    }

    private FactorResult signals(double w, CompanyFacts f) {
        String label = "Dokumenterte kjøpssignaler";
        if (w == 0) return na(SIGNALS, label, w, null);
        if (!f.aiGloballyAvailable()) return na(SIGNALS, label, w, "KI er ikke slått på.");
        if (f.ai() == null) return unknown(SIGNALS, label, w, f.aiNote() != null ? f.aiNote() : "Ikke analysert med KI.");
        List<Signal> sig = f.ai().ai().buyingSignals();
        if (sig.isEmpty()) {
            if (f.websiteTextChars() == null || f.websiteTextChars() == 0)
                return unknown(SIGNALS, label, w, "Ingen nettside eller annen kilde å lete i.");
            return new FactorResult(SIGNALS, label, w, "NEUTRAL", 0, "Ingen dokumenterte kjøpssignaler funnet i kildene.", List.of());
        }
        int sum = sig.stream().mapToInt(s -> s.strength() == null ? 1 : s.strength()).sum();
        int s = Math.min(100, sum * 25);
        return new FactorResult(SIGNALS, label, w, status(s), s, sig.size() + " dokumenterte signal(er).",
                sig.stream().map(Signal::evidenceId).toList());
    }

    private FactorResult rangeFactor(String key, String label, double w, double value, Number min, Number max, String what) {
        double lo = min == null ? Double.NEGATIVE_INFINITY : min.doubleValue();
        double hi = max == null ? Double.POSITIVE_INFINITY : max.doubleValue();
        if (value >= lo && value <= hi) return known(key, label, w, 100, what + " - innenfor målet.");
        double ratio = value < lo ? (lo <= 0 ? 0 : value / lo) : (value <= 0 ? 0 : hi / value);
        int s = (int) Math.round(Math.max(0, Math.min(1, ratio)) * 80);
        return known(key, label, w, s, what + " - utenfor målet (" + rangeText(min, max) + ").");
    }

    private static RegnskapYear latest(CompanyFacts f) {
        return f.regnskap() == null || f.regnskap().isEmpty() ? null : f.regnskap().get(0);
    }

    private static String status(int s) {
        return s >= 65 ? "POSITIVE" : s >= 35 ? "NEUTRAL" : "NEGATIVE";
    }

    private static FactorResult known(String key, String label, double w, int s, String why) {
        return new FactorResult(key, label, w, status(s), s, why, List.of());
    }

    private static FactorResult unknown(String key, String label, double w, String why) {
        return new FactorResult(key, label, w, "UNKNOWN", null, why, List.of());
    }

    private static FactorResult na(String key, String label, double w, String why) {
        return new FactorResult(key, label, w, "NOT_APPLICABLE", null, why != null ? why : "Ikke vektet.", List.of());
    }

    private static String rangeText(Number min, Number max) {
        if (min != null && max != null) return fmt(min) + "-" + fmt(max);
        return min != null ? "minst " + fmt(min) : "maks " + fmt(max);
    }

    private static String fmt(Number n) {
        return BigDecimal.valueOf(n.doubleValue()).setScale(0, RoundingMode.HALF_UP).toPlainString();
    }

    private static String orDash(String s) {
        return s == null ? "-" : s;
    }

}
