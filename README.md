# Online Booking System — Microservices + SOC Monitoring

An online booking system built as Spring Boot microservices (Phase 1), extended with a
Security Operations Centre (SOC) service, threat-detection rules, a Neo4j knowledge graph
and a GraphRAG question interface (Phase 2).

**Author:** Itumeleng Ntela — ITRI623

## Architecture

| Component | Port | Technology | Role |
|---|---|---|---|
| API Gateway | 8080 | Spring Cloud Gateway, Redis | Single entry point, JWT validation, rate limiting, request logging |
| Eureka Server | 8761 | Spring Cloud Netflix Eureka | Service registry / discovery |
| user-service | 8081 | Spring Boot, JPA, PostgreSQL | Registration, login, JWT issuing |
| resource-service | 8082 | Spring Boot, JPA, PostgreSQL | Bookable resources (CRUD, availability) |
| booking-service | 8083 | Spring Boot, JPA, PostgreSQL, Resilience4j | Bookings, circuit breaker + retry to resource-service |
| soc-service | 8084 | Spring Boot, JPA, Neo4j driver | Event ingestion, 4 detection rules, alerts, knowledge graph, RAG |
| Neo4j | 7474 / 7687 | Neo4j 5.24 Community | Knowledge graph |
| Frontend | 3001 | HTML/JS on nginx | UI + "SOC / Knowledge Graph" dashboard |

Each service owns its own PostgreSQL database (Database-per-Service pattern).

## Running it

Requires Docker and Docker Compose.

```bash
docker compose up --build
```

Then open:

- Frontend: http://localhost:3001
- API Gateway: http://localhost:8080
- Eureka dashboard: http://localhost:8761
- Neo4j Browser: http://localhost:7474

## API routes (via the gateway)

| Path | Service |
|---|---|
| `/api/auth/**`, `/api/users/**` | user-service |
| `/api/resources/**` | resource-service |
| `/api/bookings/**` | booking-service |
| `/api/soc/**` | soc-service (events, alerts, RAG) |
| `/docs/<service>/**` | each service's API docs |

## Documentation

- [`docs/PHASE2_REPORT.md`](docs/PHASE2_REPORT.md) — Phase 2 technical report
- [`docs/Deliverable_2_Evidence_Report.pdf`](docs/Deliverable_2_Evidence_Report.pdf) — Deliverable 2 evidence report
- [`docs/trigger-all-rules.ps1`](docs/trigger-all-rules.ps1) — script that triggers all four detection rules

## Note on credentials

The passwords and JWT secret in `docker-compose.yml` and the `application.yml` files are
local demo values only. Change them before deploying anywhere public.
