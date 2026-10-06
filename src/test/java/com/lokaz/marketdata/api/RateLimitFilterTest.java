package com.lokaz.marketdata.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import tools.jackson.databind.json.JsonMapper;

class RateLimitFilterTest {

    private final RateLimitFilter filter = new RateLimitFilter(
            new ApiProperties(List.of(), List.of("synthetic"), 100, List.of(), new ApiProperties.RateLimit(true, 2, 60)),
            new Problems(JsonMapper.builder().build()));

    private MockHttpServletResponse call(String ip, String path, boolean authenticated) throws Exception {
        var request = new MockHttpServletRequest("GET", path);
        request.setRemoteAddr(ip);
        request.setAttribute(ApiAccessFilter.AUTHENTICATED_ATTRIBUTE, authenticated);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void allowsTheBurstThenRejectsWithA429ProblemAndRetryAfter() throws Exception {
        assertThat(call("10.0.0.1", "/v1/bars/SYNA", false).getStatus()).isEqualTo(200);
        MockHttpServletResponse second = call("10.0.0.1", "/v1/bars/SYNA", false);
        assertThat(second.getHeader(RateLimitFilter.REMAINING_HEADER)).isEqualTo("0");

        MockHttpServletResponse third = call("10.0.0.1", "/v1/bars/SYNA", false);
        assertThat(third.getStatus()).isEqualTo(429);
        assertThat(third.getContentType()).isEqualTo("application/problem+json");
        assertThat(Integer.parseInt(third.getHeader("Retry-After"))).isBetween(1, 2);
        assertThat(third.getContentAsString()).contains("\"status\":429").contains("Too Many Requests");
    }

    @Test
    void eachClientHasItsOwnBucket() throws Exception {
        call("10.0.0.1", "/v1/bars/SYNA", false);
        call("10.0.0.1", "/v1/bars/SYNA", false);
        assertThat(call("10.0.0.1", "/v1/bars/SYNA", false).getStatus()).isEqualTo(429);
        assertThat(call("10.0.0.2", "/v1/bars/SYNA", false).getStatus()).isEqualTo(200);
    }

    @Test
    void requestsWithAValidKeyAndNonApiPathsAreNotLimited() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(call("10.0.0.3", "/v1/bars/SYNA", true).getStatus()).isEqualTo(200);
            assertThat(call("10.0.0.3", "/actuator/health", false).getStatus()).isEqualTo(200);
        }
    }
}
