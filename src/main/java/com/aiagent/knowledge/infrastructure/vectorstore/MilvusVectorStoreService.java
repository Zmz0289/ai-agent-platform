package com.aiagent.knowledge.infrastructure.vectorstore;

import com.aiagent.infrastructure.config.AiProperties;
import com.aiagent.infrastructure.tenant.TenantContext;
import com.aiagent.knowledge.domain.RetrievalChunk;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;

/**
 * LangChain4j Milvus 适配器。
 *
 * <p>多租户采用 collection 级物理隔离：default 租户沿用基础 collection（兼容存量数据），
 * 其他租户按需创建 {@code <base>_<tenantId>} collection。</p>
 */
@Slf4j
@Service
@Primary
@ConditionalOnProperty(prefix = "ai.vector-store", name = "type", havingValue = "milvus", matchIfMissing = true)
@ConditionalOnProperty(prefix = "ai.vector-store", name = "mode", havingValue = "langchain", matchIfMissing = true)
public class MilvusVectorStoreService implements VectorStoreService {

    private final AiProperties aiProperties;
    private final MilvusClientV2 milvusClient;
    private final InMemoryVectorStoreService fallbackStore = new InMemoryVectorStoreService();
    private final Map<String, MilvusEmbeddingStore> tenantStores = new ConcurrentHashMap<>();
    private final Object tenantStoreLock = new Object();
    private MilvusEmbeddingStore embeddingStore;

    public MilvusVectorStoreService(AiProperties aiProperties, Optional<MilvusClientV2> milvusClient) {
        this.aiProperties = aiProperties;
        this.milvusClient = milvusClient.orElse(null);
    }

    @PostConstruct
    public void init() {
        AiProperties.Milvus config = aiProperties.getVectorStore().getMilvus();
        if (milvusClient == null) {
            log.warn("Milvus client is unavailable; {}", fallbackMode());
            return;
        }
        if (config.isReadOnly() && !collectionExists(config)) {
            log.warn("Milvus collection [{}] is unavailable in read-only mode; disabling vector search",
                    config.getCollectionName());
            return;
        }
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "milvus-vectorstore-init");
            thread.setDaemon(true);
            return thread;
        });

        try {
            Future<MilvusEmbeddingStore> future = executor.submit(() -> MilvusEmbeddingStore.builder()
                    .host(config.getHost())
                    .port(config.getPort())
                    .collectionName(config.getCollectionName())
                    .dimension(config.getDimension())
                    .databaseName(config.getDatabaseName())
                    .build());

            this.embeddingStore = future.get(config.getConnectionTimeoutMs(), TimeUnit.MILLISECONDS);
            log.info("Connected to Milvus at {}:{}, Collection: {}, Dimension: {}",
                    config.getHost(), config.getPort(), config.getCollectionName(), config.getDimension());
        } catch (TimeoutException e) {
            log.warn("Milvus connection timed out ({}ms); {}", config.getConnectionTimeoutMs(), fallbackMode());
        } catch (Exception e) {
            log.warn("Milvus connection failed; {}: {}", fallbackMode(), extractMessage(e));
        } finally {
            executor.shutdownNow();
        }
    }

    @Override
    public void add(String id, Embedding embedding) {
        add(id, embedding, TenantContext.currentTenant());
    }

    @Override
    public void add(String id, Embedding embedding, String tenantId) {
        ensureWritable();
        MilvusEmbeddingStore store = storeFor(tenantId);
        if (store != null) {
            store.add(id, embedding);
            return;
        }
        requireFallbackEnabled();
        fallbackStore.add(id, embedding, tenantId);
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings, List<TextSegment> segments) {
        return addAll(embeddings, segments, TenantContext.currentTenant());
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings, List<TextSegment> segments, String tenantId) {
        ensureWritable();
        MilvusEmbeddingStore store = storeFor(tenantId);
        if (store != null) {
            return store.addAll(embeddings, segments);
        }
        requireFallbackEnabled();
        return fallbackStore.addAll(embeddings, segments, tenantId);
    }

    @Override
    public List<EmbeddingMatch<TextSegment>> search(Embedding queryEmbedding, int topK, double minScore) {
        return search(queryEmbedding, topK, minScore, TenantContext.currentTenant());
    }

    @Override
    public List<EmbeddingMatch<TextSegment>> search(Embedding queryEmbedding,
                                                    int topK,
                                                    double minScore,
                                                    String tenantId) {
        MilvusEmbeddingStore store = storeFor(tenantId);
        if (store != null) {
            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(topK)
                    .minScore(minScore)
                    .build();
            return store.search(request).matches();
        }
        return fallbackEnabled() && !readOnly()
                ? fallbackStore.search(queryEmbedding, topK, minScore, tenantId)
                : List.of();
    }

    @Override
    public List<RetrievalChunk> fetchAllChunks(int maxDocs) {
        return fetchAllChunks(maxDocs, TenantContext.currentTenant());
    }

    @Override
    public List<RetrievalChunk> fetchAllChunks(int maxDocs, String tenantId) {
        if (embeddingStore == null) {
            return fallbackEnabled() && !readOnly()
                    ? fallbackStore.fetchAllChunks(maxDocs, tenantId)
                    : List.of();
        }
        log.warn("Full-corpus fetch is not supported for the LangChain4j Milvus store; hybrid falls back to candidate-pool BM25");
        return List.of();
    }

    @Override
    public void remove(String id) {
        remove(id, TenantContext.currentTenant());
    }

    @Override
    public void remove(String id, String tenantId) {
        ensureWritable();
        MilvusEmbeddingStore store = storeFor(tenantId);
        if (store != null) {
            store.remove(id);
            return;
        }
        requireFallbackEnabled();
        fallbackStore.remove(id, tenantId);
    }

    @Override
    public void removeAll() {
        removeAll(TenantContext.currentTenant());
    }

    @Override
    public void removeAll(String tenantId) {
        ensureWritable();
        MilvusEmbeddingStore store = storeFor(tenantId);
        if (store != null) {
            store.removeAll();
            return;
        }
        requireFallbackEnabled();
        fallbackStore.removeAll(tenantId);
    }

    @PreDestroy
    public void destroy() {
        log.info("Closing vector store...");
    }

    /**
     * 解析租户对应的 collection；default 租户与关闭隔离时使用基础 collection。
     */
    String collectionNameFor(String tenantId) {
        String base = aiProperties.getVectorStore().getMilvus().getCollectionName();
        if (!tenantIsolationEnabled() || tenantId == null || TenantContext.DEFAULT_TENANT.equals(tenantId)) {
            return base;
        }
        return base + "_" + tenantId;
    }

    private MilvusEmbeddingStore storeFor(String tenantId) {
        if (embeddingStore == null) {
            return null;
        }
        String collection = collectionNameFor(tenantId);
        if (collection.equals(aiProperties.getVectorStore().getMilvus().getCollectionName())) {
            return embeddingStore;
        }
        MilvusEmbeddingStore existing = tenantStores.get(collection);
        if (existing != null) {
            return existing;
        }
        synchronized (tenantStoreLock) {
            existing = tenantStores.get(collection);
            if (existing != null) {
                return existing;
            }
            AiProperties.Milvus config = aiProperties.getVectorStore().getMilvus();
            try {
                MilvusEmbeddingStore created = MilvusEmbeddingStore.builder()
                        .host(config.getHost())
                        .port(config.getPort())
                        .collectionName(collection)
                        .dimension(config.getDimension())
                        .databaseName(config.getDatabaseName())
                        .build();
                tenantStores.put(collection, created);
                log.info("Initialized tenant vector collection [{}]", collection);
                return created;
            } catch (RuntimeException exception) {
                log.warn("Unable to initialize tenant vector collection [{}]: {}",
                        collection, extractMessage(exception));
                return null;
            }
        }
    }

    private boolean tenantIsolationEnabled() {
        AiProperties.Tenant tenant = aiProperties.getTenant();
        return tenant == null || tenant.isEnabled();
    }

    private String extractMessage(Exception exception) {
        Throwable cause = exception.getCause();
        return cause != null && cause.getMessage() != null ? cause.getMessage() : exception.getMessage();
    }

    private String fallbackMode() {
        return fallbackEnabled() && !readOnly()
                ? "using explicitly enabled in-memory fallback"
                : "vector operations disabled";
    }

    private boolean readOnly() {
        return aiProperties.getVectorStore().getMilvus().isReadOnly();
    }

    private boolean fallbackEnabled() {
        return aiProperties.getVectorStore().getMilvus().isFallbackEnabled();
    }

    @Override
    public boolean isAvailable() {
        return embeddingStore != null;
    }

    @Override
    public boolean isWriteAvailable() {
        return !readOnly() && (embeddingStore != null || fallbackEnabled());
    }

    private void ensureWritable() {
        if (readOnly()) {
            throw new IllegalStateException("Milvus vector store is read-only");
        }
    }

    private void requireFallbackEnabled() {
        if (!fallbackEnabled()) {
            throw new IllegalStateException("Milvus vector store is unavailable and in-memory fallback is disabled");
        }
    }

    private boolean collectionExists(AiProperties.Milvus config) {
        if (milvusClient == null) {
            return false;
        }
        try {
            return milvusClient.hasCollection(HasCollectionReq.builder()
                    .collectionName(config.getCollectionName())
                    .build());
        } catch (Exception exception) {
            log.warn("Unable to verify read-only Milvus collection [{}]: {}",
                    config.getCollectionName(), extractMessage(exception));
            return false;
        }
    }
}
