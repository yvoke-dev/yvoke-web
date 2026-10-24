package de.palsoftware.yvoke.chat.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ChatProperties.class)
public class ChatConfig {

    @Bean("modelLabels")
    public ModelLabels modelLabels(ChatProperties chatProperties, ObjectMapper objectMapper) {
        return ModelLabels.parse(objectMapper, chatProperties.modelLabels());
    }
}
