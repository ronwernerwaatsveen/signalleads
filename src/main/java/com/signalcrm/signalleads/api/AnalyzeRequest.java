package com.signalcrm.signalleads.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Analyse av én bedrift - erstatter "Score mot kundeprofil" i SignalCRM (lead og mulighet).
 *
 * @param tenantRef       SignalCRM-kundens coid - kun til sporing og kontroll ved henting, ingen innstillinger
 * @param criteriaVersion SignalCRMs versjon/hash av profil og vekter - returneres uendret med resultatet
 * @param aiModel         valgfritt modellnavn; tom = tjenestens standard
 */
public record AnalyzeRequest(
        @NotBlank String tenantRef,
        String criteriaVersion,
        @Valid SellerContext seller,
        @NotNull @Valid TargetProfile profile,
        ScoringConfig scoring,
        ReferenceSet references,
        @NotNull @Valid CompanyInput company,
        List<ContextItem> context,
        Budget budget,
        String aiModel
) {}
