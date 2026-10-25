package com.aiagent.knowledge.infrastructure.vectorstore;

import com.aiagent.infrastructure.config.AiProperties;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexBuildState;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.index.request.CreateIndexReq;
import io.milvus.v2.service.index.request.DescribeIndexReq;
import io.milvus.v2.service.utility.request.FlushReq;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Milvus 管理服务
 * 负责：建索引、flush、load
 * 用于数据导入完成后统一构建索引并加载到内存
 */
@Slf4j
@Service
public class MilvusAdminService {

    private final MilvusClientV2 milvusClient;
    private final AiProperties aiProperties;

    /** 业务集合名称 */
    private static final List<String> COLLECTION_NAMES = List.of(
            "ecommerce_qa"
    );

    public MilvusAdminService(@Autowired(required = false) MilvusClientV2 milvusClient,
                              AiProperties aiProperties) {
        this.milvusClient = milvusClient;
        this.aiProperties = aiProperties;
    }

    // =============================================
    // 1. 索引管理
    // =============================================

    /**
     * 为指定集合创建 HNSW 索引
     */
    public void createIndex(String collectionName) {
        ensureWritable();
        if (milvusClient == null) {
            log.warn("Milvus 客户端不可用，跳过建索引");
            return;
        }
        IndexParam indexParam = IndexParam.builder()
                .fieldName("embedding")
                .metricType(IndexParam.MetricType.COSINE)
                .indexType(IndexParam.IndexType.HNSW)
                .extraParams(Map.of("M", 16, "efConstruction", 200))
                .build();

        milvusClient.createIndex(CreateIndexReq.builder()
                .collectionName(collectionName)
                .indexParams(List.of(indexParam))
                .build());

        log.info("Collection [{}] HNSW 索引创建指令已发出", collectionName);
    }

    /**
     * 确保 QA 集合同步就绪：不存在则按 QA Schema 建集合、建索引，最后加载到内存。
     *
     * <p>启动期自动建集合只在 {@code ai.vector-store.mode=qa} 时发生，
     * 导入链路不能假设集合已存在，否则所有批次写入都会失败。</p>
     *
     * @return true 表示本次新建了集合，false 表示集合已存在
     */
    public boolean ensureCollection(String collectionName, int dimension) {
        if (milvusClient == null) {
            throw new IllegalStateException("Milvus 客户端不可用，请检查连接配置");
        }
        ensureWritable();

        boolean exists = milvusClient.hasCollection(HasCollectionReq.builder()
                .collectionName(collectionName)
                .build());
        if (!exists) {
            createQaCollection(collectionName, dimension);
        }
        milvusClient.loadCollection(LoadCollectionReq.builder()
                .collectionName(collectionName)
                .build());
        log.info("Collection [{}] 已就绪 (dimension={}, created={})", collectionName, dimension, !exists);
        return !exists;
    }

    /** 按与启动期一致的 QA Schema 创建集合，并建立 HNSW/COSINE 索引。 */
    private void createQaCollection(String collectionName, int dimension) {
        CreateCollectionReq.CollectionSchema schema = milvusClient.createSchema();
        schema.addField(AddFieldReq.builder()
                .fieldName("id")
                .dataType(DataType.Int64)
                .isPrimaryKey(true)
                .autoID(true)
                .description("Primary key")
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName("question")
                .dataType(DataType.VarChar)
                .maxLength(1024)
                .description("Question")
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName("answer")
                .dataType(DataType.VarChar)
                .maxLength(2048)
                .description("Answer")
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName("qa_text")
                .dataType(DataType.VarChar)
                .maxLength(3072)
                .description("Joined QA text")
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName("qa_pair_id")
                .dataType(DataType.Int64)
                .description("MySQL ecommerce_qa_pairs.id")
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName("category")
                .dataType(DataType.VarChar)
                .maxLength(100)
                .description("Category")
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName("source_file")
                .dataType(DataType.VarChar)
                .maxLength(255)
                .description("Source training file name")
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName("embedding")
                .dataType(DataType.FloatVector)
                .dimension(dimension)
                .description("Embedding vector")
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName("ts")
                .dataType(DataType.Int64)
                .description("Timestamp")
                .build());

        milvusClient.createCollection(CreateCollectionReq.builder()
                .collectionName(collectionName)
                .collectionSchema(schema)
                .description("Ecommerce QA knowledge base")
                .build());
        log.info("Collection [{}] 创建成功 (dimension={})", collectionName, dimension);

        createIndex(collectionName);
    }

    /**
     * 异步串行构建所有集合索引
     */
    public CompletableFuture<Void> buildAllIndexesAsync() {
        if (readOnly()) {
            log.warn("Milvus is read-only, skipping index build");
            return CompletableFuture.completedFuture(null);
        }
        if (milvusClient == null) {
            log.warn("Milvus 客户端不可用，跳过建索引");
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> {
            for (String name : COLLECTION_NAMES) {
                createIndex(name);
                pollIndexProgress(name);
                sleep(2000);
            }
            log.info("全部集合索引构建完成");
        });
    }

    /**
     * 轮询索引构建进度，直到完成
     */
    public void pollIndexProgress(String collectionName) {
        int maxRetries = 120;
        for (int i = 0; i < maxRetries; i++) {
            try {
                DescribeIndexReq req = DescribeIndexReq.builder()
                        .collectionName(collectionName)
                        .build();
                var resp = milvusClient.describeIndex(req);
                var indexDesc = resp.getIndexDescByFieldName("embedding");
                if (indexDesc == null) {
                    log.info("[{}] 索引描述信息尚未就绪 ({}/{})", collectionName, i + 1, maxRetries);
                    sleep(10000);
                    continue;
                }
                IndexBuildState state = indexDesc.getIndexState();
                long progress = indexDesc.getTotalRows() > 0
                        ? indexDesc.getIndexedRows() * 100 / indexDesc.getTotalRows()
                        : 0;
                log.info("[{}] 索引状态: {} 进度: {}% (indexedRows={}/{})",
                        collectionName, state, progress,
                        indexDesc.getIndexedRows(), indexDesc.getTotalRows());

                if (state == IndexBuildState.Finished) {
                    log.info("[{}] 索引构建完成", collectionName);
                    return;
                }
                if (state == IndexBuildState.Failed) {
                    log.error("[{}] 索引构建失败: {}", collectionName, indexDesc.getIndexFailedReason());
                    return;
                }
            } catch (Exception e) {
                log.warn("[{}] 查询索引状态异常: {}", collectionName, e.getMessage());
            }
            sleep(10000);
        }
        log.warn("[{}] 索引构建监控超时，请通过 Attu 手动确认状态", collectionName);
    }

    // =============================================
    // 2. Flush
    // =============================================

    /**
     * 刷新所有集合，落盘数据
     */
    public void flushAll() {
        if (readOnly()) {
            log.warn("Milvus is read-only, skipping flush");
            return;
        }
        if (milvusClient == null) {
            log.warn("Milvus 客户端不可用，跳过 flush");
            return;
        }
        for (String name : COLLECTION_NAMES) {
            try {
                milvusClient.flush(FlushReq.builder()
                        .collectionNames(List.of(name))
                        .build());
                log.info("Collection [{}] flush 完成", name);
            } catch (Exception e) {
                log.warn("Collection [{}] flush 失败: {}", name, e.getMessage());
            }
        }
    }

    // =============================================
    // 3. Load / Release
    // =============================================

    /**
     * 加载集合到内存，启用检索
     */
    public void loadCollection(String collectionName) {
        if (milvusClient == null) return;
        milvusClient.loadCollection(LoadCollectionReq.builder()
                .collectionName(collectionName)
                .build());
        log.info("Collection [{}] 已加载到内存", collectionName);
    }

    /**
     * 加载所有集合
     */
    public void loadAllCollections() {
        if (milvusClient == null) return;
        for (String name : COLLECTION_NAMES) {
            loadCollection(name);
        }
        log.info("所有集合已加载到内存");
    }

    // =============================================
    // 辅助
    // =============================================

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean readOnly() {
        return aiProperties.getVectorStore().getMilvus().isReadOnly();
    }

    private void ensureWritable() {
        if (readOnly()) {
            throw new IllegalStateException("Milvus administration is read-only");
        }
    }
}