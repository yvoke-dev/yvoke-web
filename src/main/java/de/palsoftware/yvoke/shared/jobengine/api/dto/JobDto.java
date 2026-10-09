package de.palsoftware.yvoke.shared.jobengine.api.dto;

import de.palsoftware.yvoke.shared.jobengine.model.IngestionJob;
import de.palsoftware.yvoke.shared.jobengine.model.JobStatus;
import jakarta.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.UUID;

public record JobDto(UUID id,String kind,JobStatus status,OffsetDateTime createdAt,@Nullable OffsetDateTime updatedAt,@Nullable String error){

public static JobDto from(IngestionJob job){OffsetDateTime updatedAt=job.finishedAt()!=null?job.finishedAt():(job.startedAt()!=null?job.startedAt():job.createdAt());return new JobDto(job.id(),job.kind(),job.status(),job.createdAt(),updatedAt,job.error());}}
