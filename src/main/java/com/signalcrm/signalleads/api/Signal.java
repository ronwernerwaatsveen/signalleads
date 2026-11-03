package com.signalcrm.signalleads.api;

/**
 * Et kjøpssignal eller mulig behov fra KI-analysen.
 *
 * @param evidenceId kilden (se {@link Evidence}); null betyr at det er en hypotese uten dokumentasjon
 * @param strength   1-3 (svakt-sterkt), kun for kjøpssignaler
 */
public record Signal(String text, String evidenceId, Integer strength, boolean hypothesis) {}
