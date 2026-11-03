package com.signalcrm.signalleads.api;

/**
 * Ekstra grunnlag SignalCRM allerede har om bedriften, f.eks. teksten i en Doffin-kunngjøring
 * (mulighet) eller et besøk på eget nettsted. Behandles som kilde på lik linje med nettsider.
 */
public record ContextItem(String type, String title, String text, String url, String date) {}
