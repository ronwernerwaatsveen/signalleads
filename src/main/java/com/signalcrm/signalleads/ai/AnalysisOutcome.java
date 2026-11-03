package com.signalcrm.signalleads.ai;

import com.signalcrm.signalleads.api.AiAnalysis;
import com.signalcrm.signalleads.api.Evidence;

import java.util.List;

/**
 * Validert KI-resultat for én bedrift.
 *
 * @param needsFitScore    0-100 hvor godt produktene passer virksomheten, eller null hvis KI-en ikke
 *                         kunne begrunne det med en gyldig kilde
 * @param quotedEvidence   sitatene som ble godtatt, som kilder resultatet viser til
 */
public record AnalysisOutcome(AiAnalysis ai, Integer needsFitScore, String needsFitReason,
                              List<String> needsFitEvidenceIds, List<Evidence> quotedEvidence,
                              long inputTokens, long outputTokens) {}
