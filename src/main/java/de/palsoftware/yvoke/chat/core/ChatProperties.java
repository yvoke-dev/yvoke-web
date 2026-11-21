package de.palsoftware.yvoke.chat.core;

import jakarta.validation.constraints.NotEmpty;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.validation.annotation.Validated;

@Validated @ConfigurationProperties(prefix="app.chat")public record ChatProperties(boolean enabled,@NotEmpty List<String>allowedModels,boolean playbookValidationEnabled,boolean traceToolCalls,String modelLabels,Duration staleGeneratingAfter,int maxMessageChars,int maxHistoryChars){

public static final Duration DEFAULT_STALE_GENERATING_AFTER=Duration.ofMinutes(5);public static final int DEFAULT_MAX_MESSAGE_CHARS=100_000;public static final int DEFAULT_MAX_HISTORY_CHARS=400_000;

@ConstructorBinding public ChatProperties(boolean enabled,List<String>allowedModels,boolean playbookValidationEnabled,boolean traceToolCalls,String modelLabels,Duration staleGeneratingAfter,int maxMessageChars,int maxHistoryChars){this.enabled=enabled;this.allowedModels=allowedModels;this.playbookValidationEnabled=playbookValidationEnabled;this.traceToolCalls=traceToolCalls;this.modelLabels=modelLabels;this.staleGeneratingAfter=(staleGeneratingAfter==null||staleGeneratingAfter.isNegative())?DEFAULT_STALE_GENERATING_AFTER:staleGeneratingAfter;this.maxMessageChars=maxMessageChars>0?maxMessageChars:DEFAULT_MAX_MESSAGE_CHARS;this.maxHistoryChars=maxHistoryChars>0?maxHistoryChars:DEFAULT_MAX_HISTORY_CHARS;}

public ChatProperties(boolean enabled,List<String>allowedModels,boolean playbookValidationEnabled,boolean traceToolCalls,String modelLabels){this(enabled,allowedModels,playbookValidationEnabled,traceToolCalls,modelLabels,DEFAULT_STALE_GENERATING_AFTER,DEFAULT_MAX_MESSAGE_CHARS,DEFAULT_MAX_HISTORY_CHARS);}

public ChatProperties(boolean enabled,List<String>allowedModels,boolean playbookValidationEnabled,boolean traceToolCalls){this(enabled,allowedModels,playbookValidationEnabled,traceToolCalls,"",DEFAULT_STALE_GENERATING_AFTER,DEFAULT_MAX_MESSAGE_CHARS,DEFAULT_MAX_HISTORY_CHARS);}}
