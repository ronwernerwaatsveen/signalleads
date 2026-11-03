package com.signalcrm.signalleads.scoring;

import com.signalcrm.signalleads.api.FactorResult;

import java.util.List;

public record ScoreResult(int score, String priority, double coverage, boolean disqualified,
                          String disqualifiedReason, List<FactorResult> factors) {}
