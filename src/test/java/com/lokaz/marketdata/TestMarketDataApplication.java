package com.lokaz.marketdata;

import org.springframework.boot.SpringApplication;

/** Runs the app locally against a throwaway Testcontainers PostgreSQL: ./mvnw spring-boot:test-run */
public class TestMarketDataApplication {

    public static void main(String[] args) {
        SpringApplication.from(MarketDataApplication::main).with(TestcontainersConfiguration.class).run(args);
    }
}
