package com.signalcrm.signalleads.config;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Enkel minnecache med levetid og maks størrelse - brukes KUN for offentlige bedriftsdata
 * (Brønnøysund, nettsider), som er like for alle SignalCRM-kunder. Aldri for noe kundespesifikt.
 * Tomme oppslag caches også (Optional.empty), så en bedrift uten nettsted ikke slås opp på nytt hver gang.
 */
public class TtlCache<K, V> {

    private record Entry<V>(Optional<V> value, long expiresAt) {}

    private final Map<K, Entry<V>> map = new ConcurrentHashMap<>();
    private final long ttlMillis;
    private final int maxSize;

    public TtlCache(Duration ttl, int maxSize) {
        this.ttlMillis = ttl.toMillis();
        this.maxSize = maxSize;
    }

    public Optional<V> get(K key, Supplier<Optional<V>> loader) {
        long now = System.currentTimeMillis();
        Entry<V> e = map.get(key);
        if (e != null && e.expiresAt > now) return e.value;
        Optional<V> value = loader.get();
        if (map.size() >= maxSize) evict(now);
        map.put(key, new Entry<>(value, now + ttlMillis));
        return value;
    }

    private void evict(long now) {
        map.entrySet().removeIf(en -> en.getValue().expiresAt <= now);
        // Fortsatt full: fjern vilkårlige innslag til det er plass (enkelt, og godt nok for en cache).
        Iterator<K> it = map.keySet().iterator();
        while (map.size() >= maxSize && it.hasNext()) {
            it.next();
            it.remove();
        }
    }
}
