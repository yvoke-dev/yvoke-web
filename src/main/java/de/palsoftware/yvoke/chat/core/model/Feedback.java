package de.palsoftware.yvoke.chat.core.model;

import java.time.Instant;
import java.util.UUID;

public record Feedback(UUID id,UUID messageId,int rating,String comment,Instant createdAt,Instant updatedAt){}
