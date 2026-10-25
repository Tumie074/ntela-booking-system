package com.booking.soc.controller;

import com.booking.soc.dto.RagDtos.AskRequest;
import com.booking.soc.dto.RagDtos.AskResponse;
import com.booking.soc.service.RagService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Template-based GraphRAG endpoint (spec section 5). Try questions like:
 *   "Why was this alert created and what should be done?"
 *   "What controls mitigate credential attacks?"
 *   "Which services are affected if resource-service fails?"
 *   "Which users are linked to repeated failed logins?"
 *   "What threats target booking-service?"
 */
@RestController
@RequestMapping("/api/soc/ask")
public class RagController {

    private final RagService ragService;

    public RagController(RagService ragService) {
        this.ragService = ragService;
    }

    @PostMapping
    public AskResponse ask(@Valid @RequestBody AskRequest req) {
        return ragService.ask(req);
    }
}
