package com.booking.soc.controller;

import com.booking.soc.dto.AlertDtos.AlertResponse;
import com.booking.soc.model.Alert;
import com.booking.soc.repository.AlertRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Read-only view of raised alerts (spec 3.4) - also backs the SOC dashboard (section 13.1's "alerts shown in the dashboard" option). */
@RestController
@RequestMapping("/api/soc/alerts")
public class AlertController {

    private final AlertRepository repository;

    public AlertController(AlertRepository repository) {
        this.repository = repository;
    }

    private AlertResponse toResponse(Alert a) {
        return new AlertResponse(a.getAlertId(), a.getTimestamp(), a.getRuleName(), a.getSeverity(), a.getStatus(),
                a.getDescription(), a.getRelatedEvents(), a.getAffectedService(), a.getAffectedUser(),
                a.getAffectedEntity(), a.getRecommendedAction());
    }

    @GetMapping
    public List<AlertResponse> recent() {
        return repository.findTop100ByOrderByTimestampDesc().stream().map(this::toResponse).toList();
    }
}
