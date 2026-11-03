package com.signalcrm.signalleads.api;

import java.util.List;

/**
 * Én delscore.
 *
 * @param status POSITIVE / NEUTRAL / NEGATIVE: vurdert ut fra data. UNKNOWN: kriteriet gjelder, men
 *               data mangler - teller som 0 i totalen og trekker ned dekningsgraden.
 *               NOT_APPLICABLE: kriteriet er ikke satt i profilen - teller ikke i det hele tatt.
 * @param score  0-100, null ved UNKNOWN/NOT_APPLICABLE
 */
public record FactorResult(String key, String label, double weight, String status, Integer score,
                           String explanation, List<String> evidenceIds) {}
