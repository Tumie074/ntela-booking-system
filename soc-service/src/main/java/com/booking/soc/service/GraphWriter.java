package com.booking.soc.service;

import com.booking.soc.model.Alert;
import com.booking.soc.model.SecurityEvent;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Writes application structure, events and alerts into the Neo4j knowledge
 * graph (spec section 4). Node types used: User, Service, Endpoint, Event,
 * Alert, Threat, Control, Vulnerability, Document (section 4.1). Relationship
 * types used: TRIGGERED, GENERATED_BY, TARGETED, CREATED_ALERT, INDICATES,
 * TARGETS, EXPLOITS, MITIGATES, DEPENDS_ON, EXPOSES, DESCRIBES,
 * SUPPORTS_RESPONSE_TO (section 4.2).
 *
 * All writes use MERGE rather than CREATE for anything that can repeat
 * (users, services, endpoints, threats, controls) so re-running the seed or
 * re-ingesting the same event id is idempotent.
 */
@Component
public class GraphWriter {

    private static final Logger log = LoggerFactory.getLogger(GraphWriter.class);
    private final Driver driver;

    public GraphWriter(Driver driver) {
        this.driver = driver;
    }

    public void writeEvent(SecurityEvent e) {
        try (Session session = driver.session()) {
            Map<String, Object> params = new HashMap<>();
            params.put("eventId", e.getEventId());
            params.put("timestamp", e.getTimestamp().toString());
            params.put("eventType", e.getEventType());
            params.put("severity", e.getSeverity());
            params.put("message", e.getMessage());
            params.put("statusCode", e.getStatusCode());
            params.put("serviceName", e.getServiceName());
            params.put("endpoint", e.getEndpoint());
            params.put("userId", e.getUserId());

            session.executeWrite(tx -> {
                tx.run("""
                    MERGE (ev:Event {eventId: $eventId})
                    SET ev.timestamp = $timestamp, ev.eventType = $eventType,
                        ev.severity = $severity, ev.message = $message, ev.statusCode = $statusCode
                    MERGE (s:Service {name: $serviceName})
                    MERGE (ev)-[:GENERATED_BY]->(s)
                    """, Values.parameters(
                        "eventId", params.get("eventId"), "timestamp", params.get("timestamp"),
                        "eventType", params.get("eventType"), "severity", params.get("severity"),
                        "message", params.get("message"), "statusCode", params.get("statusCode"),
                        "serviceName", params.get("serviceName")));

                if (e.getEndpoint() != null) {
                    tx.run("""
                        MATCH (ev:Event {eventId: $eventId})
                        MERGE (ep:Endpoint {path: $endpoint})
                        MERGE (ev)-[:TARGETED]->(ep)
                        """, Values.parameters("eventId", e.getEventId(), "endpoint", e.getEndpoint()));
                }
                if (e.getUserId() != null) {
                    tx.run("""
                        MATCH (ev:Event {eventId: $eventId})
                        MERGE (u:User {username: $userId})
                        MERGE (u)-[:TRIGGERED]->(ev)
                        """, Values.parameters("eventId", e.getEventId(), "userId", e.getUserId()));
                }
                return null;
            });
        } catch (Exception ex) {
            // Graph writes are best-effort: a Neo4j hiccup must never fail event
            // ingestion or rule evaluation, which both still work from socdb alone.
            log.error("event=graph_write_failed target=event eventId={} reason={}", e.getEventId(), ex.getMessage());
        }
    }

    public void writeAlert(Alert a) {
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("""
                    MERGE (al:Alert {alertId: $alertId})
                    SET al.timestamp = $timestamp, al.ruleName = $ruleName, al.severity = $severity,
                        al.status = $status, al.description = $description, al.recommendedAction = $recommendedAction
                    """, Values.parameters(
                        "alertId", a.getAlertId(), "timestamp", a.getTimestamp().toString(),
                        "ruleName", a.getRuleName(), "severity", a.getSeverity(), "status", a.getStatus(),
                        "description", a.getDescription(), "recommendedAction", a.getRecommendedAction()));

                for (String eventId : a.getRelatedEvents()) {
                    tx.run("""
                        MATCH (ev:Event {eventId: $eventId})
                        MATCH (al:Alert {alertId: $alertId})
                        MERGE (ev)-[:CREATED_ALERT]->(al)
                        """, Values.parameters("eventId", eventId, "alertId", a.getAlertId()));
                }
                if (a.getAffectedService() != null) {
                    tx.run("""
                        MATCH (al:Alert {alertId: $alertId})
                        MERGE (s:Service {name: $serviceName})
                        MERGE (al)-[:AFFECTS]->(s)
                        """, Values.parameters("alertId", a.getAlertId(), "serviceName", a.getAffectedService()));
                }
                // Link the alert to a Threat node matching its rule, so RAG queries like
                // "what controls mitigate this threat" have something to traverse to.
                String threatName = threatFor(a.getRuleName());
                if (threatName != null) {
                    tx.run("""
                        MATCH (al:Alert {alertId: $alertId})
                        MERGE (t:Threat {name: $threatName})
                        MERGE (al)-[:INDICATES]->(t)
                        """, Values.parameters("alertId", a.getAlertId(), "threatName", threatName));
                    if (a.getAffectedService() != null) {
                        tx.run("""
                            MATCH (t:Threat {name: $threatName})
                            MERGE (s:Service {name: $serviceName})
                            MERGE (t)-[:TARGETS]->(s)
                            """, Values.parameters("threatName", threatName, "serviceName", a.getAffectedService()));
                    }
                }
                return null;
            });
        } catch (Exception ex) {
            log.error("event=graph_write_failed target=alert alertId={} reason={}", a.getAlertId(), ex.getMessage());
        }
    }

    /** Maps each detection rule to the Threat concept it indicates, for the INDICATES relationship. */
    private String threatFor(String ruleName) {
        return switch (ruleName) {
            case "Multiple Failed Logins" -> "Credential Attack";
            case "Unauthorised Endpoint Access" -> "Unauthorised Access Attempt";
            case "Abnormal Request Rate" -> "Denial of Service";
            case "Service Failure" -> "Service Disruption";
            default -> null;
        };
    }
}
