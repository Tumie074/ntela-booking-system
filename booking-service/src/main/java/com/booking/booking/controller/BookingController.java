package com.booking.booking.controller;

import com.booking.booking.client.ResourceServiceClient;
import com.booking.booking.client.SocEventPublisher;
import com.booking.booking.dto.BookingDtos.*;
import com.booking.booking.model.Booking;
import com.booking.booking.repository.BookingRepository;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    private static final Logger log = LoggerFactory.getLogger(BookingController.class);

    private final BookingRepository repository;
    private final ResourceServiceClient resourceServiceClient;
    private final SocEventPublisher socEventPublisher;

    public BookingController(BookingRepository repository, ResourceServiceClient resourceServiceClient,
                              SocEventPublisher socEventPublisher) {
        this.repository = repository;
        this.resourceServiceClient = resourceServiceClient;
        this.socEventPublisher = socEventPublisher;
    }

    private BookingResponse toResponse(Booking b) {
        return new BookingResponse(b.getId(), b.getUserId(), b.getResourceId(), b.getStartTime(), b.getEndTime(), b.getStatus());
    }

    @GetMapping
    public List<BookingResponse> getAll() {
        log.info("event=request_received endpoint=/api/bookings method=GET");
        return repository.findAll().stream().map(this::toResponse).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<BookingResponse> getById(@PathVariable Long id) {
        return repository.findById(id)
                .map(b -> ResponseEntity.ok(toResponse(b)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/user/{userId}")
    public List<BookingResponse> getByUser(@PathVariable Long userId) {
        return repository.findByUserId(userId).stream().map(this::toResponse).toList();
    }

    /**
     * Demonstrates the full cross-service workflow (spec section 14):
     * validates the target resource exists & is available (via a resilient
     * call to resource-service) before persisting the booking, then flips
     * the resource to unavailable.
     */
    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody BookingRequest req) {
        log.info("event=request_received endpoint=/api/bookings method=POST userId={} resourceId={}",
                req.userId(), req.resourceId());

        Optional<ResourceView> resource = resourceServiceClient.getResource(req.resourceId());

        if (resource.isEmpty()) {
            log.warn("event=booking_rejected reason=resource_unavailable_or_unreachable resourceId={}", req.resourceId());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body("Could not verify resource availability - resource service may be down or resource does not exist");
        }

        if (!resource.get().available()) {
            log.warn("event=booking_rejected reason=resource_not_available resourceId={}", req.resourceId());
            return ResponseEntity.status(HttpStatus.CONFLICT).body("Resource is not available for booking");
        }

        Booking booking = new Booking();
        booking.setUserId(req.userId());
        booking.setResourceId(req.resourceId());
        booking.setStartTime(req.startTime());
        booking.setEndTime(req.endTime());
        booking.setStatus("CONFIRMED");
        repository.save(booking);

        // Business event of interest to a future SOC / knowledge graph:
        // links a User -> Booking -> Resource relationship.
        log.info("event=booking_created bookingId={} userId={} resourceId={} start={} end={}",
                booking.getId(), booking.getUserId(), booking.getResourceId(), booking.getStartTime(), booking.getEndTime());
        socEventPublisher.publish("booking_created", "LOW", String.valueOf(booking.getUserId()), "/api/bookings",
                "POST", 201, "Booking " + booking.getId() + " created for resource " + booking.getResourceId(),
                "resource-" + booking.getResourceId());

        // Complete the cross-service workflow: mark the resource unavailable now
        // that it is booked, so it cannot be double-booked by a second request.
        resourceServiceClient.setUnavailable(req.resourceId());

        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(booking));
    }

    @PutMapping("/{id}/cancel")
    public ResponseEntity<BookingResponse> cancel(@PathVariable Long id) {
        return repository.findById(id).map(b -> {
            b.setStatus("CANCELLED");
            repository.save(b);
            log.info("event=booking_cancelled bookingId={} resourceId={}", id, b.getResourceId());
            // Release the resource so it can be booked again.
            resourceServiceClient.setAvailable(b.getResourceId());
            return ResponseEntity.ok(toResponse(b));
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!repository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        repository.deleteById(id);
        log.info("event=booking_deleted bookingId={}", id);
        return ResponseEntity.noContent().build();
    }
}
