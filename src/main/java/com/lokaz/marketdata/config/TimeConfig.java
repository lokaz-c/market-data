package com.lokaz.marketdata.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class TimeConfig {

    /** US equity sessions are dated in New York time; injecting the clock keeps "today" testable. */
    public static final ZoneId MARKET_ZONE = ZoneId.of("America/New_York");

    @Bean
    Clock clock() {
        return Clock.system(MARKET_ZONE);
    }
}
