package com.booking.soc.service;

import com.booking.soc.dto.EventDtos.EventRequest;
import com.booking.soc.model.SecurityEvent;
import com.booking.soc.repository.SecurityEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class EventIngestionService {

    private static final Logger log = LoggerFactory.getLogger(EventIngestionService.class);

    private final SecurityEventRepository repository;
    private final GraphWriter graphWriter;

    public EventIngestionService(SecurityEventRepository repository, GraphWriter graphWriter) {
        this.repository = repository;
        this.graphWriter = graphWriter;
    }

    public SecurityEvent ingest(EventRequest req) {
        SecurityEvent event = new SecurityEvent();
        event.setEventId("evt-" + UUID.randomUUID());
        event.setTimestamp(Instant.now());
        event.setServiceName(req.serviceName());
        event.setEventType(req.eventType());
        event.setSeverity(req.severity() != null ? req.severity() : "LOW");
        event.setUserId(req.userId());
        event.setSourceIp(req.sourceIp());
        event.setEndpoint(req.endpoint());
        event.setHttpMethod(req.httpMethod());
        event.setStatusCode(req.statusCode());
        event.setMessage(req.message());
        event.setCorrelationId(req.correlationId());
        event.setAffectedEntity(req.affectedEntity());

        repository.save(event);
        log.info("event=soc_event_ingested eventId={} serviceName={} eventType={}",
                event.getEventId(), event.getServiceName(), event.getEventType());

        graphWriter.writeEvent(event);
        return event;
    }
}
