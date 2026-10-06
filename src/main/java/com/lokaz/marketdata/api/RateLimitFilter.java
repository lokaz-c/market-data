package com.lokaz.marketdata.api;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;

/**
 * Per-client-IP token bucket on the public /v1 endpoints. Requests with a key that has the {@code rate-limit} scope
 * are not limited; other requests, with or without a key, are.
 *
 * <p>Bucket4j implements the token bucket (refill, burst capacity, time to next token); buckets are kept in a
 * Caffeine cache that drops idle clients and caps memory. State is in-process, which is right for one
 * instance; with several instances the same Bucket4j API can sit on a shared store instead.
 *
 * <p>Limited responses carry the fields of the IETF draft "RateLimit header fields for HTTP"
 * (draft-ietf-httpapi-ratelimit-headers-11), next to the older X-RateLimit-Remaining and, on a 429, Retry-After:
 * <pre>
 * RateLimit-Policy: "per-ip";q=30;w=30
 * RateLimit: "per-ip";r=29;t=1
 * </pre>
 * The policy describes the bucket: {@code q} is its capacity (the burst) and {@code w} the seconds it takes to
 * refill from empty, so q/w is the sustained rate (60 a minute by default). {@code r} is the requests left now and
 * {@code t} the seconds until the next one is added: the draft's "time within which the client can use no more
 * than the available quota". On a 429, t equals Retry-After, as the draft asks (Retry-After should not point
 * earlier than the end of the effective window).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RateLimitFilter extends OncePerRequestFilter {

    static final String REMAINING_HEADER = "X-RateLimit-Remaining";
    static final String RATE_LIMIT_HEADER = "RateLimit";
    static final String POLICY_HEADER = "RateLimit-Policy";
    static final String POLICY_NAME = "\"per-ip\"";
    private static final long NANOS_PER_SECOND = TimeUnit.SECONDS.toNanos(1);

    private final ApiProperties.RateLimit limit;
    private final Problems problems;
    private final long nanosPerToken;
    private final String policy;
    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(10))
            .maximumSize(100_000)
            .build();

    public RateLimitFilter(ApiProperties properties, Problems problems) {
        this.limit = properties.rateLimit();
        this.problems = problems;
        this.nanosPerToken = nanosPerToken(limit);
        this.policy = policy(limit);
    }

    /** The RateLimit-Policy value for a bucket: its capacity and the whole seconds it takes to refill. */
    static String policy(ApiProperties.RateLimit limit) {
        return POLICY_NAME + ";q=" + limit.capacity() + ";w=" + ceilSeconds(limit.capacity() * nanosPerToken(limit));
    }

    private static long nanosPerToken(ApiProperties.RateLimit limit) {
        return TimeUnit.MINUTES.toNanos(1) / limit.requestsPerMinute();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !limit.enabled() || !request.getRequestURI().startsWith("/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (ApiAccessFilter.hasScope(request, Scope.RATE_LIMIT)) {
            chain.doFilter(request, response);
            return;
        }
        // Behind a proxy, server.forward-headers-strategy=native makes this the client address.
        Bucket bucket = buckets.get(request.getRemoteAddr(), ip -> newBucket());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        response.setHeader(POLICY_HEADER, policy);
        if (probe.isConsumed()) {
            long remaining = probe.getRemainingTokens();
            response.setHeader(RATE_LIMIT_HEADER, POLICY_NAME + ";r=" + remaining + ";t=" + secondsToNextToken(probe));
            response.setHeader(REMAINING_HEADER, Long.toString(remaining));
            chain.doFilter(request, response);
            return;
        }
        long retryAfterSeconds = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()) + 1);
        response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
        response.setHeader(RATE_LIMIT_HEADER, POLICY_NAME + ";r=0;t=" + retryAfterSeconds);
        response.setHeader(REMAINING_HEADER, "0");
        problems.write(request, response, HttpStatus.TOO_MANY_REQUESTS,
                "Rate limit of " + limit.requestsPerMinute() + " requests per minute exceeded. Retry in "
                        + retryAfterSeconds + " s.");
    }

    /**
     * Greedy refill adds a token every {@code nanosPerToken}, and the bucket is full again after
     * {@code nanosToWaitForReset}. Take away the time for all but one of the missing tokens and what is left is
     * the wait for the next one.
     */
    private long secondsToNextToken(ConsumptionProbe probe) {
        long missing = limit.capacity() - probe.getRemainingTokens(); // at least 1: this request took one
        long nanos = probe.getNanosToWaitForReset() - (missing - 1) * nanosPerToken;
        return ceilSeconds(Math.clamp(nanos, 0, nanosPerToken));
    }

    private static long ceilSeconds(long nanos) {
        return (nanos + NANOS_PER_SECOND - 1) / NANOS_PER_SECOND;
    }

    private Bucket newBucket() {
        return Bucket.builder()
                .addLimit(l -> l.capacity(limit.capacity()).refillGreedy(limit.requestsPerMinute(), Duration.ofMinutes(1)))
                .build();
    }
}
