package com.booking.resource.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public class ResourceDtos {

    public record ResourceRequest(
            @NotBlank String name,
            @NotBlank String type,
            @NotBlank String location,
            @Min(1) int capacity
    ) {}

    public record ResourceResponse(
            Long id,
            String name,
            String type,
            String location,
            int capacity,
            boolean available
    ) {}
}
