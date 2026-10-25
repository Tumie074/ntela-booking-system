package com.booking.soc.model;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Matches the Phase 2 spec's EVENT schema (section 3.1) field for field:
 * eventId, timestamp, serviceName, eventType, severity, userId, sourceIp,
 * endpoint, httpMethod, statusCode, message, correlationId, affectedEntity.
 *
 * This is the SOC Event Service's own database (socdb) - consistent with
 * the Database-per-Service pattern from Phase 1.
 */
@Entity
@Table(name = "security_event")
public class SecurityEvent {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private String eventId;

    @Column(nullable = false)
    private Instant timestamp;

    @Column(name = "service_name", nullable = false)
    private String serviceName;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(nullable = false)
    private String severity; // LOW, MEDIUM, HIGH

    @Column(name = "user_id")
    private String userId;

    @Column(name = "source_ip")
    private String sourceIp;

    private String endpoint;

    @Column(name = "http_method")
    private String httpMethod;

    @Column(name = "status_code")
    private Integer statusCode;

    @Column(length = 1000)
    private String message;

    @Column(name = "correlation_id")
    private String correlationId;

    @Column(name = "affected_entity")
    private String affectedEntity;

    /** Set once this event has been consumed by rule evaluation, so rules don't re-scan it forever. */
    @Column(name = "processed", nullable = false)
    private boolean processed = false;

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }

    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getSourceIp() { return sourceIp; }
    public void setSourceIp(String sourceIp) { this.sourceIp = sourceIp; }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public String getHttpMethod() { return httpMethod; }
    public void setHttpMethod(String httpMethod) { this.httpMethod = httpMethod; }

    public Integer getStatusCode() { return statusCode; }
    public void setStatusCode(Integer statusCode) { this.statusCode = statusCode; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }

    public String getAffectedEntity() { return affectedEntity; }
    public void setAffectedEntity(String affectedEntity) { this.affectedEntity = affectedEntity; }

    public boolean isProcessed() { return processed; }
    public void setProcessed(boolean processed) { this.processed = processed; }
}
