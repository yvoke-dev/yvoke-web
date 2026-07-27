package de.palsoftware.yvoke.shared.audit.model;


import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record AuditLog(UUID id,String entraOid,String action,String target,Map<String,Object>detail,OffsetDateTime createdAt){}
