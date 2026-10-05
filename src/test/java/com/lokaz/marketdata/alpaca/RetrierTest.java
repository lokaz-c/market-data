package com.lokaz.marketdata.alpaca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

class RetrierTest {

    private static final Instant NOW = Instant.parse("2026-10-05T20:30:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final AlpacaProperties.Retry POLICY =
            new AlpacaProperties.Retry(5, Duration.ofMillis(500), Duration.ofSeconds(8));

    private final List<Duration> sleeps = new ArrayList<>();
    private final Retrier retrier = new Retrier(POLICY, new Random(42), sleeps::add, CLOCK);

    @Test
    void backoffCeilingDoublesFromInitialDelayAndIsCapped() {
        assertThat(retrier.ceilingMillis(1)).isEqualTo(500);
        assertThat(retrier.ceilingMillis(2)).isEqualTo(1_000);
        assertThat(retrier.ceilingMillis(3)).isEqualTo(2_000);
        assertThat(retrier.ceilingMillis(5)).isEqualTo(8_000);
        assertThat(retrier.ceilingMillis(6)).isEqualTo(8_000);
        assertThat(retrier.ceilingMillis(200)).isEqualTo(8_000);
    }

    @Test
    void fullJitterStaysWithinZeroAndTheCeilingAndActuallyVaries() {
        var seen = new java.util.HashSet<Long>();
        for (int i = 0; i < 1_000; i++) {
            long wait = retrier.jitteredBackoff(3).toMillis();
            assertThat(wait).isBetween(0L, 2_000L);
            seen.add(wait);
        }
        assertThat(seen.size()).isGreaterThan(100);
    }

    @Test
    void retries429And5xxThenReturnsTheResult() {
        var calls = new AtomicInteger();
        var retries = new ArrayList<Integer>();
        String result = retrier.call(() -> {
            int n = calls.incrementAndGet();
            if (n == 1) {
                throw new AlpacaHttpException(429, "too many requests", null);
            }
            if (n == 2) {
                throw new AlpacaHttpException(503, "unavailable", null);
            }
            return "ok";
        }, (retryNumber, wait, cause) -> retries.add(retryNumber));

        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(3);
        assertThat(retries).containsExactly(1, 2);
        assertThat(sleeps).hasSize(2);
    }

    @Test
    void retriesIoErrors() {
        var calls = new AtomicInteger();
        String result = retrier.call(() -> {
            if (calls.incrementAndGet() == 1) {
                throw new ResourceAccessException("connection reset");
            }
            return "ok";
        }, Retrier.RetryListener.NONE);
        assertThat(result).isEqualTo("ok");
    }

    @Test
    void givesUpAfterMaxAttemptsAndRethrowsTheLastError() {
        var calls = new AtomicInteger();
        assertThatThrownBy(() -> retrier.call(() -> {
            calls.incrementAndGet();
            throw new AlpacaHttpException(500, "boom", null);
        }, Retrier.RetryListener.NONE))
                .isInstanceOf(AlpacaHttpException.class)
                .extracting(e -> ((AlpacaHttpException) e).status()).isEqualTo(500);
        assertThat(calls).hasValue(POLICY.maxAttempts());
        assertThat(sleeps).hasSize(POLICY.maxAttempts() - 1);
    }

    @Test
    void doesNotRetryClientErrorsOtherThan429() {
        var calls = new AtomicInteger();
        assertThatThrownBy(() -> retrier.call(() -> {
            calls.incrementAndGet();
            throw new AlpacaHttpException(403, "subscription does not permit querying recent SIP data", null);
        }, Retrier.RetryListener.NONE)).isInstanceOf(AlpacaHttpException.class);
        assertThat(calls).hasValue(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void on429WaitsUntilTheRateLimitResetsWhenThatIsLaterThanTheBackoff() {
        var cause = new AlpacaHttpException(429, "", NOW.plusSeconds(5));
        assertThat(retrier.waitBeforeRetry(1, cause)).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void rateLimitWaitIsCappedAtMaxDelay() {
        var cause = new AlpacaHttpException(429, "", NOW.plusSeconds(120));
        assertThat(retrier.waitBeforeRetry(1, cause)).isEqualTo(POLICY.maxDelay());
    }

    @Test
    void resetTimeInThePastFallsBackToTheJitteredBackoff() {
        var cause = new AlpacaHttpException(429, "", NOW.minusSeconds(10));
        assertThat(retrier.waitBeforeRetry(1, cause).toMillis()).isBetween(0L, 500L);
    }
}
