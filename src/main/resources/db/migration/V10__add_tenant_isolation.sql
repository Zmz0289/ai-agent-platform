-- 多租户隔离：documents.tenant_id + (tenant_id, content_hash) 唯一约束
-- 存量数据统一归入 default 租户，与未携带 X-Tenant-Id 的请求保持一致。

SET @tenant_column_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'documents'
      AND column_name = 'tenant_id'
);
SET @tenant_column_sql = IF(
    @tenant_column_exists = 0,
    'ALTER TABLE documents ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT ''default'' AFTER id',
    'SELECT 1'
);
PREPARE tenant_column_statement FROM @tenant_column_sql;
EXECUTE tenant_column_statement;
DEALLOCATE PREPARE tenant_column_statement;

-- content_hash 原来是全局唯一，多租户后同一文件允许不同租户各存一份，
-- 因此先移除单列唯一索引（索引名由历史版本生成，按信息模式动态定位）。
SET @content_hash_unique_index = (
    SELECT index_name
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'documents'
      AND column_name = 'content_hash'
      AND non_unique = 0
    LIMIT 1
);
SET @drop_content_hash_unique_sql = IF(
    @content_hash_unique_index IS NOT NULL,
    CONCAT('ALTER TABLE documents DROP INDEX `', @content_hash_unique_index, '`'),
    'SELECT 1'
);
PREPARE drop_content_hash_unique_statement FROM @drop_content_hash_unique_sql;
EXECUTE drop_content_hash_unique_statement;
DEALLOCATE PREPARE drop_content_hash_unique_statement;

SET @tenant_content_hash_index_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'documents'
      AND index_name = 'uk_documents_tenant_content_hash'
);
SET @tenant_content_hash_index_sql = IF(
    @tenant_content_hash_index_exists = 0,
    'CREATE UNIQUE INDEX uk_documents_tenant_content_hash ON documents(tenant_id, content_hash)',
    'SELECT 1'
);
PREPARE tenant_content_hash_index_statement FROM @tenant_content_hash_index_sql;
EXECUTE tenant_content_hash_index_statement;
DEALLOCATE PREPARE tenant_content_hash_index_statement;

SET @tenant_created_index_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'documents'
      AND index_name = 'idx_documents_tenant_created'
);
SET @tenant_created_index_sql = IF(
    @tenant_created_index_exists = 0,
    'CREATE INDEX idx_documents_tenant_created ON documents(tenant_id, created_at)',
    'SELECT 1'
);
PREPARE tenant_created_index_statement FROM @tenant_created_index_sql;
EXECUTE tenant_created_index_statement;
DEALLOCATE PREPARE tenant_created_index_statement;
