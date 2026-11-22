package de.palsoftware.yvoke.chat.api.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record FeedbackRequest(
        @NotNull @Min(-1) @Max(1) Integer rating,
        @Size(max = 4000, message = "comment must be at most 4000 characters") String comment) {}
