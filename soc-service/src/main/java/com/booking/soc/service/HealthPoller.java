package com.booking.soc.service;

import com.booking.soc.dto.EventDtos.EventRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

/**
 * Periodically polls each Phase 1 service's /actuator/health endpoint
 * (service/container health info - spec 3.1 "container or service health
 * information") and records the result as a service_health_check event, so
 * repeated failures feed the Service Failure detection rule (spec 3.3 Rule 4)
 * even when nothing is actively calling that service right now.
 */
@Component
public class HealthPoller {

    private static final Logger log = LoggerFactory.getLogger(HealthPoller.class);

    private final WebClient webClient;
    private final EventIngestionService eventIngestionService;

    // Fixed set of Phase 1 services to poll - matches the architecture in
    // docker-compose.yml. Kept as a plain constant rather than a YAML list
    // bound via @Value, since @Value does not bind YAML sequences to
    // List<String> without @ConfigurationProperties.
    private static final List<String> SERVICES = List.of(
            "user-service", "resource-service", "booking-service", "api-gateway");

    public HealthPoller(WebClient.Builder loadBalancedWebClientBuilder, EventIngestionService eventIngestionService) {
        this.webClient = loadBalancedWebClientBuilder.build();
        this.eventIngestionService = eventIngestionService;
    }

    @Scheduled(fixedDelayString = "${soc.health-poll.interval-ms:30000}")
    public void pollAll() {
        for (String service : SERVICES) {
            pollOne(service);
        }
    }

    private void pollOne(String service) {
        try {
            Map<?, ?> body = webClient.get()
                    .uri("http://" + service + "/actuator/health")
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
            String status = body != null ? String.valueOf(body.get("status")) : "UNKNOWN";
            boolean up = "UP".equals(status);

            eventIngestionService.ingest(new EventRequest(
                    service, "service_health_check", up ? "LOW" : "HIGH", null, null,
                    "/actuator/health", "GET", up ? 200 : 503,
                    "Health check reported status=" + status, null, service));
        } catch (Exception ex) {
            log.warn("event=health_poll_failed serviceName={} reason={}", service, ex.getMessage());
            eventIngestionService.ingest(new EventRequest(
                    service, "service_health_check", "HIGH", null, null,
                    "/actuator/health", "GET", 503,
                    "Health check unreachable: " + ex.getMessage(), null, service));
        }
    }
}
