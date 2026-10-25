package com.booking.soc.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

public class EventDtos {

    /** Inbound shape posted by the other services (api-gateway, user-service, resource-service, booking-service). */
    public record EventRequest(
            @NotBlank String serviceName,
            @NotBlank String eventType,
            String severity,
            String userId,
            String sourceIp,
            String endpoint,
            String httpMethod,
            Integer statusCode,
            String message,
            String correlationId,
            String affectedEntity
    ) {}

    public record EventResponse(
            String eventId,
            Instant timestamp,
            String serviceName,
            String eventType,
            String severity,
            String userId,
            String sourceIp,
            String endpoint,
            String httpMethod,
            Integer statusCode,
            String message,
            String correlationId,
            String affectedEntity
    ) {}
}
