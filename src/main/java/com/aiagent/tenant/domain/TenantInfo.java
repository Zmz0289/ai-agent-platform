package com.aiagent.tenant.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 租户注册信息。
 *
 * <p>tenantId 与请求头 {@code X-Tenant-Id}、向量集合后缀、documents/ecommerce_qa_pairs
 * 的 tenant_id 使用同一套取值，字符集限制为 {@code [A-Za-z0-9_]{1,32}}。</p>
 */
@Data
@Entity
@Table(name = "tenants")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantInfo {

    @Id
    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "tenant_name", nullable = false, length = 128)
    private String tenantName;

    @Column(length = 500)
    private String description;

    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
