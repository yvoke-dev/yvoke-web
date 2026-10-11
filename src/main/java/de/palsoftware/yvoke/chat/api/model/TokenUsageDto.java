package de.palsoftware.yvoke.chat.api.model;

public record TokenUsageDto(int prompt,int completion,int total,int cached,int thought){}
