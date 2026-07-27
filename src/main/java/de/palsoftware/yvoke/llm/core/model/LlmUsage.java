package de.palsoftware.yvoke.llm.core.model;

public record LlmUsage(int promptTokens,int completionTokens,int totalTokens,int cachedTokens,int thoughtTokens){}
