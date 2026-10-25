package com.booking.soc.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Matches the Phase 2 spec's ALERT schema (section 3.4): alertId, timestamp,
 * ruleName, severity, status, description, relatedEvents, affectedService,
 * affectedUser, affectedEntity, recommendedAction.
 */
@Entity
@Table(name = "alert")
public class Alert {

    @Id
    @Column(name = "alert_id", nullable = false, updatable = false)
    private String alertId;

    @Column(nullable = false)
    private Instant timestamp;

    @Column(name = "rule_name", nullable = false)
    private String ruleName;

    @Column(nullable = false)
    private String severity;

    @Column(nullable = false)
    private String status = "OPEN"; // OPEN, ACKNOWLEDGED, RESOLVED

    @Column(length = 1000)
    private String description;

    @ElementCollection
    @CollectionTable(name = "alert_related_events", joinColumns = @JoinColumn(name = "alert_id"))
    @Column(name = "event_id")
    private List<String> relatedEvents = new ArrayList<>();

    @Column(name = "affected_service")
    private String affectedService;

    @Column(name = "affected_user")
    private String affectedUser;

    @Column(name = "affected_entity")
    private String affectedEntity;

    @Column(name = "recommended_action", length = 1000)
    private String recommendedAction;

    public String getAlertId() { return alertId; }
    public void setAlertId(String alertId) { this.alertId = alertId; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }

    public String getRuleName() { return ruleName; }
    public void setRuleName(String ruleName) { this.ruleName = ruleName; }

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<String> getRelatedEvents() { return relatedEvents; }
    public void setRelatedEvents(List<String> relatedEvents) { this.relatedEvents = relatedEvents; }

    public String getAffectedService() { return affectedService; }
    public void setAffectedService(String affectedService) { this.affectedService = affectedService; }

    public String getAffectedUser() { return affectedUser; }
    public void setAffectedUser(String affectedUser) { this.affectedUser = affectedUser; }

    public String getAffectedEntity() { return affectedEntity; }
    public void setAffectedEntity(String affectedEntity) { this.affectedEntity = affectedEntity; }

    public String getRecommendedAction() { return recommendedAction; }
    public void setRecommendedAction(String recommendedAction) { this.recommendedAction = recommendedAction; }
}
