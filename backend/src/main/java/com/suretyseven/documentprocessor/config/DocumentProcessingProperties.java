package com.suretyseven.documentprocessor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "document-processing")
public class DocumentProcessingProperties {

    /** Max attempts per document before giving up (mock processor calls, not HTTP retries). */
    private int maxRetryAttempts = 3;

    /** Fixed delay, in milliseconds, between internal retry attempts. */
    private long retryBackoffMs = 1000;

    private final Executor executor = new Executor();
    private final Storage storage = new Storage();

    public int getMaxRetryAttempts() {
        return maxRetryAttempts;
    }

    public void setMaxRetryAttempts(int maxRetryAttempts) {
        this.maxRetryAttempts = maxRetryAttempts;
    }

    public long getRetryBackoffMs() {
        return retryBackoffMs;
    }

    public void setRetryBackoffMs(long retryBackoffMs) {
        this.retryBackoffMs = retryBackoffMs;
    }

    public Executor getExecutor() {
        return executor;
    }

    public Storage getStorage() {
        return storage;
    }

    public static class Executor {
        private int corePoolSize = 4;
        private int maxPoolSize = 8;
        private int queueCapacity = 100;

        public int getCorePoolSize() {
            return corePoolSize;
        }

        public void setCorePoolSize(int corePoolSize) {
            this.corePoolSize = corePoolSize;
        }

        public int getMaxPoolSize() {
            return maxPoolSize;
        }

        public void setMaxPoolSize(int maxPoolSize) {
            this.maxPoolSize = maxPoolSize;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }
    }

    public static class Storage {
        private String basePath = "./uploaded-documents";

        public String getBasePath() {
            return basePath;
        }

        public void setBasePath(String basePath) {
            this.basePath = basePath;
        }
    }
}
