package de.palsoftware.yvoke.chat.core;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated @ConfigurationProperties(prefix="app.chat")public record ChatProperties(boolean enabled,@NotEmpty List<String>allowedModels,boolean playbookValidationEnabled){}
