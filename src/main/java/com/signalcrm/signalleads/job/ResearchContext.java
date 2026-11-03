package com.signalcrm.signalleads.job;

import com.signalcrm.signalleads.api.ScoringConfig;
import com.signalcrm.signalleads.api.SellerContext;
import com.signalcrm.signalleads.api.TargetProfile;
import com.signalcrm.signalleads.config.UsageTracker;
import com.signalcrm.signalleads.scoring.ReferenceProfile;
import com.signalcrm.signalleads.scoring.ScoringEngine;

import java.util.List;

/** Det som er felles for alle bedrifter i én jobb: kriteriene fra SignalCRM, ferdig tolket. */
record ResearchContext(
        SellerContext seller,
        TargetProfile profile,
        ScoringConfig scoring,
        ScoringEngine.IndustryCriteria industry,
        ReferenceProfile references,
        List<String> websiteKeywords,
        int pagesPerSite,
        String aiModel,
        UsageTracker usage
) {}
