package com.lokaz.marketdata.api;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
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

    /** Only needed when the chart explorer is hosted on another origin; empty by default. */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = properties.corsAllowedOrigins().stream().map(String::strip).filter(s -> !s.isEmpty())
                .toArray(String[]::new);
        if (origins.length > 0) {
            registry.addMapping("/v1/**").allowedOrigins(origins).allowedMethods("GET");
            registry.addMapping("/actuator/health/**").allowedOrigins(origins).allowedMethods("GET");
        }
    }

    @Bean
    OpenAPI marketDataOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("market-data API")
                        .version("v1")
                        .description("Daily bars, SQL window-function indicators and price levels. Without an "
                                + "API key only public sources are served and requests are rate-limited.")
                        .license(new License().name("MIT")))
                .components(new Components().addSecuritySchemes("apiKey",
                        new SecurityScheme().type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER)
                                .name(ApiAccessFilter.API_KEY_HEADER)));
    }
}
