-- 电商 QA 多租户隔离：ecommerce_qa_pairs.tenant_id +
-- 唯一键由全局 record_hash 调整为 (tenant_id, record_hash)。
-- 存量数据统一归入 default 租户，与未携带 X-Tenant-Id 的请求保持一致。

SET @tenant_column_exists = (
    SELECT COUNT(*)
      FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'ecommerce_qa_pairs'
       AND column_name = 'tenant_id'
);
SET @tenant_column_sql = IF(
    @tenant_column_exists = 0,
    'ALTER TABLE ecommerce_qa_pairs ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT ''default'' AFTER id',
    'SELECT 1'
);
PREPARE tenant_column_statement FROM @tenant_column_sql;
EXECUTE tenant_column_statement;
DEALLOCATE PREPARE tenant_column_statement;

-- 原唯一键是全局 record_hash，多租户后同一 QA 允许各租户各存一份，先移除。
SET @global_unique_exists = (
    SELECT COUNT(*)
      FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'ecommerce_qa_pairs'
       AND index_name = 'uk_ecommerce_qa_record_hash'
);
SET @drop_global_unique_sql = IF(
    @global_unique_exists > 0,
    'ALTER TABLE ecommerce_qa_pairs DROP INDEX uk_ecommerce_qa_record_hash',
    'SELECT 1'
);
PREPARE drop_global_unique_statement FROM @drop_global_unique_sql;
EXECUTE drop_global_unique_statement;
DEALLOCATE PREPARE drop_global_unique_statement;

SET @tenant_hash_index_exists = (
    SELECT COUNT(*)
      FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'ecommerce_qa_pairs'
       AND index_name = 'uk_ecommerce_qa_tenant_hash'
);
SET @tenant_hash_index_sql = IF(
    @tenant_hash_index_exists = 0,
    'CREATE UNIQUE INDEX uk_ecommerce_qa_tenant_hash ON ecommerce_qa_pairs(tenant_id, record_hash)',
    'SELECT 1'
);
PREPARE tenant_hash_index_statement FROM @tenant_hash_index_sql;
EXECUTE tenant_hash_index_statement;
DEALLOCATE PREPARE tenant_hash_index_statement;

SET @tenant_created_index_exists = (
    SELECT COUNT(*)
      FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'ecommerce_qa_pairs'
       AND index_name = 'idx_ecommerce_qa_tenant_created'
);
SET @tenant_created_index_sql = IF(
    @tenant_created_index_exists = 0,
    'CREATE INDEX idx_ecommerce_qa_tenant_created ON ecommerce_qa_pairs(tenant_id, created_at)',
    'SELECT 1'
);
PREPARE tenant_created_index_statement FROM @tenant_created_index_sql;
EXECUTE tenant_created_index_statement;
DEALLOCATE PREPARE tenant_created_index_statement;
