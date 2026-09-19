package com.suretyseven.documentprocessor.repository;

import com.suretyseven.documentprocessor.domain.DocumentHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DocumentHistoryRepository extends JpaRepository<DocumentHistory, Long> {

    List<DocumentHistory> findByDocumentIdOrderByTimestampAsc(String documentId);
}
