package com.lanchuan.lims.workbench;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

@Component
public final class WorkbenchSnapshotCache {
    static final Duration DEFAULT_TTL = Duration.ofSeconds(1);
    static final int DEFAULT_MAX_ENTRIES = 128;
    private static final int LOCK_COUNT = 32;

    record Key(UUID userId, long sessionVersion, UUID organizationId, UUID departmentId, UUID personId,
               String dataScope, List<String> permissions, String status, LocalDate from, LocalDate to,
               String query) {
        Key {
            permissions = List.copyOf(permissions);
        }
    }

    private record Entry(long createdAt, Object value) {}

    private final long ttlNanos;
    private final int maxEntries;
    private final LongSupplier nanoTime;
    private final ConcurrentHashMap<Key, Entry> entries = new ConcurrentHashMap<>();
    private final Object[] locks = new Object[LOCK_COUNT];

    public WorkbenchSnapshotCache() {
        this(DEFAULT_TTL, DEFAULT_MAX_ENTRIES, System::nanoTime);
    }

    WorkbenchSnapshotCache(Duration ttl, int maxEntries, LongSupplier nanoTime) {
        this.ttlNanos = ttl.toNanos();
        this.maxEntries = maxEntries;
        this.nanoTime = nanoTime;
        for (int index = 0; index < locks.length; index++) locks[index] = new Object();
    }

    <T> T get(Key key, Class<T> type, Supplier<T> loader) {
        long now = nanoTime.getAsLong();
        Entry current = entries.get(key);
        if (fresh(current, now)) return type.cast(current.value());

        Object lock = locks[Math.floorMod(key.hashCode(), locks.length)];
        synchronized (lock) {
            now = nanoTime.getAsLong();
            current = entries.get(key);
            if (fresh(current, now)) return type.cast(current.value());
            T value = loader.get();
            long createdAt = nanoTime.getAsLong();
            entries.put(key, new Entry(createdAt, value));
            prune(createdAt);
            return value;
        }
    }

    private boolean fresh(Entry entry, long now) {
        return entry != null && now - entry.createdAt() < ttlNanos;
    }

    private void prune(long now) {
        if (entries.size() <= maxEntries) return;
        entries.entrySet().removeIf(item -> !fresh(item.getValue(), now));
        while (entries.size() > maxEntries) {
            Key oldest = entries.entrySet().stream()
                    .min(java.util.Comparator.comparingLong(item -> item.getValue().createdAt()))
                    .map(java.util.Map.Entry::getKey).orElse(null);
            if (oldest == null) return;
            entries.remove(oldest);
        }
    }
}
