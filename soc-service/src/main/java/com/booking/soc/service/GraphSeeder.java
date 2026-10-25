package com.booking.soc.service;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Seeds the knowledge graph on startup with the two "static" data sources
 * required by spec section 4.3:
 *   1. application structure from Phase 1 (services, endpoints, DEPENDS_ON);
 *   3. security knowledge from formal documentation (MITRE ATT&CK-derived
 *      Threat/Control/Vulnerability/Document nodes).
 * Source 2 (events/alerts generated in Phase 2) is added continuously at
 * runtime by GraphWriter as events are ingested and alerts are raised.
 *
 * Every write is a MERGE, so re-running this on every service restart is
 * safe and idempotent - it will never duplicate nodes.
 */
@Component
public class GraphSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(GraphSeeder.class);
    private final Driver driver;

    public GraphSeeder(Driver driver) {
        this.driver = driver;
    }

    @Override
    public void run(String... args) {
        try (Session session = driver.session()) {
            seedApplicationStructure(session);
            seedSecurityKnowledge(session);
            log.info("event=graph_seed_completed");
        } catch (Exception e) {
            // Non-fatal: the service still works (events/alerts still persist to
            // socdb) even if Neo4j isn't reachable yet at startup.
            log.error("event=graph_seed_failed reason={}", e.getMessage());
        }
    }

    private void seedApplicationStructure(Session session) {
        // Services, from the Phase 1 architecture diagram.
        session.executeWrite(tx -> {
            tx.run("""
                MERGE (gw:Service {name: 'api-gateway'})   SET gw.role  = 'API Gateway'
                MERGE (us:Service {name: 'user-service'})  SET us.role  = 'Authentication / user accounts'
                MERGE (rs:Service {name: 'resource-service'}) SET rs.role = 'Bookable resources'
                MERGE (bs:Service {name: 'booking-service'})  SET bs.role = 'Bookings'
                MERGE (eur:Service {name: 'eureka-server'})   SET eur.role = 'Service registry'
                MERGE (gw)-[:ROUTES_TO]->(us)
                MERGE (gw)-[:ROUTES_TO]->(rs)
                MERGE (gw)-[:ROUTES_TO]->(bs)
                MERGE (bs)-[:DEPENDS_ON]->(rs)
                """);

            // Key endpoints, with EXPOSES relationships (spec 4.2).
            tx.run("""
                MATCH (us:Service {name: 'user-service'})
                MERGE (e1:Endpoint {path: '/api/auth/login'})
                MERGE (e2:Endpoint {path: '/api/users'})
                MERGE (us)-[:EXPOSES]->(e1)
                MERGE (us)-[:EXPOSES]->(e2)
                """);
            tx.run("""
                MATCH (rs:Service {name: 'resource-service'})
                MERGE (e3:Endpoint {path: '/api/resources'})
                MERGE (rs)-[:EXPOSES]->(e3)
                """);
            tx.run("""
                MATCH (bs:Service {name: 'booking-service'})
                MERGE (e4:Endpoint {path: '/api/bookings'})
                MERGE (bs)-[:EXPOSES]->(e4)
                """);
            return null;
        });
    }

    private void seedSecurityKnowledge(Session session) {
        session.executeWrite(tx -> {
            // Threats, each linked to a MITRE ATT&CK technique (spec 4.3 item 3 example).
            tx.run("""
                MERGE (t1:Threat {name: 'Credential Attack'})
                  SET t1.description = 'Repeated attempts to guess or stuff credentials against a login endpoint.',
                      t1.mitreTechnique = 'T1110', t1.mitreUrl = 'https://attack.mitre.org/techniques/T1110/'
                MERGE (t2:Threat {name: 'Unauthorised Access Attempt'})
                  SET t2.description = 'Requests to a protected endpoint without a valid token, or with an invalid/expired one.',
                      t2.mitreTechnique = 'T1078', t2.mitreUrl = 'https://attack.mitre.org/techniques/T1078/'
                MERGE (t3:Threat {name: 'Denial of Service'})
                  SET t3.description = 'An abnormally high request rate aimed at exhausting a service.',
                      t3.mitreTechnique = 'T1498', t3.mitreUrl = 'https://attack.mitre.org/techniques/T1498/'
                MERGE (t4:Threat {name: 'Service Disruption'})
                  SET t4.description = 'Repeated failures or dependency outages that degrade or break a service.',
                      t4.mitreTechnique = 'T1499', t4.mitreUrl = 'https://attack.mitre.org/techniques/T1499/'
                """);

            // Controls, each mitigating one or more threats (MITIGATES).
            tx.run("""
                MERGE (c1:Control {name: 'Account Lockout'})
                  SET c1.description = 'Temporarily locks an account after repeated failed logins.'
                MERGE (c2:Control {name: 'Multi-Factor Authentication'})
                  SET c2.description = 'Requires a second factor beyond username/password.'
                MERGE (c3:Control {name: 'Password Reset Procedure'})
                  SET c3.description = 'Forces a credential reset when compromise is suspected.'
                MERGE (c4:Control {name: 'JWT Validation at Gateway'})
                  SET c4.description = 'The API Gateway rejects requests with a missing/invalid/expired token before they reach a service.'
                MERGE (c5:Control {name: 'Rate Limiting'})
                  SET c5.description = 'Redis-backed per-IP request rate limiter enforced at the API Gateway.'
                MERGE (c6:Control {name: 'Circuit Breaker + Retry'})
                  SET c6.description = 'Resilience4j circuit breaker/retry around inter-service calls, preventing cascading failure.'

                MERGE (t1:Threat {name: 'Credential Attack'})
                MERGE (t2:Threat {name: 'Unauthorised Access Attempt'})
                MERGE (t3:Threat {name: 'Denial of Service'})
                MERGE (t4:Threat {name: 'Service Disruption'})

                MERGE (c1)-[:MITIGATES]->(t1)
                MERGE (c2)-[:MITIGATES]->(t1)
                MERGE (c3)-[:MITIGATES]->(t1)
                MERGE (c4)-[:MITIGATES]->(t2)
                MERGE (c5)-[:MITIGATES]->(t3)
                MERGE (c6)-[:MITIGATES]->(t4)
                """);

            // A Document node describing the MITRE technique behind the credential-attack threat
            // (spec 4.3 item 3 / 4.2 DESCRIBES + SUPPORTS_RESPONSE_TO).
            tx.run("""
                MERGE (d1:Document {title: 'MITRE ATT&CK T1110 - Brute Force'})
                  SET d1.url = 'https://attack.mitre.org/techniques/T1110/',
                      d1.summary = 'Documents how adversaries use password guessing (T1110.001) and credential stuffing (T1110.004) to gain unauthorised access.'
                MERGE (t1:Threat {name: 'Credential Attack'})
                MERGE (c1:Control {name: 'Account Lockout'})
                MERGE (d1)-[:DESCRIBES]->(t1)
                MERGE (d1)-[:SUPPORTS_RESPONSE_TO]->(t1)
                MERGE (d1)-[:DESCRIBES]->(c1)
                """);

            // Spec 4.2: (:Document)-[:DESCRIBES]->(:Control). MITRE's T1110 page lists
            // multi-factor authentication and account-use policies as mitigations.
            tx.run("""
                MATCH (d1:Document {title: 'MITRE ATT&CK T1110 - Brute Force'})
                MATCH (c:Control) WHERE c.name IN ['Multi-Factor Authentication', 'Password Reset Procedure']
                MERGE (d1)-[:DESCRIBES]->(c)
                """);
            return null;
        });
    }
}