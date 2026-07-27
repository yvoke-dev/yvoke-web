package de.palsoftware.yvoke.chat.core.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ModelPricing(UUID id,String modelName,BigDecimal promptPricePerMillion,BigDecimal completionPricePerMillion,BigDecimal cachedPricePerMillion,BigDecimal thoughtPricePerMillion,Instant updatedAt){}
