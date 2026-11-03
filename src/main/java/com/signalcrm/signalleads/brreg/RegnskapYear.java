package com.signalcrm.signalleads.brreg;

import java.math.BigDecimal;

/** Ett årsregnskap fra Regnskapsregisteret. */
public record RegnskapYear(int year, BigDecimal revenue, BigDecimal resultBeforeTax, BigDecimal equity, BigDecimal wageCost) {}
