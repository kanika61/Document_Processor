package com.suretyseven.documentprocessor.repository;

import com.suretyseven.documentprocessor.domain.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface DocumentRepository extends JpaRepository<Document, String> {

    Optional<Document> findByContentHash(String contentHash);

    @Query("""
            SELECT d FROM Document d
            WHERE (:status IS NULL OR d.status = :status)
              AND (:documentType IS NULL OR d.documentType = :documentType)
              AND (:uploadDateStart IS NULL OR d.createdAt >= :uploadDateStart)
              AND (:uploadDateEnd IS NULL OR d.createdAt < :uploadDateEnd)
            """)
    Page<Document> search(
            @Param("status") com.suretyseven.documentprocessor.domain.DocumentStatus status,
            @Param("documentType") com.suretyseven.documentprocessor.domain.DocumentType documentType,
            @Param("uploadDateStart") Instant uploadDateStart,
            @Param("uploadDateEnd") Instant uploadDateEnd,
            Pageable pageable);
}
