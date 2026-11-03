package com.signalcrm.signalleads.brreg;

import java.time.LocalDate;

/** Grunndata om én enhet fra Enhetsregisteret - samme felt fra oppslag og fra søk. */
public record Enhet(
        String orgNumber,
        String name,
        String naceCode,
        String naceDescription,
        Integer employees,
        boolean bankrupt,
        boolean underLiquidation,
        boolean underForcedLiquidation,
        String website,
        String city,
        String municipalityNumber,
        String parentOrgNumber,
        String sectorCode,
        String sectorDescription,
        LocalDate foundedDate
) {
    public boolean inactive() {
        return bankrupt || underLiquidation || underForcedLiquidation;
    }
}
