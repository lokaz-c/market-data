package com.lokaz.marketdata.alpaca;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.ResourceAccessException;

/**
 * Retries a call on 429, 5xx and I/O errors with exponential backoff and "full jitter": the wait before
 * retry n is uniform in [0, min(maxDelay, initialDelay * 2^(n-1))]. Randomising the whole wait spreads
 * retries from concurrent clients instead of having them hit the server again in lockstep.
 *
 * <p>On a 429 that carries X-RateLimit-Reset, the wait is at least the time until the quota resets
 * (still capped at maxDelay), since retrying earlier is guaranteed to fail.
 *
 * <p>Spring Framework 7 ships a RetryTemplate, but its jitter is a +/- range around the exponential delay
 * and it cannot wait for a server-provided reset time, which is why this is a small class of its own.
 */
public class Retrier {

    private static final Logger log = LoggerFactory.getLogger(Retrier.class);

    /** Lets tests run without real sleeps. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    /** Notified once per retry, e.g. to count retries for the ingestion run log. */
    @FunctionalInterface
    public interface RetryListener {
        void onRetry(int retryNumber, Duration wait, RuntimeException cause);

        RetryListener NONE = (n, wait, cause) -> {
        };
    }

    private final AlpacaProperties.Retry policy;
    private final RandomGenerator random;
    private final Sleeper sleeper;
    private final Clock clock;

    public Retrier(AlpacaProperties.Retry policy, RandomGenerator random, Sleeper sleeper, Clock clock) {
        this.policy = policy;
        this.random = random;
        this.sleeper = sleeper;
        this.clock = clock;
    }

    public static Retrier withThreadSleep(AlpacaProperties.Retry policy, Clock clock) {
        return new Retrier(policy, RandomGenerator.getDefault(), d -> Thread.sleep(d), clock);
    }

    public <T> T call(Supplier<T> action, RetryListener listener) {
        for (int attempt = 1; ; attempt++) {
            try {
                return action.get();
            } catch (RuntimeException e) {
                if (!isRetryable(e) || attempt >= policy.maxAttempts()) {
                    throw e;
                }
                Duration wait = waitBeforeRetry(attempt, e);
                log.warn("Alpaca call failed (attempt {}/{}), retrying in {} ms: {}",
                        attempt, policy.maxAttempts(), wait.toMillis(), e.getMessage());
                listener.onRetry(attempt, wait, e);
                sleep(wait);
            }
        }
    }

    static boolean isRetryable(RuntimeException e) {
        return (e instanceof AlpacaHttpException http && http.isRetryable())
                || e instanceof ResourceAccessException; // connection refused/reset, timeouts
    }

    /** Wait before retry number {@code retryNumber} (1 = first retry). */
    Duration waitBeforeRetry(int retryNumber, RuntimeException cause) {
        Duration backoff = jitteredBackoff(retryNumber);
        if (cause instanceof AlpacaHttpException http && http.status() == 429 && http.rateLimitReset() != null) {
            Duration untilReset = Duration.between(Instant.now(clock), http.rateLimitReset());
            if (untilReset.compareTo(backoff) > 0) {
                backoff = untilReset;
            }
        }
        return backoff.compareTo(policy.maxDelay()) > 0 ? policy.maxDelay() : backoff;
    }

    Duration jitteredBackoff(int retryNumber) {
        long ceilingMillis = ceilingMillis(retryNumber);
        return Duration.ofMillis(ceilingMillis == 0 ? 0 : random.nextLong(ceilingMillis + 1));
    }

    long ceilingMillis(int retryNumber) {
        long initial = policy.initialDelay().toMillis();
        long max = policy.maxDelay().toMillis();
        int shift = Math.min(retryNumber - 1, 30); // 2^30 * initial already exceeds any sane cap
        long exponential = initial << shift;
        return (exponential < 0 || exponential > max) ? max : exponential;
    }

    private void sleep(Duration wait) {
        try {
            sleeper.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to retry", e);
        }
    }
}
