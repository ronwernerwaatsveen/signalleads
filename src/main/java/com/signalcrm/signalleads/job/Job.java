package com.signalcrm.signalleads.job;

import com.signalcrm.signalleads.api.CandidateResult;
import com.signalcrm.signalleads.api.JobView;
import com.signalcrm.signalleads.config.UsageTracker;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;

/** En jobb i minnet. Feltene skrives av jobbtråden og leses av API-trådene, derav volatile. */
class Job {
    final String id;
    final String type;
    final String tenantRef;
    final String criteriaVersion;
    final Instant createdAt = Instant.now();
    final UsageTracker usage = new UsageTracker();
    final List<String> warnings = new CopyOnWriteArrayList<>();

    volatile String status = "QUEUED";
    volatile int progressPct;
    volatile String progressText = "I kø";
    volatile Instant finishedAt;
    volatile String error;
    volatile List<CandidateResult> results = List.of();
    volatile boolean cancelled;
    volatile Future<?> future;

    Job(String id, String type, String tenantRef, String criteriaVersion) {
        this.id = id;
        this.type = type;
        this.tenantRef = tenantRef;
        this.criteriaVersion = criteriaVersion;
    }

    void progress(int pct, String text) {
        this.progressPct = Math.max(this.progressPct, Math.min(99, pct));
        this.progressText = text;
    }

    boolean finished() {
        return finishedAt != null;
    }

    JobView view() {
        boolean done = "DONE".equals(status);
        return new JobView(id, type, status, done ? 100 : progressPct, progressText, createdAt, finishedAt, error,
                criteriaVersion, done || "CANCELLED".equals(status) ? results : null, usage.snapshot(), List.copyOf(warnings));
    }
}
