package com.signalcrm.signalleads.scoring;

import java.util.List;

/** Bransje og størrelse for kundens eksisterende kunder (fra org.nr. i ReferenceSet, slått opp i Brreg). */
public record ReferenceProfile(List<Ref> refs) {
    public record Ref(String orgNumber, String naceCode, Integer employees) {}

    public static ReferenceProfile empty() {
        return new ReferenceProfile(List.of());
    }
}
