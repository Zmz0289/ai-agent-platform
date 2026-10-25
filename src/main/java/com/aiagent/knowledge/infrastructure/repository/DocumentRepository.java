package com.aiagent.knowledge.infrastructure.repository;

import com.aiagent.knowledge.domain.Document;
import com.aiagent.knowledge.domain.DocumentProcessingStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DocumentRepository extends JpaRepository<Document, Long> {

    Optional<Document> findByContentHash(String contentHash);

    Optional<Document> findByTenantIdAndContentHash(String tenantId, String contentHash);

    Optional<Document> findByIdAndTenantId(Long id, String tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select document from Document document where document.id = :id")
    Optional<Document> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select document from Document document where document.id = :id and document.tenantId = :tenantId")
    Optional<Document> findByIdForUpdateAndTenantId(@Param("id") Long id,
                                                    @Param("tenantId") String tenantId);

    List<Document> findByProcessingStatusInAndStagingPathIsNotNull(Collection<DocumentProcessingStatus> statuses);

    List<Document> findByProcessingStatusAndStagingPathIsNotNull(DocumentProcessingStatus status);

    long countByIdInAndProcessingStatus(Collection<Long> ids, DocumentProcessingStatus status);
}
