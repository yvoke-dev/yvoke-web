package de.palsoftware.yvoke.chat.api.model;

import de.palsoftware.yvoke.chat.core.model.Feedback;
import de.palsoftware.yvoke.chat.core.model.Message;
import java.time.Instant;
import java.util.UUID;

public record MessageDto(UUID id,String role,String content,Integer promptTokens,Integer completionTokens,Integer totalTokens,Integer cachedTokens,Integer thoughtTokens,Instant createdAt,Integer feedbackRating,String feedbackComment,String model){public static MessageDto from(Message m,Feedback feedback){return new MessageDto(m.id(),m.role(),m.content(),m.promptTokens(),m.completionTokens(),m.totalTokens(),m.cachedTokens(),m.thoughtTokens(),m.createdAt(),feedback!=null?feedback.rating():null,feedback!=null?feedback.comment():null,m.model());}}
