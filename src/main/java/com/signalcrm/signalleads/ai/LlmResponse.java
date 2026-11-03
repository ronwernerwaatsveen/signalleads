package com.signalcrm.signalleads.ai;

public record LlmResponse(String text, long inputTokens, long outputTokens, String model) {}
