package de.palsoftware.yvoke.document.api.model;

import java.util.UUID;

public record DocumentDto(UUID id,String sourceFile,String title,String ingestionStatus){}
