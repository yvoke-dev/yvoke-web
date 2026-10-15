package de.palsoftware.yvoke.rag.core.websearch;

/** A single grounded web search hit returned by {@code WebSearchService}. */
public record WebSearchResult(String title,String url,String snippet){}
