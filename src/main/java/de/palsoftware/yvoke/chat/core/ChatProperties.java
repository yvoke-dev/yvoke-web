package de.palsoftware.yvoke.chat.core;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.validation.annotation.Validated;

@Validated @ConfigurationProperties(prefix="app.chat")public record ChatProperties(boolean enabled,@NotEmpty List<String>allowedModels,boolean playbookValidationEnabled,boolean traceToolCalls,String modelLabels){

@ConstructorBinding public ChatProperties(boolean enabled,List<String>allowedModels,boolean playbookValidationEnabled,boolean traceToolCalls,String modelLabels){this.enabled=enabled;this.allowedModels=allowedModels;this.playbookValidationEnabled=playbookValidationEnabled;this.traceToolCalls=traceToolCalls;this.modelLabels=modelLabels;}

public ChatProperties(boolean enabled,List<String>allowedModels,boolean playbookValidationEnabled,boolean traceToolCalls){this(enabled,allowedModels,playbookValidationEnabled,traceToolCalls,"");}}
