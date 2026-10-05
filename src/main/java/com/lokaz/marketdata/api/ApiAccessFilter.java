package com.lokaz.marketdata.api;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves what a /v1 request may see. Without a key: only {@code publicSources}, and no bulk export. With a
 * valid X-API-Key: every source and /v1/export. A key that is present but wrong is rejected with 401 rather
 * than silently treated as anonymous, so a misconfigured client finds out.
 *
 * <p>This is a single header check, so it is a filter rather than Spring Security.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiAccessFilter extends OncePerRequestFilter {

    public static final String API_KEY_HEADER = "X-API-Key";
    static final String SOURCES_ATTRIBUTE = ApiAccessFilter.class.getName() + ".sources";
    static final String AUTHENTICATED_ATTRIBUTE = ApiAccessFilter.class.getName() + ".authenticated";
    private static final List<String> ALL_SOURCES = List.of("alpaca", "synthetic");

    private final ApiKeys keys;
    private final List<String> publicSources;
    private final Problems problems;

    public ApiAccessFilter(ApiKeys keys, ApiProperties properties, Problems problems) {
        this.keys = keys;
        this.publicSources = properties.publicSources().stream().map(String::strip)
                .filter(Set.copyOf(ALL_SOURCES)::contains).toList();
        this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader(API_KEY_HEADER);
        boolean authenticated = false;
        if (key != null) {
            if (!keys.isValid(key)) {
                problems.write(request, response, HttpStatus.UNAUTHORIZED, "The X-API-Key header is not a valid key.");
                return;
            }
            authenticated = true;
        }
        if (!authenticated && request.getRequestURI().startsWith("/v1/export")) {
            problems.write(request, response, HttpStatus.UNAUTHORIZED, "Bulk export requires an X-API-Key header.");
            return;
        }
        request.setAttribute(AUTHENTICATED_ATTRIBUTE, authenticated);
        request.setAttribute(SOURCES_ATTRIBUTE, authenticated ? ALL_SOURCES : publicSources);
        chain.doFilter(request, response);
    }

    static boolean isAuthenticated(HttpServletRequest request) {
        return Boolean.TRUE.equals(request.getAttribute(AUTHENTICATED_ATTRIBUTE));
    }

    @SuppressWarnings("unchecked")
    static List<String> sources(HttpServletRequest request) {
        Object sources = request.getAttribute(SOURCES_ATTRIBUTE);
        return sources == null ? List.of() : (List<String>) sources;
    }
}
