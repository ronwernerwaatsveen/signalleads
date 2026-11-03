package com.signalcrm.signalleads.api;

/** Forbruk for én jobb - SignalCRM bruker dette til å telle mot kundens tak. */
public record Usage(int aiCalls, long inputTokens, long outputTokens, int websitesFetched, int brregCalls) {}
