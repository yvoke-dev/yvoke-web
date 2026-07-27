package de.palsoftware.yvoke.chat.api.model;

import jakarta.validation.constraints.Size;
import java.util.Map;

/** SEC-18: bounds on the title and settings map of a synced conversation. */
public record UpdateConversationRequest(@Size(max=500)String title,@Size(max=100)Map<String,Object>settings){}
