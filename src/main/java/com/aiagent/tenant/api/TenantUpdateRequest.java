package com.aiagent.tenant.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TenantUpdateRequest(
        @NotBlank(message = "tenantName must not be blank")
        @Size(max = 128, message = "tenantName must not exceed 128 characters")
        String tenantName,

        @Size(max = 500, message = "description must not exceed 500 characters")
        String description,

        Boolean enabled
) {
}
