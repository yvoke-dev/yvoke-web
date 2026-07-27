package de.palsoftware.yvoke.chat.api.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * SEC-18: the message batch is bounded so a desktop-sync client cannot post an unbounded payload
 * that exhausts memory. Each element is cascade-validated ({@code @Valid}).
 */
public record AppendMessagesRequest(@NotNull @Size(max=500,message="A sync batch may contain at most 500 messages")List<@Valid NewMessageDto>messages){}
