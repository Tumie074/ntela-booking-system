package com.booking.soc.service;

import com.booking.soc.dto.RagDtos.AskRequest;
import com.booking.soc.dto.RagDtos.AskResponse;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.neo4j.driver.Values;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Template-based GraphRAG (spec 5.2 Option C). No LLM is used: the question
 * is matched to one of a fixed set of intents with simple keyword rules, the
 * matching Cypher query is run against the knowledge graph, and the result
 * rows are substituted directly into a sentence template. The spec explicitly
 * allows this ("acceptable if the answer is clearly generated from the
 * graph"), and it keeps the whole system runnable offline in Docker with no
 * external API key.
 *
 * Covers the "Required Graph Queries" (spec 4.4) and the example RAG
 * questions (spec 5.1):
 *   - alerts affecting a service / which services are affected by an incident
 *   - users linked to (repeated) failed logins / high-severity alerts
 *   - threats linked to a service
 *   - controls that mitigate a threat/alert
 *   - dependency impact if a service fails
 *   - why a specific alert was created + what should be done about it
 */
@Service
public class RagService {

    private static final List<String> KNOWN_SERVICES = List.of(
            "api-gateway", "user-service", "resource-service", "booking-service", "eureka-server");
    private static final List<String> KNOWN_THREATS = List.of(
            "Credential Attack", "Unauthorised Access Attempt", "Denial of Service", "Service Disruption");

    private final Driver driver;

    public RagService(Driver driver) {
        this.driver = driver;
    }

    public AskResponse ask(AskRequest req) {
        String q = req.question().toLowerCase(Locale.ROOT);

        if (q.matches(".*alert[- ].*") && (q.contains("why") || q.contains("created") || q.matches(".*\\balert-[a-f0-9-]+\\b.*"))) {
            return explainAlert(req.question(), extractAlertId(req.question()));
        }
        if (q.contains("control") || q.contains("mitigat")) {
            return controlsForThreat(req.question(), extractService(q), extractThreat(q));
        }
        if (q.contains("depend") || q.contains("impact") || q.contains("fail")) {
            return dependencyImpact(req.question(), extractService(q));
        }
        if (q.contains("failed login") || q.contains("repeated login")) {
            return usersLinkedToFailedLogins(req.question());
        }
        if (q.contains("high-severity") || q.contains("high severity")) {
            return usersLinkedToHighSeverityAlerts(req.question());
        }
        if (q.contains("threat")) {
            return threatsForService(req.question(), extractService(q));
        }
        if (q.contains("response") || q.contains("procedure") || q.contains("should be done") || q.contains("should be followed")) {
            return responseProcedure(req.question(), extractThreat(q));
        }
        // Default: alerts affecting a service, or if no service named, the most recent alerts overall.
        return alertsAffectingService(req.question(), extractService(q));
    }

    // ---- Intents -----------------------------------------------------

    private AskResponse explainAlert(String question, String alertId) {
        try (Session session = driver.session()) {
            String cypher = alertId != null
                    ? """
                      MATCH (al:Alert {alertId: $alertId})
                      OPTIONAL MATCH (ev:Event)-[:CREATED_ALERT]->(al)
                      OPTIONAL MATCH (al)-[:INDICATES]->(t:Threat)
                      OPTIONAL MATCH (c:Control)-[:MITIGATES]->(t)
                      OPTIONAL MATCH (d:Document)-[:SUPPORTS_RESPONSE_TO]->(t)
                      RETURN al, collect(DISTINCT ev.eventId) AS events, t.name AS threat,
                             collect(DISTINCT c.name) AS controls, collect(DISTINCT d.title) AS docs
                      """
                    : """
                      MATCH (al:Alert)
                      OPTIONAL MATCH (ev:Event)-[:CREATED_ALERT]->(al)
                      OPTIONAL MATCH (al)-[:INDICATES]->(t:Threat)
                      OPTIONAL MATCH (c:Control)-[:MITIGATES]->(t)
                      OPTIONAL MATCH (d:Document)-[:SUPPORTS_RESPONSE_TO]->(t)
                      RETURN al, collect(DISTINCT ev.eventId) AS events, t.name AS threat,
                             collect(DISTINCT c.name) AS controls, collect(DISTINCT d.title) AS docs
                      ORDER BY al.timestamp DESC LIMIT 1
                      """;

            Record r = alertId != null
                    ? session.run(cypher, Values.parameters("alertId", alertId)).list().stream().findFirst().orElse(null)
                    : session.run(cypher).list().stream().findFirst().orElse(null);

            if (r == null) {
                return notFound(question, "explain_alert", "No alerts have been raised yet, so there is nothing to explain.");
            }

            var al = r.get("al").asNode();
            List<String> events = r.get("events").asList(Value::asString);
            String threat = r.get("threat").isNull() ? null : r.get("threat").asString();
            // The Neo4j driver returns immutable lists (singletonList/unmodifiableList), so
            // copy into a mutable ArrayList before removing nulls - calling removeIf directly
            // on the driver's list throws UnsupportedOperationException.
            List<String> controls = new ArrayList<>(r.get("controls").asList(v -> v.isNull() ? null : v.asString()));
            controls.removeIf(c -> c == null);
            List<String> docs = new ArrayList<>(r.get("docs").asList(v -> v.isNull() ? null : v.asString()));
            docs.removeIf(d -> d == null);

            List<String> nodes = new ArrayList<>();
            nodes.add("Alert{" + al.get("alertId").asString() + "}");
            events.forEach(e -> nodes.add("Event{" + e + "}"));
            if (threat != null) nodes.add("Threat{" + threat + "}");
            controls.forEach(c -> nodes.add("Control{" + c + "}"));

            List<String> rels = new ArrayList<>();
            events.forEach(e -> rels.add("(Event " + e + ")-[:CREATED_ALERT]->(Alert " + al.get("alertId").asString() + ")"));
            if (threat != null) rels.add("(Alert " + al.get("alertId").asString() + ")-[:INDICATES]->(Threat " + threat + ")");
            controls.forEach(c -> rels.add("(Control " + c + ")-[:MITIGATES]->(Threat " + threat + ")"));

            String controlList = controls.isEmpty() ? "no documented controls yet" : String.join(", ", controls);
            String answer = String.format(
                    "Alert %s was created by rule \"%s\" (severity %s) because of %d matching event(s) (%s). "
                            + "%sDetails: %s Recommended controls are: %s. %s",
                    al.get("alertId").asString(), al.get("ruleName").asString(), al.get("severity").asString(),
                    events.size(), String.join(", ", events),
                    threat != null ? "This is linked to the threat \"" + threat + "\". " : "",
                    al.get("description").isNull() ? "unknown" : al.get("description").asString(),
                    controlList,
                    al.get("recommendedAction").isNull() ? "" : al.get("recommendedAction").asString());

            return new AskResponse(question, "explain_alert", nodes, rels, docs,
                    "Cypher traversal: Event -[:CREATED_ALERT]-> Alert -[:INDICATES]-> Threat <-[:MITIGATES]- Control, Document -[:SUPPORTS_RESPONSE_TO]-> Threat",
                    answer);
        }
    }

    private AskResponse controlsForThreat(String question, String service, String threat) {
        try (Session session = driver.session()) {
            String cypher = threat != null
                    ? "MATCH (c:Control)-[:MITIGATES]->(t:Threat {name: $threat}) RETURN t.name AS threat, collect(c.name) AS controls"
                    : service != null
                        ? "MATCH (c:Control)-[:MITIGATES]->(t:Threat)-[:TARGETS]->(s:Service {name: $service}) RETURN t.name AS threat, collect(DISTINCT c.name) AS controls"
                        : "MATCH (c:Control)-[:MITIGATES]->(t:Threat) RETURN t.name AS threat, collect(c.name) AS controls LIMIT 1";

            var records = threat != null
                    ? session.run(cypher, Values.parameters("threat", threat)).list()
                    : service != null
                        ? session.run(cypher, Values.parameters("service", service)).list()
                        : session.run(cypher).list();

            if (records.isEmpty()) {
                return notFound(question, "controls_for_threat", "No matching threat/control relationships were found in the graph.");
            }

            List<String> nodes = new ArrayList<>();
            List<String> rels = new ArrayList<>();
            StringBuilder answer = new StringBuilder();
            for (Record r : records) {
                String t = r.get("threat").asString();
                List<String> controls = r.get("controls").asList(Value::asString);
                nodes.add("Threat{" + t + "}");
                controls.forEach(c -> {
                    nodes.add("Control{" + c + "}");
                    rels.add("(Control " + c + ")-[:MITIGATES]->(Threat " + t + ")");
                });
                answer.append(t).append(" is mitigated by: ").append(String.join(", ", controls)).append(". ");
            }

            return new AskResponse(question, "controls_for_threat", nodes, rels, List.of(),
                    "Cypher traversal: Control -[:MITIGATES]-> Threat" + (service != null ? " -[:TARGETS]-> Service" : ""),
                    answer.toString().trim());
        }
    }

    private AskResponse dependencyImpact(String question, String service) {
        if (service == null) {
            return notFound(question, "dependency_impact", "Name one of the services (api-gateway, user-service, resource-service, booking-service, eureka-server) to check its dependency impact.");
        }
        try (Session session = driver.session()) {
            var dependents = session.run(
                    "MATCH (dependent:Service)-[:DEPENDS_ON]->(s:Service {name: $service}) RETURN dependent.name AS name",
                    Values.parameters("service", service)).list();
            var dependsOn = session.run(
                    "MATCH (s:Service {name: $service})-[:DEPENDS_ON]->(dep:Service) RETURN dep.name AS name",
                    Values.parameters("service", service)).list();

            List<String> nodes = new ArrayList<>();
            List<String> rels = new ArrayList<>();
            nodes.add("Service{" + service + "}");
            List<String> dependentNames = dependents.stream().map(r -> r.get("name").asString()).toList();
            List<String> dependsOnNames = dependsOn.stream().map(r -> r.get("name").asString()).toList();
            dependentNames.forEach(d -> { nodes.add("Service{" + d + "}"); rels.add("(Service " + d + ")-[:DEPENDS_ON]->(Service " + service + ")"); });
            dependsOnNames.forEach(d -> { nodes.add("Service{" + d + "}"); rels.add("(Service " + service + ")-[:DEPENDS_ON]->(Service " + d + ")"); });

            String answer = dependentNames.isEmpty()
                    ? "No other service directly depends on " + service + " in the graph, so its failure would not cascade to another domain service (though the API Gateway would no longer be able to route to it)."
                    : "If " + service + " fails, the following service(s) would be directly affected because they depend on it: " + String.join(", ", dependentNames)
                      + ". " + service + " itself has resilience (circuit breaker/retry) protecting its own calls to: " + (dependsOnNames.isEmpty() ? "none." : String.join(", ", dependsOnNames) + ".");

            return new AskResponse(question, "dependency_impact", nodes, rels, List.of(),
                    "Cypher traversal: Service -[:DEPENDS_ON]-> Service (both directions from the named service)", answer);
        }
    }

    private AskResponse usersLinkedToFailedLogins(String question) {
        try (Session session = driver.session()) {
            var records = session.run("""
                MATCH (u:User)-[:TRIGGERED]->(ev:Event {eventType: 'failed_login_attempt'})
                RETURN u.username AS user, count(ev) AS failures
                ORDER BY failures DESC
                """).list();

            if (records.isEmpty()) {
                return notFound(question, "users_failed_logins", "No failed_login_attempt events have been linked to a user in the graph yet.");
            }
            List<String> nodes = new ArrayList<>();
            List<String> rels = new ArrayList<>();
            StringBuilder answer = new StringBuilder("Users linked to failed logins: ");
            for (Record r : records) {
                String user = r.get("user").asString();
                long failures = r.get("failures").asLong();
                nodes.add("User{" + user + "}");
                rels.add("(User " + user + ")-[:TRIGGERED]->(Event failed_login_attempt) x" + failures);
                answer.append(user).append(" (").append(failures).append("), ");
            }
            return new AskResponse(question, "users_failed_logins", nodes, rels, List.of(),
                    "Cypher traversal: User -[:TRIGGERED]-> Event {eventType: 'failed_login_attempt'}, grouped by user",
                    answer.substring(0, answer.length() - 2) + ".");
        }
    }

    private AskResponse usersLinkedToHighSeverityAlerts(String question) {
        try (Session session = driver.session()) {
            var records = session.run("""
                MATCH (u:User)-[:TRIGGERED]->(ev:Event)-[:CREATED_ALERT]->(al:Alert {severity: 'HIGH'})
                RETURN DISTINCT u.username AS user, collect(DISTINCT al.alertId) AS alerts
                """).list();

            if (records.isEmpty()) {
                return notFound(question, "users_high_severity_alerts", "No user is currently linked to a HIGH-severity alert in the graph.");
            }
            List<String> nodes = new ArrayList<>();
            List<String> rels = new ArrayList<>();
            StringBuilder answer = new StringBuilder("Users linked to HIGH-severity alerts: ");
            for (Record r : records) {
                String user = r.get("user").asString();
                List<String> alerts = r.get("alerts").asList(Value::asString);
                nodes.add("User{" + user + "}");
                alerts.forEach(a -> { nodes.add("Alert{" + a + "}"); rels.add("(User " + user + ") ... -[:CREATED_ALERT]->(Alert " + a + ")"); });
                answer.append(user).append(" -> ").append(String.join(", ", alerts)).append("; ");
            }
            return new AskResponse(question, "users_high_severity_alerts", nodes, rels, List.of(),
                    "Cypher traversal: User -[:TRIGGERED]-> Event -[:CREATED_ALERT]-> Alert {severity: 'HIGH'}", answer.toString().trim());
        }
    }

    private AskResponse threatsForService(String question, String service) {
        try (Session session = driver.session()) {
            String cypher = service != null
                    ? "MATCH (t:Threat)-[:TARGETS]->(s:Service {name: $service}) RETURN t.name AS threat, t.description AS description"
                    : "MATCH (t:Threat) RETURN t.name AS threat, t.description AS description";
            var records = service != null
                    ? session.run(cypher, Values.parameters("service", service)).list()
                    : session.run(cypher).list();

            if (records.isEmpty()) {
                return notFound(question, "threats_for_service", "No threat has been linked to that service yet (none has triggered an alert against it).");
            }
            List<String> nodes = new ArrayList<>();
            List<String> rels = new ArrayList<>();
            StringBuilder answer = new StringBuilder();
            for (Record r : records) {
                String t = r.get("threat").asString();
                nodes.add("Threat{" + t + "}");
                if (service != null) rels.add("(Threat " + t + ")-[:TARGETS]->(Service " + service + ")");
                answer.append(t).append(": ").append(r.get("description").asString()).append(" ");
            }
            return new AskResponse(question, "threats_for_service", nodes, rels, List.of(),
                    "Cypher traversal: Threat -[:TARGETS]-> Service" + (service == null ? " (all threats, no service named)" : ""),
                    answer.toString().trim());
        }
    }

    private AskResponse responseProcedure(String question, String threat) {
        try (Session session = driver.session()) {
            String cypher = threat != null
                    ? "MATCH (d:Document)-[:SUPPORTS_RESPONSE_TO]->(t:Threat {name: $threat}) RETURN d.title AS title, d.summary AS summary, t.name AS threat"
                    : "MATCH (d:Document)-[:SUPPORTS_RESPONSE_TO]->(t:Threat) RETURN d.title AS title, d.summary AS summary, t.name AS threat LIMIT 1";
            var records = threat != null
                    ? session.run(cypher, Values.parameters("threat", threat)).list()
                    : session.run(cypher).list();

            if (records.isEmpty()) {
                return notFound(question, "response_procedure", "No response-supporting document is linked to that threat in the graph yet.");
            }
            Record r = records.get(0);
            String title = r.get("title").asString();
            String t = r.get("threat").asString();
            List<String> nodes = List.of("Document{" + title + "}", "Threat{" + t + "}");
            List<String> rels = List.of("(Document " + title + ")-[:SUPPORTS_RESPONSE_TO]->(Threat " + t + ")");
            String answer = "For " + t + ", follow \"" + title + "\": " + r.get("summary").asString();
            return new AskResponse(question, "response_procedure", nodes, rels, List.of(title),
                    "Cypher traversal: Document -[:SUPPORTS_RESPONSE_TO]-> Threat", answer);
        }
    }

    private AskResponse alertsAffectingService(String question, String service) {
        try (Session session = driver.session()) {
            String cypher = service != null
                    ? "MATCH (al:Alert)-[:AFFECTS]->(s:Service {name: $service}) RETURN al ORDER BY al.timestamp DESC LIMIT 10"
                    : "MATCH (al:Alert) RETURN al ORDER BY al.timestamp DESC LIMIT 10";
            var records = service != null
                    ? session.run(cypher, Values.parameters("service", service)).list()
                    : session.run(cypher).list();

            if (records.isEmpty()) {
                return notFound(question, "alerts_affecting_service",
                        service != null ? "No alerts currently affect " + service + "." : "No alerts have been raised yet.");
            }
            List<String> nodes = new ArrayList<>();
            List<String> rels = new ArrayList<>();
            StringBuilder answer = new StringBuilder(service != null ? "Alerts affecting " + service + ": " : "Most recent alerts: ");
            for (Record r : records) {
                var al = r.get("al").asNode();
                nodes.add("Alert{" + al.get("alertId").asString() + "}");
                if (service != null) rels.add("(Alert " + al.get("alertId").asString() + ")-[:AFFECTS]->(Service " + service + ")");
                answer.append(al.get("alertId").asString()).append(" [").append(al.get("ruleName").asString())
                        .append(", ").append(al.get("severity").asString()).append("], ");
            }
            return new AskResponse(question, "alerts_affecting_service", nodes, rels, List.of(),
                    "Cypher traversal: Alert -[:AFFECTS]-> Service", answer.substring(0, answer.length() - 2) + ".");
        }
    }

    private AskResponse notFound(String question, String intent, String message) {
        return new AskResponse(question, intent, List.of(), List.of(), List.of(), "Cypher query returned no rows", message);
    }

    // ---- Entity extraction (keyword matching, no NLP library needed) ----

    private String extractService(String q) {
        // Accept "resource-service", "resource service", or just "resource".
        String norm = q.replace('-', ' ');
        for (String s : KNOWN_SERVICES) {
            String spaced = s.replace('-', ' ');
            String shortName = s.substring(0, s.indexOf('-'));
            if (norm.contains(spaced) || norm.matches(".*\\b" + shortName + "\\b.*")) {
                return s;
            }
        }
        return null;
    }

    private String extractThreat(String q) {
        return KNOWN_THREATS.stream().filter(t -> q.contains(t.toLowerCase(Locale.ROOT))).findFirst().orElse(null);
    }

    private String extractAlertId(String question) {
        var m = java.util.regex.Pattern.compile("alert-[a-f0-9-]+").matcher(question.toLowerCase(Locale.ROOT));
        return m.find() ? m.group() : null;
    }
}