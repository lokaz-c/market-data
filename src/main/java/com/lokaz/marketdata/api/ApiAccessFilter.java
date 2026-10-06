package com.lokaz.marketdata.api;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
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
 * Resolves what a /v1 request may do from its X-API-Key and the key's scopes (see {@link Scope}):
 * <ul>
 * <li>{@code alpaca-data}: every source; without it, only {@code publicSources} (synthetic by default);</li>
 * <li>{@code export}: /v1/export; without a key that is a 401, with a key lacking the scope a 403;</li>
 * <li>{@code rate-limit}: read by {@link RateLimitFilter}, which runs next.</li>
 * </ul>
 * A key that is present but wrong is rejected with 401 rather than silently treated as anonymous, so a
 * misconfigured client finds out.
 *
 * <p>This is a single header check, so it is a filter rather than Spring Security.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiAccessFilter extends OncePerRequestFilter {

    public static final String API_KEY_HEADER = "X-API-Key";
    static final String SOURCES_ATTRIBUTE = ApiAccessFilter.class.getName() + ".sources";
    static final String AUTHENTICATED_ATTRIBUTE = ApiAccessFilter.class.getName() + ".authenticated";
    static final String SCOPES_ATTRIBUTE = ApiAccessFilter.class.getName() + ".scopes";
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
        Set<Scope> scopes = Set.of();
        if (key != null) {
            Optional<Set<Scope>> found = keys.scopes(key);
            if (found.isEmpty()) {
                problems.write(request, response, HttpStatus.UNAUTHORIZED, "The X-API-Key header is not a valid key.");
                return;
            }
            scopes = found.get();
        }
        if (request.getRequestURI().startsWith("/v1/export")) {
            if (key == null) {
                problems.write(request, response, HttpStatus.UNAUTHORIZED, "Bulk export requires an X-API-Key header.");
                return;
            }
            if (!scopes.contains(Scope.EXPORT)) {
                problems.write(request, response, HttpStatus.FORBIDDEN, "This API key does not have the export scope.");
                return;
            }
        }
        request.setAttribute(AUTHENTICATED_ATTRIBUTE, key != null);
        request.setAttribute(SCOPES_ATTRIBUTE, scopes);
        request.setAttribute(SOURCES_ATTRIBUTE, scopes.contains(Scope.ALPACA_DATA) ? ALL_SOURCES : publicSources);
        chain.doFilter(request, response);
    }

    /** True for any request with a valid key, whatever its scopes. */
    static boolean isAuthenticated(HttpServletRequest request) {
        return Boolean.TRUE.equals(request.getAttribute(AUTHENTICATED_ATTRIBUTE));
    }

    static boolean hasScope(HttpServletRequest request, Scope scope) {
        return request.getAttribute(SCOPES_ATTRIBUTE) instanceof Set<?> scopes && scopes.contains(scope);
    }

    @SuppressWarnings("unchecked")
    static List<String> sources(HttpServletRequest request) {
        Object sources = request.getAttribute(SOURCES_ATTRIBUTE);
        return sources == null ? List.of() : (List<String>) sources;
    }
}
