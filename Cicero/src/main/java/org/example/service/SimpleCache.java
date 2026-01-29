package org.example.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * A simple thread-safe cache with time-based expiration and optional automatic cleanup.
 * @param <K> Key type
 * @param <V> Value type
 */
public class SimpleCache<K, V> {

    private final Map<K, CacheEntry<V>> cache = new ConcurrentHashMap<>();
    private final long ttlMillis;

    // Shared scheduler for all cache instances to minimize resource usage
    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "SimpleCache-Cleanup");
        t.setDaemon(true); // Allow JVM to exit even if this thread is running
        return t;
    });

    /**
     * Creates a cache with the specified TTL.
     * Automatic cleanup is scheduled at an interval equal to the TTL.
     * @param ttlMillis Time to live in milliseconds
     */
    public SimpleCache(long ttlMillis) {
        this(ttlMillis, ttlMillis);
    }

    /**
     * Creates a cache with the specified TTL and cleanup interval.
     * @param ttlMillis Time to live in milliseconds
     * @param cleanupIntervalMillis Interval between cleanup runs in milliseconds. If <= 0, auto-cleanup is disabled.
     */
    public SimpleCache(long ttlMillis, long cleanupIntervalMillis) {
        this.ttlMillis = ttlMillis;
        if (cleanupIntervalMillis > 0) {
            scheduler.scheduleAtFixedRate(this::prune, cleanupIntervalMillis, cleanupIntervalMillis, TimeUnit.MILLISECONDS);
        }
    }

    public void put(K key, V value) {
        cache.put(key, new CacheEntry<>(value, System.currentTimeMillis() + ttlMillis));
    }

    public V get(K key) {
        CacheEntry<V> entry = cache.get(key);
        if (entry == null) {
            return null;
        }
        if (System.currentTimeMillis() > entry.expiryTime) {
            cache.remove(key);
            return null;
        }
        return entry.value;
    }

    public void clear() {
        cache.clear();
    }

    /**
     * Removes all expired entries from the cache.
     */
    public void prune() {
        long now = System.currentTimeMillis();
        cache.entrySet().removeIf(entry -> now > entry.getValue().expiryTime);
    }

    public int size() {
        return cache.size();
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