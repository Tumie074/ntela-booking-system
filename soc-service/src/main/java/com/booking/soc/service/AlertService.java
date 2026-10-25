package com.booking.soc.service;

import com.booking.soc.model.Alert;
import com.booking.soc.model.SecurityEvent;
import com.booking.soc.repository.AlertRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Alert Service (spec 3.4). Alerts are stored in the SOC Event Service's own
 * database and mirrored into Neo4j so they can be linked to the events,
 * threats and controls that explain them.
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    private final AlertRepository repository;
    private final GraphWriter graphWriter;

    public AlertService(AlertRepository repository, GraphWriter graphWriter) {
        this.repository = repository;
        this.graphWriter = graphWriter;
    }

    public Alert raise(String ruleName, String severity, String description, List<SecurityEvent> relatedEvents,
                        String affectedService, String affectedUser, String affectedEntity, String recommendedAction) {
        Alert alert = new Alert();
        alert.setAlertId("alert-" + UUID.randomUUID());
        alert.setTimestamp(Instant.now());
        alert.setRuleName(ruleName);
        alert.setSeverity(severity);
        alert.setStatus("OPEN");
        alert.setDescription(description);
        alert.setRelatedEvents(relatedEvents.stream().map(SecurityEvent::getEventId).toList());
        alert.setAffectedService(affectedService);
        alert.setAffectedUser(affectedUser);
        alert.setAffectedEntity(affectedEntity);
        alert.setRecommendedAction(recommendedAction);

        repository.save(alert);
        log.warn("event=alert_created alertId={} ruleName={} severity={} affectedService={} affectedUser={}",
                alert.getAlertId(), ruleName, severity, affectedService, affectedUser);

        graphWriter.writeAlert(alert);
        return alert;
    }
}
