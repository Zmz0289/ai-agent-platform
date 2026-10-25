package com.aiagent.knowledge.infrastructure.vectorstore;

import com.aiagent.knowledge.domain.RetrievalChunk;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;

import java.util.List;

public interface VectorStoreService {
    default boolean isAvailable() {
        return true;
    }

    default boolean isWriteAvailable() {
        return true;
    }

    void add(String id, Embedding embedding);

    default void add(String id, Embedding embedding, String tenantId) {
        add(id, embedding);
    }

    List<String> addAll(List<Embedding> embeddings, List<TextSegment> segments);
    List<EmbeddingMatch<TextSegment>> search(Embedding queryEmbedding, int topK, double minScore);

    /**
     * 租户感知的写入。默认实现忽略租户，由支持物理/逻辑隔离的向量服务覆盖。
     */
    default List<String> addAll(List<Embedding> embeddings, List<TextSegment> segments, String tenantId) {
        return addAll(embeddings, segments);
    }

    /**
     * 租户感知的检索。默认实现忽略租户，由支持隔离的向量服务覆盖。
     */
    default List<EmbeddingMatch<TextSegment>> search(Embedding queryEmbedding,
                                                     int topK,
                                                     double minScore,
                                                     String tenantId) {
        return search(queryEmbedding, topK, minScore);
    }

    /**
     * 在精选 FAQ 库中检索（级联检索第一层）。
     * 默认实现不检索 FAQ 库，由支持 FAQ collection 的向量服务覆盖。
     */
    default List<EmbeddingMatch<TextSegment>> searchFaq(Embedding queryEmbedding, int topK, double minScore) {
        return List.of();
    }

    List<RetrievalChunk> fetchAllChunks(int maxDocs);

    default List<RetrievalChunk> fetchAllChunks(int maxDocs, String tenantId) {
        return fetchAllChunks(maxDocs);
    }

    void remove(String id);

    default void remove(String id, String tenantId) {
        remove(id);
    }

    void removeAll();

    default void removeAll(String tenantId) {
        removeAll();
    }
}
