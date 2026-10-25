package com.booking.resource.controller;

import com.booking.resource.client.SocEventPublisher;
import com.booking.resource.dto.ResourceDtos.*;
import com.booking.resource.model.Resource;
import com.booking.resource.repository.ResourceRepository;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Standard REST CRUD for bookable resources.
 * Called directly by clients (via gateway) and internally by the Booking Service
 * to check availability before confirming a booking.
 */
@RestController
@RequestMapping("/api/resources")
public class ResourceController {

    private static final Logger log = LoggerFactory.getLogger(ResourceController.class);
    private final ResourceRepository repository;
    private final SocEventPublisher socEventPublisher;

    public ResourceController(ResourceRepository repository, SocEventPublisher socEventPublisher) {
        this.repository = repository;
        this.socEventPublisher = socEventPublisher;
    }

    private ResourceResponse toResponse(Resource r) {
        return new ResourceResponse(r.getId(), r.getName(), r.getType(), r.getLocation(), r.getCapacity(), r.isAvailable());
    }

    @GetMapping
    public List<ResourceResponse> getAll() {
        log.info("event=request_received endpoint=/api/resources method=GET");
        return repository.findAll().stream().map(this::toResponse).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<ResourceResponse> getById(@PathVariable Long id) {
        log.info("event=request_received endpoint=/api/resources/{} method=GET", id);
        return repository.findById(id)
                .map(r -> ResponseEntity.ok(toResponse(r)))
                .orElseGet(() -> {
                    log.warn("event=resource_not_found id={}", id);
                    return ResponseEntity.notFound().build();
                });
    }

    @PostMapping
    public ResponseEntity<ResourceResponse> create(@Valid @RequestBody ResourceRequest req) {
        log.info("event=request_received endpoint=/api/resources method=POST name={}", req.name());
        Resource r = new Resource();
        r.setName(req.name());
        r.setType(req.type());
        r.setLocation(req.location());
        r.setCapacity(req.capacity());
        r.setAvailable(true);
        repository.save(r);
        log.info("event=resource_created id={} name={}", r.getId(), r.getName());
        socEventPublisher.publish("resource_created", "LOW", "/api/resources", "POST", 201,
                "Resource created: " + r.getName(), r.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(r));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ResourceResponse> update(@PathVariable Long id, @Valid @RequestBody ResourceRequest req) {
        log.info("event=request_received endpoint=/api/resources/{} method=PUT", id);
        return repository.findById(id).map(r -> {
            r.setName(req.name());
            r.setType(req.type());
            r.setLocation(req.location());
            r.setCapacity(req.capacity());
            repository.save(r);
            log.info("event=resource_updated id={}", id);
            return ResponseEntity.ok(toResponse(r));
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PatchMapping("/{id}/availability")
    public ResponseEntity<ResourceResponse> setAvailability(@PathVariable Long id, @RequestParam boolean available) {
        return repository.findById(id).map(r -> {
            r.setAvailable(available);
            repository.save(r);
            // SOC-relevant business event: access/state change to a tracked asset
            log.info("event=resource_availability_changed id={} available={}", id, available);
            socEventPublisher.publish("resource_availability_changed", "LOW", "/api/resources/" + id + "/availability",
                    "PATCH", 200, "Resource " + id + " availability set to " + available, r.getName());
            return ResponseEntity.ok(toResponse(r));
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        log.info("event=request_received endpoint=/api/resources/{} method=DELETE", id);
        if (!repository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        repository.deleteById(id);
        log.info("event=resource_deleted id={}", id);
        return ResponseEntity.noContent().build();
    }
}
