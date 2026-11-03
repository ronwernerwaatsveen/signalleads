package com.signalcrm.signalleads.job;

class JobCancelledException extends RuntimeException {
    JobCancelledException() {
        super("Avbrutt");
    }
}
