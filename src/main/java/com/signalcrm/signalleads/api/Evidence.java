package com.signalcrm.signalleads.api;

/**
 * Én kilde bak en påstand: hvor den kom fra, og det konkrete utdraget.
 *
 * @param source BRREG, REGNSKAP, WEBSITE eller CONTEXT
 */
public record Evidence(String id, String source, String url, String title, String quote, String retrievedAt) {}
