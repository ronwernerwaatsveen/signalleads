package com.signalcrm.signalleads.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * Resultatet for én bedrift.
 *
 * @param score    0-100, der ukjente faktorer teller som 0 (manglende data gir aldri høy score)
 * @param coverage 0-1, andelen av vektene som faktisk er vurdert ut fra data
 * @param priority A, B eller C (etter tersklene i ScoringConfig)
 */
public record CandidateResult(
        String orgNumber,
        String name,
        String industryCode,
        String industryDescription,
        Integer employees,
        BigDecimal revenue,
        Integer revenueYear,
        String city,
        String website,
        Integer score,
        String priority,
        double coverage,
        boolean disqualified,
        String disqualifiedReason,
        String summary,
        List<FactorResult> factors,
        List<Evidence> evidence,
        AiAnalysis ai,
        List<PublicRole> publicRoles
) {}
