package de.palsoftware.yvoke.chat.web;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ValidationResponse(@JsonProperty("plausible")boolean plausible,@JsonProperty("reason")String reason,@JsonProperty("suggestedPlaybookName")String suggestedPlaybookName){}
