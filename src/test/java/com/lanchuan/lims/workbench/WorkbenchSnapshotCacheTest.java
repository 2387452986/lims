package com.lanchuan.lims.workbench;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class WorkbenchSnapshotCacheTest {
    private final WorkbenchSnapshotCache.Key key = new WorkbenchSnapshotCache.Key(
            UUID.randomUUID(), 3, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            "ORGANIZATION", List.of("WORKBENCH_READ"), "ALL",
            LocalDate.of(2000, 1, 1), LocalDate.of(2100, 1, 1), "");

    @Test
    void returnsSnapshotUntilTtlExpires() {
        AtomicLong clock = new AtomicLong();
        AtomicInteger loads = new AtomicInteger();
        WorkbenchSnapshotCache cache = new WorkbenchSnapshotCache(Duration.ofSeconds(1), 8, clock::get);

        assertThat(cache.get(key, Integer.class, loads::incrementAndGet)).isEqualTo(1);
        clock.set(Duration.ofMillis(999).toNanos());
        assertThat(cache.get(key, Integer.class, loads::incrementAndGet)).isEqualTo(1);
        clock.set(Duration.ofSeconds(1).toNanos());
        assertThat(cache.get(key, Integer.class, loads::incrementAndGet)).isEqualTo(2);
    }

    @Test
    void coalescesConcurrentMissesForTheSameUserAndFilter() throws Exception {
        WorkbenchSnapshotCache cache = new WorkbenchSnapshotCache(Duration.ofSeconds(1), 8, System::nanoTime);
        AtomicInteger loads = new AtomicInteger();
        int workers = 24;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(workers)) {
            List<Future<Integer>> futures = IntStream.range(0, workers).mapToObj(index -> executor.submit(() -> {
                ready.countDown();
                start.await();
                return cache.get(key, Integer.class, () -> {
                    loads.incrementAndGet();
                    try {
                        Thread.sleep(40);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                    return 17;
                });
            })).toList();
            ready.await();
            start.countDown();
            for (Future<Integer> future : futures) assertThat(future.get()).isEqualTo(17);
        }
        assertThat(loads).hasValue(1);
    }
}
