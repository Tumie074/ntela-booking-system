package com.booking.gateway.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.util.List;

/**
 * Gateway-level JWT validation (spec 6.4): the API Gateway itself validates
 * tokens for protected routes so individual services don't each need to
 * re-implement authentication. Downstream services trust the gateway.
 * Applied to routes via the "JwtAuth" filter name in application.yml.
 */
@Component
public class JwtAuthenticationFilter extends AbstractGatewayFilterFactory<JwtAuthenticationFilter.Config> {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private final SecretKey key;

    public JwtAuthenticationFilter(@Value("${jwt.secret}") String secret) {
        super(Config.class);
        this.key = Keys.hmacShaKeyFor(secret.getBytes());
    }

    public static class Config {
        // Marker config class; no fields needed for this simple filter.
    }

    /**
     * Spring Cloud Gateway resolves a filter name in YAML (e.g. "JwtAuth") to a
     * bean by convention: the class must be named "<Name>GatewayFilterFactory".
     * This class is named JwtAuthenticationFilter instead, so we override name()
     * explicitly to register it under "JwtAuth" regardless of the class name.
     */
    @Override
    public String name() {
        return "JwtAuth";
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            List<String> authHeaders = exchange.getRequest().getHeaders().get(HttpHeaders.AUTHORIZATION);

            if (authHeaders == null || authHeaders.isEmpty() || !authHeaders.get(0).startsWith("Bearer ")) {
                log.warn("event=missing_or_malformed_token path={}", exchange.getRequest().getPath());
                exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                return exchange.getResponse().setComplete();
            }

            String token = authHeaders.get(0).substring(7);
            try {
                Claims claims = Jwts.parser().verifyWith(key).build()
                        .parseSignedClaims(token)
                        .getPayload();

                // Forward identity to downstream services as headers so they
                // don't need to re-parse the JWT themselves. mutate().build() returns
                // a NEW request object - it must be applied to a new exchange, otherwise
                // the headers are silently dropped and chain.filter() forwards the
                // original, un-mutated request.
                ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                        .header("X-User-Name", claims.getSubject())
                        .header("X-User-Role", String.valueOf(claims.get("role")))
                        .build();
                ServerWebExchange mutatedExchange = exchange.mutate().request(mutatedRequest).build();

                log.info("event=token_validated user={} role={} path={}",
                        claims.getSubject(), claims.get("role"), exchange.getRequest().getPath());

                return chain.filter(mutatedExchange);

            } catch (JwtException e) {
                log.warn("event=invalid_token path={} reason={}", exchange.getRequest().getPath(), e.getMessage());
                exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                return exchange.getResponse().setComplete();
            }
        };
    }
}
