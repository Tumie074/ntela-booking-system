package com.booking.user.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Phase 2: forwards the same security-relevant events this service already
 * logs via SLF4J (see AuthController) to soc-service's event-collection
 * endpoint, so they can be stored, rule-evaluated and added to the knowledge
 * graph. Fire-and-forget: a slow/unreachable soc-service must never add
 * latency or failures to a login/register request, so publish() never blocks
 * the caller and any error is only logged, never thrown.
 */
@Component
public class SocEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(SocEventPublisher.class);
    private final WebClient webClient;

    public SocEventPublisher(WebClient.Builder loadBalancedWebClientBuilder) {
        this.webClient = loadBalancedWebClientBuilder.baseUrl("http://soc-service").build();
    }

    public void publish(String eventType, String severity, String userId, String endpoint,
                         String httpMethod, Integer statusCode, String message, String affectedEntity) {
        Map<String, Object> body = new HashMap<>();
        body.put("serviceName", "user-service");
        body.put("eventType", eventType);
        body.put("severity", severity);
        body.put("userId", userId);
        body.put("endpoint", endpoint);
        body.put("httpMethod", httpMethod);
        body.put("statusCode", statusCode);
        body.put("message", message);
        body.put("affectedEntity", affectedEntity);

        webClient.post()
                .uri("/api/soc/events")
                .bodyValue(body)
                .retrieve()
                .toBodilessEntity()
                .timeout(Duration.ofSeconds(2))
                .onErrorResume(ex -> {
                    log.warn("event=soc_publish_failed eventType={} reason={}", eventType, ex.getMessage());
                    return Mono.empty();
                })
                .subscribe();
    }
}
