package com.signalcrm.signalleads.ai;

/**
 * Én kilde som legges frem for KI-en med en id (E1, E2 ...). KI-en må vise til id-en og sitere
 * ordrett fra teksten for at et kjøpssignal skal godtas - se {@link LeadAnalyzer#validate}.
 */
public record SourceChunk(String id, String source, String url, String title, String text) {}
