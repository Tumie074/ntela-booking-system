package com.booking.booking.dto;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public class BookingDtos {

    public record BookingRequest(
            @NotNull Long userId,
            @NotNull Long resourceId,
            @NotNull Instant startTime,
            @NotNull Instant endTime
    ) {}

    public record BookingResponse(
            Long id,
            Long userId,
            Long resourceId,
            Instant startTime,
            Instant endTime,
            String status
    ) {}

    // Mirrors ResourceResponse from resource-service; kept as a local DTO
    // since services must not share a code/database dependency directly.
    public record ResourceView(
            Long id,
            String name,
            String type,
            String location,
            int capacity,
            boolean available
    ) {}
}
