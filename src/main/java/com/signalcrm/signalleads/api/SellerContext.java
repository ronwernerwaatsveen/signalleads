package com.signalcrm.signalleads.api;

import java.util.List;

/** Hvem som selger, og hva - SignalCRM-kundens egne produktbeskrivelser. Språk for KI-tekstene ("nb" som standard). */
public record SellerContext(String sellerName, String language, List<ProductInfo> products) {}
