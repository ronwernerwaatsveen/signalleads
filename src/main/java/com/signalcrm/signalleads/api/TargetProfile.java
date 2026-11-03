package com.signalcrm.signalleads.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * Kundeprofilen (ICP) slik SignalCRM har den - sendes i hvert kall, lagres aldri her.
 * Et kriterium som ikke er satt (null/tom) teller ikke for eller mot en bedrift.
 *
 * @param industries  bransjenavn (SN2007-seksjon, -divisjon eller detaljert beskrivelse) - tolkes til NACE-koder
 * @param naceCodes   eksplisitte NACE-koder/prefikser, f.eks. "43.2" eller "71"
 * @param locations   kommunenavn (Norge) - kun brukt til å finne kandidater i prospektering
 * @param websiteKeywords ekstra ord som peker ut relevante undersider på bedriftenes nettsted
 */
public record TargetProfile(
        String name,
        Integer minEmployees,
        Integer maxEmployees,
        BigDecimal minRevenue,
        BigDecimal maxRevenue,
        List<String> industries,
        List<String> naceCodes,
        List<String> locations,
        String country,
        List<String> certifications,
        Boolean multipleLocations,
        Boolean multipleCountries,
        List<String> relevantRoles,
        List<String> websiteKeywords
) {}
