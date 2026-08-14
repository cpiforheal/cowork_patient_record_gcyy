package com.coshare.patientrecord.ai.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@Profile("mysql")
public class AiTaskExecutorConfig {

    @Bean(name = "aiDocumentTaskExecutor")
    public ThreadPoolTaskExecutor aiDocumentTaskExecutor(
        @Value("${clinic.ai.document-task.workers:2}") int workers,
        @Value("${clinic.ai.document-task.queue-capacity:20}") int queueCapacity
    ) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, workers));
        executor.setMaxPoolSize(Math.max(1, workers));
        executor.setQueueCapacity(Math.max(1, queueCapacity));
        executor.setThreadNamePrefix("ai-document-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        return executor;
    }
}
