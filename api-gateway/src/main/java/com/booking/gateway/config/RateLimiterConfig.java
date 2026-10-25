package com.booking.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/**
 * Defines how the RequestRateLimiter groups requests (spec 3.1: rate limiting).
 * Here we rate-limit per client IP; in a real deployment with authenticated
 * routes this could instead resolve to the X-User-Name header set by the JWT filter.
 */
@Configuration
public class RateLimiterConfig {

    @Bean
    public KeyResolver ipKeyResolver() {
        return exchange -> Mono.just(
                exchange.getRequest().getRemoteAddress() != null
                        ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
                        : "unknown"
        );
    }
}
