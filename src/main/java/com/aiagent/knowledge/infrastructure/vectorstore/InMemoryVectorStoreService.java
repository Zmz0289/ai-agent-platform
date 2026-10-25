package com.aiagent.knowledge.infrastructure.vectorstore;

import com.aiagent.infrastructure.tenant.TenantContext;
import com.aiagent.knowledge.domain.RetrievalChunk;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "ai.vector-store", name = "type", havingValue = "inmemory")
public class InMemoryVectorStoreService implements VectorStoreService {

    /** default 租户沿用原有存储，保持与存量数据兼容。 */
    private final InMemoryEmbeddingStore<TextSegment> embeddingStore = new InMemoryEmbeddingStore<>();
    private final List<RetrievalChunk> chunks = new CopyOnWriteArrayList<>();
    private final Map<String, InMemoryEmbeddingStore<TextSegment>> tenantStores = new ConcurrentHashMap<>();
    private final Map<String, List<RetrievalChunk>> tenantChunks = new ConcurrentHashMap<>();

    @Override
    public void add(String id, Embedding embedding) {
        add(id, embedding, TenantContext.DEFAULT_TENANT);
    }

    @Override
    public void add(String id, Embedding embedding, String tenantId) {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        storeFor(tenantId).add(id, embedding);
        log.info("Added embedding with id: {} (tenant={})", id, tenantId);
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings, List<TextSegment> segments) {
        return addAll(embeddings, segments, TenantContext.DEFAULT_TENANT);
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings, List<TextSegment> segments, String tenantId) {
        InMemoryEmbeddingStore<TextSegment> store = storeFor(tenantId);
        List<RetrievalChunk> tenantChunkList = chunksFor(tenantId);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < embeddings.size(); i++) {
            String id = UUID.randomUUID().toString();
            store.add(id, embeddings.get(i), segments == null ? null : segments.get(i));
            ids.add(id);
            if (segments != null && i < segments.size() && segments.get(i) != null) {
                TextSegment segment = segments.get(i);
                tenantChunkList.add(RetrievalChunk.builder()
                        .id(id)
                        .content(segment.text())
                        .score(0.0)
                        .metadata(segment.metadata() == null ? Map.of() : segment.metadata().toMap())
                        .build());
            }
        }
        log.info("Added {} embeddings (tenant={})", embeddings.size(), tenantId);
        return ids;
    }

    @Override
    public List<EmbeddingMatch<TextSegment>> search(Embedding queryEmbedding, int topK, double minScore) {
        return search(queryEmbedding, topK, minScore, TenantContext.DEFAULT_TENANT);
    }

    @Override
    public List<EmbeddingMatch<TextSegment>> search(Embedding queryEmbedding,
                                                    int topK,
                                                    double minScore,
                                                    String tenantId) {
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(topK)
                .minScore(minScore)
                .build();
        List<EmbeddingMatch<TextSegment>> matches = storeFor(tenantId).search(request).matches();
        log.info("Found {} matches (tenant={})", matches.size(), tenantId);
        return matches;
    }

    @Override
    public List<RetrievalChunk> fetchAllChunks(int maxDocs) {
        return fetchAllChunks(maxDocs, TenantContext.DEFAULT_TENANT);
    }

    @Override
    public List<RetrievalChunk> fetchAllChunks(int maxDocs, String tenantId) {
        List<RetrievalChunk> tenantChunkList = chunksFor(tenantId);
        int limit = maxDocs > 0 ? Math.min(maxDocs, tenantChunkList.size()) : tenantChunkList.size();
        return new ArrayList<>(tenantChunkList.subList(0, limit));
    }

    @Override
    public void remove(String id) {
        // InMemoryEmbeddingStore doesn't support remove operation
        log.info("Remove operation not supported for in-memory store");
    }

    @Override
    public void removeAll() {
        chunks.clear();
    }

    @Override
    public void removeAll(String tenantId) {
        if (isDefaultTenant(tenantId)) {
            chunks.clear();
            return;
        }
        tenantChunks.remove(tenantId);
        tenantStores.remove(tenantId);
    }

    private InMemoryEmbeddingStore<TextSegment> storeFor(String tenantId) {
        return isDefaultTenant(tenantId)
                ? embeddingStore
                : tenantStores.computeIfAbsent(tenantId, key -> new InMemoryEmbeddingStore<>());
    }

    private List<RetrievalChunk> chunksFor(String tenantId) {
        return isDefaultTenant(tenantId)
                ? chunks
                : tenantChunks.computeIfAbsent(tenantId, key -> new CopyOnWriteArrayList<>());
    }

    private boolean isDefaultTenant(String tenantId) {
        return tenantId == null || TenantContext.DEFAULT_TENANT.equals(tenantId);
    }
}
