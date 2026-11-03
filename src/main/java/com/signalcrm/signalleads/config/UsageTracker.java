package com.signalcrm.signalleads.config;

import com.signalcrm.signalleads.api.Usage;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Teller forbruket i én jobb (trådsikkert - en jobb henter flere bedrifter parallelt). */
public class UsageTracker {
    public final AtomicInteger aiCalls = new AtomicInteger();
    public final AtomicLong inputTokens = new AtomicLong();
    public final AtomicLong outputTokens = new AtomicLong();
    public final AtomicInteger websites = new AtomicInteger();
    public final AtomicInteger brregCalls = new AtomicInteger();

    public Usage snapshot() {
        return new Usage(aiCalls.get(), inputTokens.get(), outputTokens.get(), websites.get(), brregCalls.get());
    }
}
