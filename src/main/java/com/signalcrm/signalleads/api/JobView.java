package com.signalcrm.signalleads.api;

import java.time.Instant;
import java.util.List;

/**
 * Status og (når ferdig) resultat for en jobb.
 *
 * @param status QUEUED, RUNNING, DONE, FAILED eller CANCELLED
 */
public record JobView(
        String id,
        String type,
        String status,
        int progressPct,
        String progressText,
        Instant createdAt,
        Instant finishedAt,
        String error,
        String criteriaVersion,
        List<CandidateResult> results,
        Usage usage,
        List<String> warnings
) {}
