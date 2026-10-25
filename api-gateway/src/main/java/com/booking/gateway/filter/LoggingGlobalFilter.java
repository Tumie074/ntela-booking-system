package com.booking.gateway.filter;

import com.booking.gateway.client.SocEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * Centralised request logging (spec 6.2): every request that hits the
 * Gateway - the single entry point - is logged on the way in and the way
 * out, with status code and latency. This is the one place all traffic
 * is guaranteed to pass through, making it the natural point for
 * centralised access logging.
 */
@Component
public class LoggingGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(LoggingGlobalFilter.class);
    private final SocEventPublisher socEventPublisher;

    public LoggingGlobalFilter(SocEventPublisher socEventPublisher) {
        this.socEventPublisher = socEventPublisher;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        long start = System.currentTimeMillis();
        String path = request.getPath().value();
        String method = request.getMethod().name();
        String requestId = request.getId();
        String sourceIp = request.getRemoteAddress() != null ? request.getRemoteAddress().getAddress().getHostAddress() : null;

        log.info("event=request_received requestId={} method={} path={} remote={}",
                requestId, method, path, request.getRemoteAddress());
        // Every request through the single entry point is forwarded to the SOC
        // (spec 3.1 "the API Gateway"); this also feeds the Abnormal Request Rate rule.
        socEventPublisher.publish("request_received", "LOW", null, sourceIp, path, method, null, null);

        return chain.filter(exchange).doFinally(signal -> {
            long duration = System.currentTimeMillis() - start;
            int status = exchange.getResponse().getStatusCode() != null
                    ? exchange.getResponse().getStatusCode().value() : 0;
            log.info("event=request_completed requestId={} method={} path={} status={} durationMs={}",
                    requestId, method, path, status, duration);

            if (status == 401 || status == 403) {
                // SOC-relevant event: unauthorised access attempt to a protected endpoint
                log.warn("event=unauthorised_access_attempt requestId={} path={} status={}", requestId, path, status);
                socEventPublisher.publish("unauthorised_access_attempt", "MEDIUM", null, sourceIp, path, method, status,
                        "Rejected with status " + status);
            }
        });
    }

    @Override
    public int getOrder() {
        return -1; // run early/late relative to other filters
    }
}
