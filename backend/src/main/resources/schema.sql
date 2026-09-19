CREATE TABLE IF NOT EXISTS documents (
    document_id      VARCHAR(50) PRIMARY KEY,
    filename          VARCHAR(255) NOT NULL,
    document_type     VARCHAR(50) NOT NULL,
    content_hash      VARCHAR(64) NOT NULL UNIQUE,
    file_path         VARCHAR(500) NOT NULL,
    status            VARCHAR(20) NOT NULL,
    retry_count       INT DEFAULT 0,
    extracted_result  JSON,
    failure_reason    VARCHAR(500),
    file_size_bytes   BIGINT,
    created_at        TIMESTAMP NOT NULL,
    updated_at        TIMESTAMP NOT NULL,

    INDEX idx_document_type (document_type),
    INDEX idx_status (status)
);

CREATE TABLE IF NOT EXISTS document_history (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id   VARCHAR(50) NOT NULL,
    status        VARCHAR(20) NOT NULL,
    reason        VARCHAR(500),
    timestamp     TIMESTAMP NOT NULL,

    FOREIGN KEY (document_id) REFERENCES documents(document_id),
    INDEX idx_document_id (document_id)
);
