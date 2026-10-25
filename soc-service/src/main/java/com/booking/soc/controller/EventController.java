package com.booking.soc.controller;

import com.booking.soc.dto.EventDtos.EventRequest;
import com.booking.soc.dto.EventDtos.EventResponse;
import com.booking.soc.model.SecurityEvent;
import com.booking.soc.repository.SecurityEventRepository;
import com.booking.soc.service.EventIngestionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * SOC Event Collection Layer ingestion endpoint (spec 3.1). The other four
 * Phase 1 services (api-gateway, user-service, resource-service,
 * booking-service) each have a small SocEventPublisher that fires a request
 * here, alongside their existing SLF4J logging, whenever a security- or
 * operations-relevant thing happens.
 */
@RestController
@RequestMapping("/api/soc/events")
public class EventController {

    private final EventIngestionService ingestionService;
    private final SecurityEventRepository repository;

    public EventController(EventIngestionService ingestionService, SecurityEventRepository repository) {
        this.ingestionService = ingestionService;
        this.repository = repository;
    }

    private EventResponse toResponse(SecurityEvent e) {
        return new EventResponse(e.getEventId(), e.getTimestamp(), e.getServiceName(), e.getEventType(),
                e.getSeverity(), e.getUserId(), e.getSourceIp(), e.getEndpoint(), e.getHttpMethod(),
                e.getStatusCode(), e.getMessage(), e.getCorrelationId(), e.getAffectedEntity());
    }

    @PostMapping
    public ResponseEntity<EventResponse> ingest(@Valid @RequestBody EventRequest req) {
        SecurityEvent saved = ingestionService.ingest(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(saved));
    }

    @GetMapping
    public List<EventResponse> recent() {
        return repository.findTop100ByOrderByTimestampDesc().stream().map(this::toResponse).toList();
    }
}
