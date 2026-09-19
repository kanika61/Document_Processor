package com.suretyseven.documentprocessor.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "document_history")
@Getter
@Setter
@NoArgsConstructor
public class DocumentHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id", nullable = false, length = 50)
    private String documentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DocumentStatus status;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "timestamp", nullable = false)
    private Instant timestamp;

    public DocumentHistory(String documentId, DocumentStatus status, String reason, Instant timestamp) {
        this.documentId = documentId;
        this.status = status;
        this.reason = reason;
        this.timestamp = timestamp;
    }
}
