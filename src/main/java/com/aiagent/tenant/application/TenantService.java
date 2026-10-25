package com.aiagent.tenant.application;

import com.aiagent.infrastructure.tenant.TenantContext;
import com.aiagent.shared.exception.ResourceNotFoundException;
import com.aiagent.shared.exception.ResourceStateConflictException;
import com.aiagent.tenant.domain.TenantInfo;
import com.aiagent.tenant.infrastructure.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 租户注册信息维护。
 *
 * <p>只维护租户元数据；租户的实际数据隔离仍由 {@code <collection>_<tenantId>}
 * 与各表的 tenant_id 列负责。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantService {

    /** 与 TenantContext 的校验规则保持一致，确保可以直接拼进 collection 名与缓存 key。 */
    private static final Pattern TENANT_ID_PATTERN = Pattern.compile("[A-Za-z0-9_]{1,32}");

    private final TenantRepository tenantRepository;

    @Transactional(readOnly = true)
    public List<TenantInfo> list() {
        return tenantRepository.findAll(Sort.by(Sort.Direction.ASC, "tenantId"));
    }

    @Transactional(readOnly = true)
    public TenantInfo get(String tenantId) {
        String normalized = normalizeTenantId(tenantId);
        return tenantRepository.findById(normalized)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + normalized));
    }

    @Transactional
    public TenantInfo create(String tenantId, String tenantName, String description, Boolean enabled) {
        String normalized = normalizeTenantId(tenantId);
        String name = requireName(tenantName);
        if (tenantRepository.existsById(normalized)) {
            throw new ResourceStateConflictException("Tenant already exists: " + normalized);
        }

        TenantInfo tenant = TenantInfo.builder()
                .tenantId(normalized)
                .tenantName(name)
                .description(normalizeDescription(description))
                .enabled(enabled == null ? Boolean.TRUE : enabled)
                .build();
        try {
            TenantInfo saved = tenantRepository.save(tenant);
            log.info("Tenant created: {}", normalized);
            return saved;
        } catch (DataIntegrityViolationException duplicate) {
            throw new ResourceStateConflictException("Tenant already exists: " + normalized);
        }
    }

    @Transactional
    public TenantInfo update(String tenantId, String tenantName, String description, Boolean enabled) {
        TenantInfo tenant = get(tenantId);
        tenant.setTenantName(requireName(tenantName));
        tenant.setDescription(normalizeDescription(description));
        if (enabled != null) {
            if (TenantContext.DEFAULT_TENANT.equals(tenant.getTenantId()) && !enabled) {
                throw new ResourceStateConflictException("default tenant cannot be disabled");
            }
            tenant.setEnabled(enabled);
        }
        TenantInfo saved = tenantRepository.save(tenant);
        log.info("Tenant updated: {}", tenant.getTenantId());
        return saved;
    }

    @Transactional
    public void delete(String tenantId) {
        String normalized = normalizeTenantId(tenantId);
        if (TenantContext.DEFAULT_TENANT.equals(normalized)) {
            throw new ResourceStateConflictException("default tenant cannot be deleted");
        }
        if (!tenantRepository.existsById(normalized)) {
            throw new ResourceNotFoundException("Tenant not found: " + normalized);
        }
        tenantRepository.deleteById(normalized);
        log.info("Tenant deleted: {}", normalized);
    }

    private String normalizeTenantId(String tenantId) {
        String normalized = tenantId == null ? "" : tenantId.trim();
        if (!TENANT_ID_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "Invalid tenant id; only letters, digits and underscore are allowed (max 32 characters)");
        }
        return normalized;
    }

    private String requireName(String tenantName) {
        if (!StringUtils.hasText(tenantName)) {
            throw new IllegalArgumentException("tenantName must not be blank");
        }
        String trimmed = tenantName.trim();
        if (trimmed.length() > 128) {
            throw new IllegalArgumentException("tenantName must not exceed 128 characters");
        }
        return trimmed;
    }

    private String normalizeDescription(String description) {
        if (!StringUtils.hasText(description)) {
            return null;
        }
        String trimmed = description.trim();
        if (trimmed.length() > 500) {
            throw new IllegalArgumentException("description must not exceed 500 characters");
        }
        return trimmed;
    }
}
