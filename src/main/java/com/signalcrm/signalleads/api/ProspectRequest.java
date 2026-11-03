package com.signalcrm.signalleads.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Prospektering: finn og ranger nye bedrifter som passer profilen.
 *
 * @param count               ønsket antall leads i resultatet
 * @param excludeOrgNumbers   bedrifter som allerede finnes i kundens CRM og skal holdes utenfor
 */
public record ProspectRequest(
        @NotBlank String tenantRef,
        String criteriaVersion,
        @Valid SellerContext seller,
        @NotNull @Valid TargetProfile profile,
        ScoringConfig scoring,
        ReferenceSet references,
        List<String> excludeOrgNumbers,
        @Min(1) @Max(500) int count,
        Budget budget,
        String aiModel
) {}
