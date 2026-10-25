package com.booking.soc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Phase 2 SOC / Knowledge Graph service.
 *
 * Responsibilities (see docs/PHASE2_REPORT.md for the full write-up):
 *  - ingest structured security/operational events posted by the other
 *    Phase 1 services (api-gateway, user-service, resource-service, booking-service);
 *  - evaluate threat-detection rules against stored events and raise Alerts;
 *  - mirror application structure, events, alerts and static security
 *    knowledge (threats/controls/documents) into a Neo4j knowledge graph;
 *  - answer natural-language security questions via a template-based
 *    GraphRAG endpoint (Cypher query -> graph evidence -> templated answer).
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableScheduling
public class SocServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(SocServiceApplication.class, args);
    }
}
