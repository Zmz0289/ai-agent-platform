-- 租户注册表：维护可用租户及其基础信息。
-- tenant_id 与 documents.tenant_id / ecommerce_qa_pairs.tenant_id 保持同样的长度约束。

CREATE TABLE IF NOT EXISTS tenants (
    tenant_id VARCHAR(64) NOT NULL,
    tenant_name VARCHAR(128) NOT NULL,
    description VARCHAR(500),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME NOT NULL,
    updated_at DATETIME,
    PRIMARY KEY (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 内置默认租户：未携带 X-Tenant-Id 的请求落到该租户。
INSERT IGNORE INTO tenants (tenant_id, tenant_name, description, enabled, created_at)
VALUES ('default', '默认租户', '内置默认租户，未携带 X-Tenant-Id 的请求落到该租户', TRUE, NOW());
