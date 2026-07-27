package de.palsoftware.yvoke.chat.api.model;

import java.util.UUID;

public record FeedbackDto(UUID messageId,int rating,String comment){}
