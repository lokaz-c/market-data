package com.lokaz.marketdata.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import tools.jackson.databind.json.JsonMapper;

class RateLimitFilterTest {

    private final RateLimitFilter filter = new RateLimitFilter(
            new ApiProperties(List.of(), "", List.of("synthetic"), 100, List.of(), new ApiProperties.RateLimit(true, 2, 60)),
            new Problems(JsonMapper.builder().build()));

    private MockHttpServletResponse call(String ip, String path, Set<Scope> scopes) throws Exception {
        var request = new MockHttpServletRequest("GET", path);
        request.setRemoteAddr(ip);
        request.setAttribute(ApiAccessFilter.AUTHENTICATED_ATTRIBUTE, !scopes.isEmpty());
        request.setAttribute(ApiAccessFilter.SCOPES_ATTRIBUTE, scopes);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private MockHttpServletResponse call(String ip, String path) throws Exception {
        return call(ip, path, Set.of());
    }

    @Test
    void allowsTheBurstThenRejectsWithA429ProblemAndRetryAfter() throws Exception {
        assertThat(call("10.0.0.1", "/v1/bars/SYNA").getStatus()).isEqualTo(200);
        MockHttpServletResponse second = call("10.0.0.1", "/v1/bars/SYNA");
        assertThat(second.getHeader(RateLimitFilter.REMAINING_HEADER)).isEqualTo("0");

        MockHttpServletResponse third = call("10.0.0.1", "/v1/bars/SYNA");
        assertThat(third.getStatus()).isEqualTo(429);
        assertThat(third.getContentType()).isEqualTo("application/problem+json");
        assertThat(Integer.parseInt(third.getHeader("Retry-After"))).isBetween(1, 2);
        assertThat(third.getContentAsString()).contains("\"status\":429").contains("Too Many Requests");
    }

    @Test
    void sendsTheDraftRateLimitFields() throws Exception {
        MockHttpServletResponse first = call("10.0.0.4", "/v1/bars/SYNA");
        MockHttpServletResponse second = call("10.0.0.4", "/v1/bars/SYNA");
        MockHttpServletResponse limited = call("10.0.0.4", "/v1/bars/SYNA");

        // Capacity 2 refilled at 60 a minute: 2 requests, 2 seconds to refill from empty.
        assertThat(first.getHeader("RateLimit-Policy")).isEqualTo("\"per-ip\";q=2;w=2");
        // One token comes back every second, so the next one is at most a second away.
        assertThat(first.getHeader("RateLimit")).isEqualTo("\"per-ip\";r=1;t=1");
        assertThat(second.getHeader("RateLimit")).isEqualTo("\"per-ip\";r=0;t=1");
        // On a 429 the effective window ends exactly when Retry-After says to come back.
        assertThat(limited.getHeader("RateLimit")).isEqualTo("\"per-ip\";r=0;t=" + limited.getHeader("Retry-After"));
        assertThat(limited.getHeader("RateLimit-Policy")).isEqualTo("\"per-ip\";q=2;w=2");
    }

    @Test
    void thePolicyIsDerivedFromTheConfiguredBucket() {
        assertThat(RateLimitFilter.policy(new ApiProperties.RateLimit(true, 30, 60))).isEqualTo("\"per-ip\";q=30;w=30");
        assertThat(RateLimitFilter.policy(new ApiProperties.RateLimit(true, 10, 120))).isEqualTo("\"per-ip\";q=10;w=5");
        assertThat(RateLimitFilter.policy(new ApiProperties.RateLimit(true, 10, 7))).isEqualTo("\"per-ip\";q=10;w=86");
    }

    @Test
    void eachClientHasItsOwnBucket() throws Exception {
        call("10.0.0.1", "/v1/bars/SYNA");
        call("10.0.0.1", "/v1/bars/SYNA");
        assertThat(call("10.0.0.1", "/v1/bars/SYNA").getStatus()).isEqualTo(429);
        assertThat(call("10.0.0.2", "/v1/bars/SYNA").getStatus()).isEqualTo(200);
    }

    @Test
    void onlyTheRateLimitScopeLiftsTheLimit() throws Exception {
        for (int i = 0; i < 5; i++) {
            MockHttpServletResponse scoped = call("10.0.0.3", "/v1/bars/SYNA", Set.of(Scope.RATE_LIMIT));
            assertThat(scoped.getStatus()).isEqualTo(200);
            assertThat(scoped.getHeader("RateLimit")).isNull();
            assertThat(call("10.0.0.3", "/actuator/health").getStatus()).isEqualTo(200);
        }
        Set<Scope> otherScopes = Set.of(Scope.ALPACA_DATA, Scope.EXPORT);
        call("10.0.0.5", "/v1/bars/SYNA", otherScopes);
        call("10.0.0.5", "/v1/bars/SYNA", otherScopes);
        assertThat(call("10.0.0.5", "/v1/bars/SYNA", otherScopes).getStatus()).isEqualTo(429);
    }
}
