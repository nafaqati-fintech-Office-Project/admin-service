package com.fintech.admin_service.service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Runs independent calls to other services side by side, so a page waits for its slowest source instead of the sum of all
 * of them. Each call keeps its own timeout (admin.downstream.timeout) and its own error handling.
 */
public final class Parallel {

    private static final AtomicInteger COUNT = new AtomicInteger();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(16, r -> {
        Thread t = new Thread(r, "admin-downstream-" + COUNT.incrementAndGet());
        t.setDaemon(true);
        return t;
    });

    private Parallel() {
    }

    public static <T> CompletableFuture<T> start(Supplier<T> call) {
        return CompletableFuture.supplyAsync(call, POOL);
    }
}
