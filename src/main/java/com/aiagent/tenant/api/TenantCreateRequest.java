package com.aiagent.tenant.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record TenantCreateRequest(
        @NotBlank(message = "tenantId must not be blank")
        @Pattern(regexp = "^[A-Za-z0-9_]{1,32}$",
                message = "tenantId must contain 1 to 32 letters, digits or underscores")
        String tenantId,

        @NotBlank(message = "tenantName must not be blank")
        @Size(max = 128, message = "tenantName must not exceed 128 characters")
        String tenantName,

        @Size(max = 500, message = "description must not exceed 500 characters")
        String description,

        Boolean enabled
) {
}
