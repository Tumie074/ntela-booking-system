package com.booking.booking.client;

import com.booking.booking.dto.BookingDtos.ResourceView;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Optional;

/**
 * Client for the Resource Service, called from the Booking Service.
 * Implements the Retry + Circuit Breaker resilience patterns (section 6.6):
 *  - Retry: transient failures are retried a few times with backoff.
 *  - Circuit Breaker: if resource-service keeps failing, the circuit opens
 *    and calls fail fast (with a fallback) instead of piling up and cascading
 *    the failure back to booking clients.
 */
@Component
public class ResourceServiceClient {

    private static final Logger log = LoggerFactory.getLogger(ResourceServiceClient.class);
    private final WebClient webClient;
    private final SocEventPublisher socEventPublisher;

    public ResourceServiceClient(WebClient.Builder loadBalancedWebClientBuilder, SocEventPublisher socEventPublisher) {
        // "resource-service" is the Eureka application name, not a hostname -
        // the load balancer resolves it to a real instance.
        this.webClient = loadBalancedWebClientBuilder.baseUrl("http://resource-service").build();
        this.socEventPublisher = socEventPublisher;
    }

    @CircuitBreaker(name = "resourceService", fallbackMethod = "fallbackGetResource")
    @Retry(name = "resourceService")
    public Optional<ResourceView> getResource(Long resourceId) {
        log.info("event=inter_service_call caller=booking-service callee=resource-service resourceId={}", resourceId);
        ResourceView view = webClient.get()
                .uri("/api/resources/{id}", resourceId)
                .retrieve()
                .bodyToMono(ResourceView.class)
                .block();
        return Optional.ofNullable(view);
    }

    /**
     * Fallback invoked when resource-service is unreachable/failing or the
     * circuit is open. Prevents booking-service from hanging or cascading the failure.
     */
    private Optional<ResourceView> fallbackGetResource(Long resourceId, Throwable t) {
        log.error("event=dependency_failure caller=booking-service callee=resource-service resourceId={} reason={}",
                resourceId, t.getMessage());
        socEventPublisher.publish("dependency_failure", "HIGH", null, "/api/resources/" + resourceId,
                "GET", 503, "booking-service could not reach resource-service: " + t.getMessage(), "resource-service");
        return Optional.empty();
    }

    /**
     * Flips the resource to unavailable after a booking is confirmed, so the
     * same resource cannot be double-booked. Same resilience treatment as the
     * read path: a transient failure here should not fail the booking that
     * was already persisted, so failures are logged and swallowed rather than
     * propagated - the resource simply stays "available" until the next
     * read/retry reconciles it.
     */
    @CircuitBreaker(name = "resourceService", fallbackMethod = "fallbackSetAvailability")
    @Retry(name = "resourceService")
    public void setUnavailable(Long resourceId) {
        setAvailability(resourceId, false);
    }

    @CircuitBreaker(name = "resourceService", fallbackMethod = "fallbackSetAvailability")
    @Retry(name = "resourceService")
    public void setAvailable(Long resourceId) {
        setAvailability(resourceId, true);
    }

    private void setAvailability(Long resourceId, boolean available) {
        log.info("event=inter_service_call caller=booking-service callee=resource-service action=set_availability resourceId={} available={}",
                resourceId, available);
        webClient.patch()
                .uri("/api/resources/{id}/availability?available={available}", resourceId, available)
                .retrieve()
                .toBodilessEntity()
                .block();
    }

    private void fallbackSetAvailability(Long resourceId, Throwable t) {
        log.error("event=dependency_failure caller=booking-service callee=resource-service action=set_availability resourceId={} reason={}",
                resourceId, t.getMessage());
        socEventPublisher.publish("dependency_failure", "HIGH", null, "/api/resources/" + resourceId + "/availability",
                "PATCH", 503, "booking-service could not update resource-service: " + t.getMessage(), "resource-service");
    }
}
