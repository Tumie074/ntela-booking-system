package com.booking.soc.dto;

import java.time.Instant;
import java.util.List;

public class AlertDtos {

    public record AlertResponse(
            String alertId,
            Instant timestamp,
            String ruleName,
            String severity,
            String status,
            String description,
            List<String> relatedEvents,
            String affectedService,
            String affectedUser,
            String affectedEntity,
            String recommendedAction
    ) {}
}
