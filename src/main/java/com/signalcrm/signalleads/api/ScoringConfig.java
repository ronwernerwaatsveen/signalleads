package com.signalcrm.signalleads.api;

import java.util.Map;

/**
 * Vekting og grenser, bestemt av SignalCRM. Nøklene i {@code weights} er faktornøklene i
 * {@link FactorResult} (industry, size, revenue, financialHealth, growth, lookalike, needsFit,
 * buyingSignals). Mangler vekter, vektes alle anvendelige faktorer likt.
 *
 * @param thresholdA minste score for prioritet A (standard 75)
 * @param thresholdB minste score for prioritet B (standard 50)
 */
public record ScoringConfig(Map<String, Double> weights, Integer thresholdA, Integer thresholdB) {}
