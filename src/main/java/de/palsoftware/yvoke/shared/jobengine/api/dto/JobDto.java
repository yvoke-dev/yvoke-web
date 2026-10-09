package de.palsoftware.yvoke.shared.jobengine.api.dto;

import de.palsoftware.yvoke.shared.jobengine.model.IngestionJob;
import de.palsoftware.yvoke.shared.jobengine.model.JobCounts;
import de.palsoftware.yvoke.shared.jobengine.model.JobStatus;
import de.palsoftware.yvoke.shared.jobengine.model.JobStep;
import jakarta.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record JobDto(UUID id,String kind,String sourceRef,List<String>tags,UUID collectionId,String collectionName,JobStatus status,@Nullable JobStep step,int progress,int attempts,@Nullable String error,@Nullable JobCounts counts,OffsetDateTime createdAt,@Nullable OffsetDateTime startedAt,@Nullable OffsetDateTime finishedAt,Map<String,Object>settings,@Nullable String summary){public static JobDto from(IngestionJob job){return new JobDto(job.id(),job.kind(),job.sourceRef(),job.tags(),job.collectionId(),job.collectionName(),job.status(),job.step(),job.progress(),job.attempts(),job.error(),job.counts(),job.createdAt(),job.startedAt(),job.finishedAt(),job.settings(),job.summary());}}
