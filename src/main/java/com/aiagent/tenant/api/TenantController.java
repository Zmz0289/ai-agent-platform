package com.aiagent.tenant.api;

import com.aiagent.tenant.application.TenantService;
import com.aiagent.tenant.domain.TenantInfo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 租户维护接口（管理员）。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/tenants")
@RequiredArgsConstructor
public class TenantController {

    private final TenantService tenantService;

    @GetMapping
    public List<TenantInfo> list() {
        return tenantService.list();
    }

    @GetMapping("/{tenantId}")
    public TenantInfo get(@PathVariable String tenantId) {
        return tenantService.get(tenantId);
    }

    @PostMapping
    public ResponseEntity<TenantInfo> create(@Valid @RequestBody TenantCreateRequest request) {
        TenantInfo created = tenantService.create(
                request.tenantId(), request.tenantName(), request.description(), request.enabled());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{tenantId}")
    public TenantInfo update(@PathVariable String tenantId,
                             @Valid @RequestBody TenantUpdateRequest request) {
        return tenantService.update(
                tenantId, request.tenantName(), request.description(), request.enabled());
    }

    @DeleteMapping("/{tenantId}")
    public ResponseEntity<Void> delete(@PathVariable String tenantId) {
        tenantService.delete(tenantId);
        return ResponseEntity.noContent().build();
    }
}
