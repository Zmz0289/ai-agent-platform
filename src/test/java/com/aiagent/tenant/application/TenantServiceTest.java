package com.aiagent.tenant.application;

import com.aiagent.shared.exception.ResourceNotFoundException;
import com.aiagent.shared.exception.ResourceStateConflictException;
import com.aiagent.tenant.domain.TenantInfo;
import com.aiagent.tenant.infrastructure.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TenantServiceTest {

    @Mock
    private TenantRepository tenantRepository;

    private TenantService tenantService;

    @BeforeEach
    void setUp() {
        tenantService = new TenantService(tenantRepository);
    }

    @Test
    void shouldCreateTenantWithNormalizedFields() {
        when(tenantRepository.existsById("tenant_a")).thenReturn(false);
        when(tenantRepository.save(any(TenantInfo.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TenantInfo created = tenantService.create(" tenant_a ", " 租户A ", " 备注 ", null);

        ArgumentCaptor<TenantInfo> captor = ArgumentCaptor.forClass(TenantInfo.class);
        verify(tenantRepository).save(captor.capture());
        assertThat(captor.getValue().getTenantId()).isEqualTo("tenant_a");
        assertThat(captor.getValue().getTenantName()).isEqualTo("租户A");
        assertThat(captor.getValue().getDescription()).isEqualTo("备注");
        assertThat(captor.getValue().getEnabled()).isTrue();
        assertThat(created.getTenantId()).isEqualTo("tenant_a");
    }

    @Test
    void shouldRejectDuplicateTenant() {
        when(tenantRepository.existsById("tenant_a")).thenReturn(true);

        assertThatThrownBy(() -> tenantService.create("tenant_a", "租户A", null, true))
                .isInstanceOf(ResourceStateConflictException.class)
                .hasMessageContaining("already exists");
        verify(tenantRepository, never()).save(any());
    }

    @Test
    void shouldRejectInvalidTenantId() {
        assertThatThrownBy(() -> tenantService.create("tenant-a", "租户A", null, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid tenant id");
    }

    @Test
    void shouldFailWhenTenantMissing() {
        when(tenantRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tenantService.get("missing"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void shouldProtectDefaultTenantFromDeletionAndDisable() {
        TenantInfo defaultTenant = TenantInfo.builder()
                .tenantId("default")
                .tenantName("默认租户")
                .enabled(true)
                .build();
        when(tenantRepository.findById("default")).thenReturn(Optional.of(defaultTenant));

        assertThatThrownBy(() -> tenantService.delete("default"))
                .isInstanceOf(ResourceStateConflictException.class)
                .hasMessageContaining("cannot be deleted");

        assertThatThrownBy(() -> tenantService.update("default", "默认租户", null, false))
                .isInstanceOf(ResourceStateConflictException.class)
                .hasMessageContaining("cannot be disabled");

        verify(tenantRepository, never()).deleteById("default");
    }

    @Test
    void shouldUpdateAndDeleteNormalTenant() {
        TenantInfo tenant = TenantInfo.builder()
                .tenantId("tenant_a")
                .tenantName("旧名称")
                .enabled(true)
                .build();
        when(tenantRepository.findById("tenant_a")).thenReturn(Optional.of(tenant));
        when(tenantRepository.save(tenant)).thenReturn(tenant);
        when(tenantRepository.existsById("tenant_a")).thenReturn(true);

        TenantInfo updated = tenantService.update("tenant_a", "新名称", "说明", false);

        assertThat(updated.getTenantName()).isEqualTo("新名称");
        assertThat(updated.getDescription()).isEqualTo("说明");
        assertThat(updated.getEnabled()).isFalse();

        tenantService.delete("tenant_a");
        verify(tenantRepository).deleteById("tenant_a");
    }
}
