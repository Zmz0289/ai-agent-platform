package com.aiagent.tenant.infrastructure.repository;

import com.aiagent.tenant.domain.TenantInfo;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantRepository extends JpaRepository<TenantInfo, String> {
}
