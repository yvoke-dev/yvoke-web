package de.palsoftware.yvoke.ingest.core;

import de.palsoftware.yvoke.ingest.core.service.ZipExtractionProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ZipExtractionProperties.class)
public class IngestConfig {
}
