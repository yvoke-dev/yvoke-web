package de.palsoftware.yvoke.shared.user.model;

import java.time.Instant;
import java.util.UUID;

public record User(UUID id,String entraOid,String email,String displayName,Instant lastSeenAt){}
