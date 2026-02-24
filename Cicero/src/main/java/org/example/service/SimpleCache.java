package org.example.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SimpleCache<K, V> {
    private final long ttlMillis;
    private final Map<K, CacheEntry<V>> cache = new ConcurrentHashMap<>();

    public SimpleCache(long ttlMillis) {
        this.ttlMillis = ttlMillis;
    }

    public void put(K key, V value) {
        cache.put(key, new CacheEntry<>(value, System.currentTimeMillis() + ttlMillis));
    }

    public V get(K key) {
        CacheEntry<V> entry = cache.get(key);
        if (entry != null) {
            if (System.currentTimeMillis() < entry.expiryTime) {
                return entry.value;
            } else {
                cache.remove(key);
            }
        }
        return null;
    }

    private static class CacheEntry<V> {
        final V value;
        final long expiryTime;

        CacheEntry(V value, long expiryTime) {
            this.value = value;
            this.expiryTime = expiryTime;
        }
    }
}