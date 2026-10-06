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
 * Per-client-IP token bucket on the public /v1 endpoints; requests with a valid API key are not limited.
 *
 * <p>Bucket4j implements the token bucket (refill, burst capacity, time to next token); buckets are kept in a
 * Caffeine cache that drops idle clients and caps memory. State is in-process, which is right for one
 * instance; with several instances the same Bucket4j API can sit on a shared store instead.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RateLimitFilter extends OncePerRequestFilter {

    static final String REMAINING_HEADER = "X-RateLimit-Remaining";

    private final ApiProperties.RateLimit limit;
    private final Problems problems;
    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(10))
            .maximumSize(100_000)
            .build();

    public RateLimitFilter(ApiProperties properties, Problems problems) {
        this.limit = properties.rateLimit();
        this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !limit.enabled() || !request.getRequestURI().startsWith("/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (ApiAccessFilter.isAuthenticated(request)) {
            chain.doFilter(request, response);
            return;
        }
        // Behind a proxy, server.forward-headers-strategy=native makes this the client address.
        Bucket bucket = buckets.get(request.getRemoteAddr(), ip -> newBucket());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            response.setHeader(REMAINING_HEADER, Long.toString(probe.getRemainingTokens()));
            chain.doFilter(request, response);
            return;
        }
        long retryAfterSeconds = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()) + 1);
        response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
        response.setHeader(REMAINING_HEADER, "0");
        problems.write(request, response, HttpStatus.TOO_MANY_REQUESTS,
                "Rate limit of " + limit.requestsPerMinute() + " requests per minute exceeded. Retry in "
                        + retryAfterSeconds + " s.");
    }

    private Bucket newBucket() {
        return Bucket.builder()
                .addLimit(l -> l.capacity(limit.capacity()).refillGreedy(limit.requestsPerMinute(), Duration.ofMinutes(1)))
                .build();
    }
}
