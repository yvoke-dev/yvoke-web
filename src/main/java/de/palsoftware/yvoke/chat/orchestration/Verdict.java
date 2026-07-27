package de.palsoftware.yvoke.chat.orchestration;

import java.util.List;

/** The review agent's structured verdict on a candidate answer. */
public record Verdict(boolean approved,String feedback,List<String>unsupportedClaims){

public static Verdict reject(String feedback){return new Verdict(false,feedback,List.of());}}
