# ITRI623 Phase 2 — Technical Report

## Online Booking System — SOC Event Collection, Detection Rules, Knowledge Graph and RAG

## 1. Overview

Phase 2 adds a fifth service, **soc-service** (port 8084), to the Phase 1
system without changing any Phase 1 service's own business logic. It:

1. collects structured security/operational events from the API Gateway,
   user-service, resource-service and booking-service;
2. evaluates four threat-detection rules against stored events and raises
   alerts;
3. mirrors application structure, events, alerts and static security
   knowledge into a Neo4j knowledge graph;
4. answers natural-language security questions through a template-based
   GraphRAG endpoint.

soc-service follows the same conventions as every Phase 1 service: Spring
Boot, registered with Eureka, routed through the API Gateway at
`/api/soc/**`, owns its own database (`socdb`, Database-per-Service), and
is built/deployed the same way via Docker Compose.

## 2. SOC Event Collection Layer (spec 3.1)

Each of the four Phase 1 services already logged structured `event=` lines
via SLF4J in Phase 1. In Phase 2, a small `SocEventPublisher` component was
added to each service (`client/SocEventPublisher.java`) that posts the same
event, as JSON, to `POST /api/soc/events` on soc-service — **alongside**,
not instead of, the existing SLF4J logging. The call is fire-and-forget
(2s timeout, errors only logged) so a slow or unreachable soc-service can
never add latency or failures to a real user request.

Events are collected from:
- **the API Gateway** — every request (`request_received`) and every
  401/403 rejection (`unauthorised_access_attempt`), published from
  `LoggingGlobalFilter`;
- **user-service (authentication)** — `login_success` and
  `failed_login_attempt`, published from `AuthController`;
- **resource-service** — `resource_created` and
  `resource_availability_changed`, published from `ResourceController`;
- **booking-service** — `booking_created`, published from
  `BookingController`, and `dependency_failure` (the circuit-breaker
  fallback), published from `ResourceServiceClient`;
- **container/service health information** — `soc-service`'s own
  `HealthPoller` polls each service's `/actuator/health` every 30s via the
  Eureka-resolved load balancer and records a `service_health_check` event.

Each stored event (`SecurityEvent` entity, table `security_event` in
`socdb`) matches the spec's schema exactly: `eventId`, `timestamp`,
`serviceName`, `eventType`, `severity`, `userId`, `sourceIp`, `endpoint`,
`httpMethod`, `statusCode`, `message`, `correlationId`, `affectedEntity`.

## 3. SOC Storage (spec 3.2)

Events and alerts are stored in a dedicated PostgreSQL database, `socdb`,
owned exclusively by soc-service (consistent with the Database-per-Service
pattern from Phase 1). They can also be inspected live via
`GET /api/soc/events` and `GET /api/soc/alerts` (through the gateway), and
through the "SOC / Knowledge Graph" tab added to the frontend.

## 4. Threat Detection Rules (spec 3.3)

`RuleEngine` runs every 15 seconds, scans un-processed stored events, and
raises an alert when a rule's condition is met. Matched events are then
marked `processed` so the same burst is not re-alerted, while a *new* burst
afterwards still raises a fresh alert. Four rules are implemented (the spec
requires at least three):

| Rule | Purpose | Input events | Detection condition | Severity | Generated alert | Recommended response |
|---|---|---|---|---|---|---|
| **Multiple Failed Logins** | Detect credential-guessing/stuffing attacks | `failed_login_attempt` | ≥5 events for the same `userId` within 5 minutes | HIGH | "N failed login attempts for user X within 5 minutes" | Lock the account, prompt a password reset, verify compromise |
| **Unauthorised Endpoint Access** | Detect stolen/expired tokens or access-control probing | `unauthorised_access_attempt`, `invalid_token`, `missing_or_malformed_token` | ≥3 events for the same user/IP within 5 minutes (escalates to HIGH at ≥6) | MEDIUM/HIGH | "N unauthorised/invalid-token requests from X within 5 minutes" | Review access logs, confirm token/credential compromise, consider blocking source |
| **Abnormal Request Rate** | Detect possible denial-of-service | `request_received` | ≥20 requests to the same service within 1 minute | MEDIUM | "N requests to service X within 1 minute — possible denial-of-service" | Inspect source IP distribution, tighten the gateway's rate limiter |
| **Service Failure** | Detect cascading/dependency failure | `dependency_failure`, `service_health_check` with status ≥500 | ≥3 failures for the same service within 5 minutes | HIGH | "N failures/unhealthy checks for service X within 5 minutes" | Check container logs and dependencies; circuit breaker should already be containing it |

Thresholds, windows and severities are all configurable in
`soc-service/application.yml` under `soc.rules.*`.

## 5. Alert Service (spec 3.4)

`AlertService` raises alerts (table `alert` in `socdb`), matching the
spec's schema exactly: `alertId`, `timestamp`, `ruleName`, `severity`,
`status`, `description`, `relatedEvents`, `affectedService`,
`affectedUser`, `affectedEntity`, `recommendedAction`. Every alert is also
mirrored into Neo4j (`GraphWriter.writeAlert`) and surfaced on the
frontend's "SOC / Knowledge Graph" tab and `GET /api/soc/alerts`.

## 6. Knowledge Graph (spec section 4)

Neo4j (`neo4j:5.24-community`, Bolt on 7687, Browser on 7474, credentials
`neo4j` / `socgraphpassword`) is written to directly with Cypher via the
Neo4j Java driver (`GraphWriter`, `GraphSeeder`), so every write and every
RAG retrieval query is explicit and explainable rather than hidden behind
an ORM.

**Node types used:** User, Service, Endpoint, Event, Alert, Threat,
Control, Document (the spec's full list — Asset, Incident, Vulnerability,
Role — is supported by the schema but this submission's demo workflow only
populates the subset actually exercised by the booking-system scenario).

**Relationship types used:** `TRIGGERED` (User→Event), `GENERATED_BY`
(Event→Service), `TARGETED` (Event→Endpoint), `CREATED_ALERT`
(Event→Alert), `INDICATES` (Alert→Threat), `TARGETS` (Threat→Service),
`AFFECTS` (Alert→Service), `MITIGATES` (Control→Threat), `DEPENDS_ON`
(Service→Service), `EXPOSES` (Service→Endpoint), `DESCRIBES` and
`SUPPORTS_RESPONSE_TO` (Document→Threat).

**Data sources (spec 4.3 requires at least three):**
1. **Application structure from Phase 1** — `GraphSeeder` seeds the five
   services, their key endpoints (`EXPOSES`), and `booking-service
   -[:DEPENDS_ON]-> resource-service` on startup.
2. **Events/alerts generated in Phase 2** — written continuously at
   runtime: `GraphWriter.writeEvent` on every ingested event,
   `GraphWriter.writeAlert` on every raised alert.
3. **Security knowledge from formal documentation** — `GraphSeeder` also
   seeds four Threat nodes, each tagged with its MITRE ATT&CK technique
   (e.g. Credential Attack → T1110, https://attack.mitre.org/techniques/T1110/),
   six Control nodes with `MITIGATES` relationships, and a Document node
   ("MITRE ATT&CK T1110 — Brute Force") linked via `DESCRIBES` and
   `SUPPORTS_RESPONSE_TO`.

All writes use `MERGE`, so re-running the seed (e.g. on every service
restart) is idempotent and never duplicates nodes.

### Example traceability (spec section 12's closing goal)

```
user-22 -[:TRIGGERED]-> evt-101..105 (failed_login_attempt)
evt-101..105 -[:CREATED_ALERT]-> alert-501 (Multiple Failed Logins, HIGH)
alert-501 -[:INDICATES]-> Credential Attack
Credential Attack <-[:MITIGATES]- Account Lockout, Multi-Factor Authentication, Password Reset Procedure
"MITRE ATT&CK T1110 — Brute Force" -[:SUPPORTS_RESPONSE_TO]-> Credential Attack
```

This is exactly the chain the spec's worked example describes, and is what
the RAG endpoint retrieves and explains when asked "why was this alert
created?".

## 7. RAG over the Knowledge Graph (spec section 5)

**Pattern used: Option C, template-based GraphRAG.** No LLM is called. A
question is matched to one of eight intents by keyword rules
(`RagService.ask`), the matching Cypher query is run against Neo4j, and the
result rows are substituted into a fixed sentence template — exactly the
pattern the spec's own Option C example shows ("Alert {alertId} was created
by rule {ruleName}...").

This keeps the whole system runnable offline inside Docker Compose with no
external API key, while still being "clearly generated from the graph" as
the spec requires for a non-LLM submission.

**Supported intents / example questions** (`POST /api/soc/ask {"question": "..."}`):

| Intent | Example question | Cypher traversal |
|---|---|---|
| `explain_alert` | "Why was this alert created and what should be done?" | `Event -[:CREATED_ALERT]-> Alert -[:INDICATES]-> Threat <-[:MITIGATES]- Control`, `Document -[:SUPPORTS_RESPONSE_TO]-> Threat` |
| `controls_for_threat` | "What controls mitigate credential attacks?" | `Control -[:MITIGATES]-> Threat` |
| `dependency_impact` | "Which services are affected if resource-service fails?" | `Service -[:DEPENDS_ON]-> Service` (both directions) |
| `users_failed_logins` | "Which users are linked to repeated failed logins?" | `User -[:TRIGGERED]-> Event {eventType: 'failed_login_attempt'}` |
| `users_high_severity_alerts` | "Which users are linked to high-severity alerts?" | `User -[:TRIGGERED]-> Event -[:CREATED_ALERT]-> Alert {severity:'HIGH'}` |
| `threats_for_service` | "What threats target booking-service?" | `Threat -[:TARGETS]-> Service` |
| `response_procedure` | "What response procedure should be followed?" | `Document -[:SUPPORTS_RESPONSE_TO]-> Threat` |
| `alerts_affecting_service` (default) | "Show all alerts affecting resource-service" | `Alert -[:AFFECTS]-> Service` |

### Evidence shown with every answer (spec 5.3)

Every `AskResponse` includes `retrievedNodes`, `retrievedRelationships`,
`sourceDocuments` and `retrievalMethod` (a one-line statement of which
Cypher traversal produced the answer) alongside the generated `answer`
text, so the frontend can display the underlying evidence next to the
answer rather than just the answer in isolation.

## 8. Minimum Demonstration Scenario (spec section 7)

1. A user fails `/api/auth/login` five times within five minutes (easy to
   trigger from the frontend's Login panel).
2. `user-service` logs `failed_login_attempt` and publishes it to
   soc-service, which stores it in `socdb` and mirrors it into Neo4j as an
   `Event` linked to the `User` who triggered it.
3. `RuleEngine`'s "Multiple Failed Logins" rule fires on its next 15-second
   pass, raising a HIGH alert and mirroring it into Neo4j, linked to the
   five triggering events and to the "Credential Attack" threat.
4. The alert appears on the frontend's "SOC / Knowledge Graph" tab
   (`GET /api/soc/alerts`).
5. Asking the RAG box "Why was this alert created and what should be
   done?" retrieves the alert, its triggering events, the linked threat,
   the controls that mitigate it, and the MITRE-derived document, and
   returns a generated answer plus the underlying graph evidence.

## 9. Limitations

- Detection rules poll on a 15-second schedule rather than reacting
  instantly to each ingested event; this keeps the implementation simple
  and is acceptable for a demo-scale workload, but a production SOC would
  evaluate rules as events arrive (e.g. via a message queue).
- The RAG layer is template-based, not LLM-based (an explicit, agreed
  design choice for this submission — see section 7); it handles the
  question patterns the spec's own examples use well, but will not
  gracefully handle an open-ended question outside those patterns, falling
  back to "most recent alerts" instead.
- `soc-service`'s own event/alert ingestion endpoints are not JWT-protected
  (treated as an internal monitoring interface, consistent with the
  Swagger-docs passthrough routes already left open in Phase 1); a
  production deployment would restrict these to the SOC's own network or
  service identity.
