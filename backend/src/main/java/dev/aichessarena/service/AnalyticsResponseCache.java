package dev.aichessarena.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/** Bounded cache of completed responses with independently coalesced loads. */
final class AnalyticsResponseCache<T> {
    private final Cache<String, T> responses;
    private final Map<String, CompletableFuture<T>> inFlight = new HashMap<>();

    AnalyticsResponseCache() {
        this(Ticker.systemTicker());
    }

    AnalyticsResponseCache(Ticker ticker) {
        responses = Caffeine.newBuilder()
                .maximumSize(64)
                .expireAfterWrite(Duration.ofSeconds(30))
                .executor(Runnable::run)
                .ticker(ticker)
                .build();
    }

    T get(String key, Supplier<T> load) {
        CompletableFuture<T> pending;
        synchronized (inFlight) {
            T cached = responses.getIfPresent(key);
            if (cached != null) {
                return cached;
            }
            pending = inFlight.get(key);
            if (pending == null) {
                inFlight.put(key, new CompletableFuture<>());
            }
        }
        if (pending != null) {
            return await(pending);
        }

        // Panache reads use the initiating request's CDI context.
        try {
            T value = load.get();
            responses.put(key, value);
            synchronized (inFlight) {
                inFlight.remove(key).complete(value);
            }
            return value;
        } catch (RuntimeException | Error failure) {
            synchronized (inFlight) {
                inFlight.remove(key).completeExceptionally(new CompletionException(failure));
            }
            throw failure;
        }
    }

    private T await(CompletableFuture<T> pending) {
        try {
            return pending.join();
        } catch (CompletionException failure) {
            if (failure.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            if (failure.getCause() instanceof Error cause) {
                throw cause;
            }
            throw failure;
        }
    }
}
