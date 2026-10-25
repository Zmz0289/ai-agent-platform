package com.aiagent.infrastructure.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantContextTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void shouldFallBackToDefaultTenantWhenUnset() {
        assertThat(TenantContext.currentTenant()).isEqualTo(TenantContext.DEFAULT_TENANT);
        assertThat(TenantContext.isDefaultTenant()).isTrue();
    }

    @Test
    void shouldAcceptValidTenantAndTrimWhitespace() {
        assertThat(TenantContext.resolve("  acme_01  ", "default")).isEqualTo("acme_01");
    }

    @Test
    void shouldFallBackToDefaultWhenHeaderMissing() {
        assertThat(TenantContext.resolve(null, "default")).isEqualTo("default");
        assertThat(TenantContext.resolve("  ", "tenant_a")).isEqualTo("tenant_a");
    }

    @Test
    void shouldRejectInvalidTenantInsteadOfSilentlyUsingDefault() {
        assertThatThrownBy(() -> TenantContext.resolve("acme-corp", "default"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid tenant id");
        assertThatThrownBy(() -> TenantContext.resolve("tenant/../../etc", "default"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldPropagateTenantIntoWrappedAsyncTask() throws Exception {
        TenantContext.set("tenant_a");
        Callable<String> wrapped = TenantContext.wrap(TenantContext::currentTenant);

        TenantContext.set("tenant_b");
        assertThat(wrapped.call()).isEqualTo("tenant_a");
        assertThat(TenantContext.currentTenant()).isEqualTo("tenant_b");
    }

    @Test
    void shouldRestorePreviousContextAfterWrappedRunnable() {
        TenantContext.set("tenant_a");
        Runnable wrapped = TenantContext.wrap(() -> {
            assertThat(TenantContext.currentTenant()).isEqualTo("tenant_a");
        });

        TenantContext.set("tenant_b");
        wrapped.run();
        assertThat(TenantContext.currentTenant()).isEqualTo("tenant_b");
    }
}
