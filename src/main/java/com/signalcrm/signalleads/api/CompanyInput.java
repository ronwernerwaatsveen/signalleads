package com.signalcrm.signalleads.api;

/** Bedriften som skal analyseres. Org.nr. er best (norske bedrifter); ellers brukes navn, nettsted og bransje slik de er. */
public record CompanyInput(String orgNumber, String name, String website, String country, String industry, String municipality) {}
