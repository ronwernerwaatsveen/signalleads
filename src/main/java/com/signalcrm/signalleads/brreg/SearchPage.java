package com.signalcrm.signalleads.brreg;

import java.util.List;

public record SearchPage(List<Enhet> enheter, int page, int totalPages, long totalElements) {}
