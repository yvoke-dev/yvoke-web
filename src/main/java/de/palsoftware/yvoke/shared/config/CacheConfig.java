package de.palsoftware.yvoke.shared.config;


import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableCaching
public class CacheConfig {

    public static final String SYSTEM_PROMPTS = "systemPrompts";

    public static final String PLAYBOOKS = "playbooks";

    public static final String APP_CONFIG = "appConfig";

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager =
            new CaffeineCacheManager(SYSTEM_PROMPTS, PLAYBOOKS, APP_CONFIG);
        manager.setCaffeine(
            Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(60)).maximumSize(1_000));
        return manager;
    }
}
