package com.booking.soc.service;

import com.booking.soc.model.SecurityEvent;
import com.booking.soc.repository.SecurityEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Threat Detection Rules (spec 3.3). Runs on a fixed schedule, scans
 * un-processed stored events for each rule's condition, and raises an Alert
 * (spec 3.4) through AlertService when a condition is met. Matched events
 * are marked processed so the same burst isn't alerted on twice, while still
 * allowing a *new* burst afterwards to raise a fresh alert.
 *
 * Implements 4 of the example rules from the spec (only 3 are required):
 *   Rule 1 - Multiple Failed Logins
 *   Rule 2 - Unauthorised Endpoint Access
 *   Rule 3 - Abnormal Request Rate
 *   Rule 4 - Service Failure
 */
@Component
public class RuleEngine {

    private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);

    private final SecurityEventRepository eventRepository;
    private final AlertService alertService;

    @Value("${soc.rules.failed-login.threshold:5}")
    private int failedLoginThreshold;
    @Value("${soc.rules.failed-login.window-minutes:5}")
    private int failedLoginWindowMinutes;
    @Value("${soc.rules.failed-login.severity:HIGH}")
    private String failedLoginSeverity;

    @Value("${soc.rules.unauthorised-access.threshold:3}")
    private int unauthThreshold;
    @Value("${soc.rules.unauthorised-access.window-minutes:5}")
    private int unauthWindowMinutes;
    @Value("${soc.rules.unauthorised-access.severity:MEDIUM}")
    private String unauthSeverity;

    @Value("${soc.rules.abnormal-request-rate.threshold:20}")
    private int rateThreshold;
    @Value("${soc.rules.abnormal-request-rate.window-minutes:1}")
    private int rateWindowMinutes;
    @Value("${soc.rules.abnormal-request-rate.severity:MEDIUM}")
    private String rateSeverity;

    @Value("${soc.rules.service-failure.threshold:3}")
    private int failureThreshold;
    @Value("${soc.rules.service-failure.window-minutes:5}")
    private int failureWindowMinutes;
    @Value("${soc.rules.service-failure.severity:HIGH}")
    private String failureSeverity;

    public RuleEngine(SecurityEventRepository eventRepository, AlertService alertService) {
        this.eventRepository = eventRepository;
        this.alertService = alertService;
    }

    @Scheduled(fixedDelayString = "15000")
    @Transactional
    public void evaluateAll() {
        evaluateFailedLogins();
        evaluateUnauthorisedAccess();
        evaluateAbnormalRequestRate();
        evaluateServiceFailure();
    }

    /** Rule 1: Multiple Failed Logins - same user has N failed_login_attempt events within the window -> HIGH alert. */
    private void evaluateFailedLogins() {
        Instant since = Instant.now().minus(failedLoginWindowMinutes, ChronoUnit.MINUTES);
        List<SecurityEvent> events = eventRepository
                .findAllByProcessedFalseAndEventTypeAndTimestampAfter("failed_login_attempt", since);

        Map<String, List<SecurityEvent>> byUser = events.stream()
                .filter(e -> e.getUserId() != null)
                .collect(Collectors.groupingBy(SecurityEvent::getUserId));

        byUser.forEach((userId, userEvents) -> {
            if (userEvents.size() >= failedLoginThreshold) {
                alertService.raise(
                        "Multiple Failed Logins", failedLoginSeverity,
                        userEvents.size() + " failed login attempts for user '" + userId + "' within "
                                + failedLoginWindowMinutes + " minute(s).",
                        userEvents, userEvents.get(0).getServiceName(), userId, "login-endpoint",
                        "Temporarily lock the account, prompt a password reset and verify whether the account is compromised.");
                markProcessed(userEvents);
            }
        });
    }

    /** Rule 2: Unauthorised Endpoint Access - repeated 401/403s from the same user/IP -> MEDIUM/HIGH alert. */
    private void evaluateUnauthorisedAccess() {
        Instant since = Instant.now().minus(unauthWindowMinutes, ChronoUnit.MINUTES);
        List<SecurityEvent> events = eventRepository
                .findAllByProcessedFalseAndEventTypeInAndTimestampAfter(
                        List.of("unauthorised_access_attempt", "invalid_token", "missing_or_malformed_token"), since);

        Map<String, List<SecurityEvent>> byActor = events.stream()
                .collect(Collectors.groupingBy(e -> e.getUserId() != null ? e.getUserId()
                        : e.getSourceIp() != null ? e.getSourceIp() : "unknown"));

        byActor.forEach((actor, actorEvents) -> {
            if (actorEvents.size() >= unauthThreshold) {
                String severity = actorEvents.size() >= unauthThreshold * 2 ? "HIGH" : unauthSeverity;
                alertService.raise(
                        "Unauthorised Endpoint Access", severity,
                        actorEvents.size() + " unauthorised/invalid-token requests from '" + actor + "' within "
                                + unauthWindowMinutes + " minute(s).",
                        actorEvents, actorEvents.get(0).getServiceName(), actorEvents.get(0).getUserId(),
                        actorEvents.get(0).getEndpoint(),
                        "Review access logs for this user/IP, confirm whether the token/credentials were stolen, and consider blocking the source.");
                markProcessed(actorEvents);
            }
        });
    }

    /** Rule 3: Abnormal Request Rate - a service receives an unusually high number of requests in a short window -> POSSIBLE_DOS alert. */
    private void evaluateAbnormalRequestRate() {
        Instant since = Instant.now().minus(rateWindowMinutes, ChronoUnit.MINUTES);
        List<SecurityEvent> events = eventRepository
                .findAllByProcessedFalseAndEventTypeAndTimestampAfter("request_received", since);

        Map<String, List<SecurityEvent>> byService = events.stream()
                .collect(Collectors.groupingBy(SecurityEvent::getServiceName));

        byService.forEach((service, serviceEvents) -> {
            if (serviceEvents.size() >= rateThreshold) {
                alertService.raise(
                        "Abnormal Request Rate", rateSeverity,
                        serviceEvents.size() + " requests to '" + service + "' within " + rateWindowMinutes
                                + " minute(s) - possible denial-of-service.",
                        serviceEvents, service, null, service,
                        "Inspect the source IP distribution of recent requests; consider tightening the gateway's rate limiter for this route.");
                markProcessed(serviceEvents);
            }
        });
    }

    /** Rule 4: Service Failure - a service returns repeated 5xx responses or fails its health check -> SERVICE_FAILURE alert. */
    private void evaluateServiceFailure() {
        Instant since = Instant.now().minus(failureWindowMinutes, ChronoUnit.MINUTES);
        List<SecurityEvent> events = eventRepository
                .findAllByProcessedFalseAndEventTypeInAndTimestampAfter(
                        List.of("dependency_failure", "service_health_check"), since);

        List<SecurityEvent> failures = events.stream()
                .filter(e -> e.getStatusCode() == null || e.getStatusCode() >= 500)
                .collect(Collectors.toList());

        Map<String, List<SecurityEvent>> byService = failures.stream()
                .collect(Collectors.groupingBy(SecurityEvent::getServiceName));

        byService.forEach((service, serviceEvents) -> {
            if (serviceEvents.size() >= failureThreshold) {
                alertService.raise(
                        "Service Failure", failureSeverity,
                        serviceEvents.size() + " failures/unhealthy checks for '" + service + "' within "
                                + failureWindowMinutes + " minute(s).",
                        serviceEvents, service, null, service,
                        "Check the service's container logs and dependencies; the circuit breaker should already be preventing cascading failure.");
                markProcessed(serviceEvents);
            }
        });
    }

    private void markProcessed(List<SecurityEvent> events) {
        events.forEach(e -> e.setProcessed(true));
        eventRepository.saveAll(events);
        log.info("event=rule_evaluated matchedEvents={}", events.size());
    }
}
