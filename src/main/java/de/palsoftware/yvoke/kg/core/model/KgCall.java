package de.palsoftware.yvoke.kg.core.model;

import jakarta.annotation.Nullable;

public record KgCall(String name,@Nullable String kind,@Nullable String description,String relationType){

@Nullable public String kind(){return kind;}}
