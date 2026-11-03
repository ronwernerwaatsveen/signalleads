package com.signalcrm.signalleads.job;

import com.signalcrm.signalleads.ai.AnalysisOutcome;
import com.signalcrm.signalleads.api.ContextItem;
import com.signalcrm.signalleads.api.PublicRole;
import com.signalcrm.signalleads.brreg.Enhet;
import com.signalcrm.signalleads.brreg.RegnskapYear;
import com.signalcrm.signalleads.scoring.ScoreResult;
import com.signalcrm.signalleads.web.WebPage;

import java.util.List;

/** Arbeidsdata for én bedrift mens en jobb pågår - bygges gradvis (Brreg -> regnskap -> nettside -> KI). */
class Candidate {
    String orgNumber;
    String name;
    String website;
    String naceCode;
    String naceDescription;
    Integer employees;
    String city;
    Enhet enhet;
    List<RegnskapYear> regnskap;
    List<WebPage> pages;
    List<ContextItem> context = List.of();
    AnalysisOutcome ai;
    String aiNote;
    List<PublicRole> roles = List.of();
    ScoreResult score;

    static Candidate of(Enhet e) {
        Candidate c = new Candidate();
        c.enhet = e;
        c.orgNumber = e.orgNumber();
        c.name = e.name();
        c.website = e.website();
        c.naceCode = e.naceCode();
        c.naceDescription = e.naceDescription();
        c.employees = e.employees();
        c.city = e.city();
        return c;
    }

    int currentScore() {
        return score == null ? 0 : score.score();
    }
}
