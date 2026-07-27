package de.palsoftware.yvoke.shared.jobengine;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix="app.worker")public record WorkerProperties(boolean enabled,int concurrency,Duration pollInterval){

public WorkerProperties{if(concurrency<=0){concurrency=4;}if(pollInterval==null||pollInterval.isZero()||pollInterval.isNegative()){pollInterval=Duration.ofSeconds(2);}}}
