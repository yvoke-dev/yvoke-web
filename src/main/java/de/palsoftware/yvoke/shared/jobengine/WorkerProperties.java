package de.palsoftware.yvoke.shared.jobengine;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix="app.worker")public record WorkerProperties(boolean enabled,int concurrency,Duration pollInterval,Duration heartbeatInterval,Duration staleAfter){

@ConstructorBinding public WorkerProperties{if(concurrency<=0){concurrency=4;}if(pollInterval==null||pollInterval.isZero()||pollInterval.isNegative()){pollInterval=Duration.ofSeconds(2);}if(heartbeatInterval==null||heartbeatInterval.isZero()||heartbeatInterval.isNegative()){heartbeatInterval=Duration.ofSeconds(30);}if(staleAfter==null||staleAfter.isZero()||staleAfter.isNegative()){staleAfter=Duration.ofMinutes(5);}}

public WorkerProperties(boolean enabled,int concurrency,Duration pollInterval){this(enabled,concurrency,pollInterval,Duration.ofSeconds(30),Duration.ofMinutes(5));}}
