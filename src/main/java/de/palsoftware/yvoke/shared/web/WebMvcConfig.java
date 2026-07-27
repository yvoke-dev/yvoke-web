package de.palsoftware.yvoke.shared.web;

import java.util.List;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.security.task.DelegatingSecurityContextAsyncTaskExecutor;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final ChatEnabledInterceptor chatEnabledInterceptor;
    private final RateLimitInterceptor rateLimitInterceptor;
    private final UserArgumentResolver userArgumentResolver;

    public WebMvcConfig(ChatEnabledInterceptor chatEnabledInterceptor,
        RateLimitInterceptor rateLimitInterceptor, UserArgumentResolver userArgumentResolver) {
        this.chatEnabledInterceptor = chatEnabledInterceptor;
        this.rateLimitInterceptor = rateLimitInterceptor;
        this.userArgumentResolver = userArgumentResolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(userArgumentResolver);
    }

    @Bean
    public AsyncTaskExecutor mvcTaskExecutor() {
        AsyncTaskExecutor delegate =
            new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor());
        return new DelegatingSecurityContextAsyncTaskExecutor(delegate);
    }

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(mvcTaskExecutor());
        configurer.setDefaultTimeout(60000); // 60 seconds
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(chatEnabledInterceptor).addPathPatterns("/chat", "/chat/**");
        // Rate-limit only the expensive, LLM/ingest-triggering POSTs — not the cheap GET polls.
        registry.addInterceptor(rateLimitInterceptor).addPathPatterns("/chat/*/send",
            "/chat/*/send-async", "/api/ingest/**");
    }
}
