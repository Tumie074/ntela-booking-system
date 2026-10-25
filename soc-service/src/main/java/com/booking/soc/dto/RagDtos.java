package com.booking.soc.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public class RagDtos {

    public record AskRequest(@NotBlank String question) {}

    public record AskResponse(
            String question,
            String intent,
            List<String> retrievedNodes,
            List<String> retrievedRelationships,
            List<String> sourceDocuments,
            String retrievalMethod,
            String answer
    ) {}
}
