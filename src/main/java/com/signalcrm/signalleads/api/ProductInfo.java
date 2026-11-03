package com.signalcrm.signalleads.api;

/** Én produktbeskrivelse fra SignalCRM (ProductDescription). Teksten kortes ned før den går til KI. */
public record ProductInfo(String name, String summary, String description) {}
