package com.lokaz.marketdata.api;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;

@Configuration(proxyBeanMethods = false)
public class WebConfig implements WebMvcConfigurer {

    private final ApiProperties properties;

    public WebConfig(ApiProperties properties) {
        this.properties = properties;
    }

    /**
     * Only needed when the chart explorer is hosted on another origin; empty by default. Actuator endpoints
     * (the health check the UI polls) take the same origins via management.endpoints.web.cors.
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = properties.corsAllowedOrigins().stream().map(String::strip).filter(s -> !s.isEmpty())
                .toArray(String[]::new);
        if (origins.length > 0) {
            // Browsers hide response headers outside the CORS safelist unless they are exposed.
            registry.addMapping("/v1/**").allowedOrigins(origins).allowedMethods("GET")
                    .exposedHeaders(HttpHeaders.ETAG, HttpHeaders.RETRY_AFTER, RateLimitFilter.RATE_LIMIT_HEADER,
                            RateLimitFilter.POLICY_HEADER, RateLimitFilter.REMAINING_HEADER);
        }
    }

    /**
     * The chart explorer's build output (copied into static/ by the Dockerfile) has content-hashed file names
     * under /assets, so those can be cached for a year; index.html keeps the default revalidation.
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
    }

    @Bean
    OpenAPI marketDataOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("market-data API")
                        .version("v1")
                        .description("""
                                Daily bars, SQL window-function indicators and price levels.

                                Without an API key only public sources are served (synthetic data by default) and \
                                requests are rate-limited per client IP. A key's scopes decide what it adds: \
                                `rate-limit` (not rate-limited), `alpaca-data` (every source) and `export` \
                                (/v1/export).

                                Rate-limited responses carry `RateLimit-Policy: %s` (bucket size and seconds to \
                                refill it) and `RateLimit: "per-ip";r=<left>;t=<seconds to the next request>`, as in \
                                draft-ietf-httpapi-ratelimit-headers-11, plus `X-RateLimit-Remaining`; a 429 adds \
                                `Retry-After`.

                                /v1/bars responses carry a weak ETag; send it in If-None-Match to get a 304 while the \
                                symbol's data is unchanged.""".formatted(RateLimitFilter.policy(properties.rateLimit())))
                        .license(new License().name("MIT")))
                .components(new Components().addSecuritySchemes("apiKey",
                        new SecurityScheme().type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER)
                                .name(ApiAccessFilter.API_KEY_HEADER)
                                .description("Scopes, set per key in API_KEYS: " + Scope.names())));
    }
}
