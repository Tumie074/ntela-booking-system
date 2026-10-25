package com.booking.soc.config;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Low-level Neo4j Java driver bean. We talk to Neo4j directly with Cypher
 * (GraphWriter / GraphQueryService) rather than through Spring Data Neo4j,
 * so the exact Cypher used for graph writes and for the RAG retrieval
 * templates (spec section 5) is fully visible and explainable in the report.
 */
@Configuration
public class Neo4jConfig {

    @Bean(destroyMethod = "close")
    public Driver neo4jDriver(
            @Value("${neo4j.uri}") String uri,
            @Value("${neo4j.username}") String username,
            @Value("${neo4j.password}") String password) {
        return GraphDatabase.driver(uri, AuthTokens.basic(username, password));
    }
}
