package com.signalcrm.signalleads.api;

import java.util.List;

/**
 * KI-delen av analysen, etter at påstander uten gyldig kilde er fjernet (se rejectedClaims).
 */
public record AiAnalysis(
        String summary,
        List<Signal> buyingSignals,
        List<Signal> painPoints,
        List<String> decisionMakerRoles,
        String salesApproach,
        List<String> recommendedProducts,
        List<String> assumptions,
        List<String> missingInformation,
        Double confidence,
        int rejectedClaims,
        String model
) {}
