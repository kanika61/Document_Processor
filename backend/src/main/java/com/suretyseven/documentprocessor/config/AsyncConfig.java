package com.suretyseven.documentprocessor.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync
@EnableConfigurationProperties(DocumentProcessingProperties.class)
public class AsyncConfig {

    @Bean(name = "documentProcessingExecutor")
    public ThreadPoolTaskExecutor documentProcessingExecutor(DocumentProcessingProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getExecutor().getCorePoolSize());
        executor.setMaxPoolSize(properties.getExecutor().getMaxPoolSize());
        executor.setQueueCapacity(properties.getExecutor().getQueueCapacity());
        executor.setThreadNamePrefix("doc-processing-");
        executor.initialize();
        return executor;
    }
}
