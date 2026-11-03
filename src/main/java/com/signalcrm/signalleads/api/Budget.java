package com.signalcrm.signalleads.api;

/**
 * Tak for én jobb, satt av SignalCRM (typisk ut fra abonnementet). Jobben stopper når et tak er nådd
 * og returnerer det den har.
 *
 * @param maxAiCalls     maks antall KI-analyser
 * @param maxWebsites    maks antall nettsteder som hentes
 * @param maxCandidates  maks antall kandidater som hentes fra Brønnøysund (prospektering)
 * @param maxPagesPerSite maks antall sider per nettsted
 */
public record Budget(Integer maxAiCalls, Integer maxWebsites, Integer maxCandidates, Integer maxPagesPerSite) {}
