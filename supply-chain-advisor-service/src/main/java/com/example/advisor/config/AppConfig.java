package com.example.advisor.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Central infrastructure configuration.
 *
 * <ul>
 *   <li>{@code @EnableAsync} activates Spring's proxy-based async method execution.</li>
 *   <li>{@code @EnableScheduling} activates the {@code @Scheduled} task infrastructure.</li>
 *   <li>{@code intelligenceExecutor} — dedicated thread pool for the advisory engine so that
 *       background analysis never starves the main request-handling threads.</li>
 *   <li>{@code RestTemplate} — used by {@code GlobalIntelligenceService} to fetch external feeds.</li>
 * </ul>
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AppConfig {

    /**
     * Thread pool backing all {@code @Async("intelligenceExecutor")} calls.
     * CallerRunsPolicy is used as the rejection handler so that no work is silently dropped
     * when the queue is full — the caller thread takes over instead.
     */
    @Bean(name = "intelligenceExecutor")
    public Executor intelligenceExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("intelligence-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
