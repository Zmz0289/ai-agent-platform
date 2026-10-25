package com.aiagent.knowledge.infrastructure.vectorstore;

import com.aiagent.infrastructure.config.AiProperties;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TenantVectorIsolationTest {

    private AiProperties aiProperties;
    private MilvusVectorStoreService milvusVectorStoreService;

    @BeforeEach
    void setUp() {
        aiProperties = new AiProperties();
        aiProperties.getVectorStore().getMilvus().setCollectionName("ai_agent_documents");
        aiProperties.getTenant().setEnabled(true);
        milvusVectorStoreService = new MilvusVectorStoreService(aiProperties, Optional.empty());
    }

    @Test
    void shouldKeepBaseCollectionForDefaultTenant() {
        assertThat(milvusVectorStoreService.collectionNameFor("default"))
                .isEqualTo("ai_agent_documents");
    }

    @Test
    void shouldUseTenantSuffixedCollectionForOtherTenants() {
        assertThat(milvusVectorStoreService.collectionNameFor("acme"))
                .isEqualTo("ai_agent_documents_acme");
    }

    @Test
    void shouldFallBackToBaseCollectionWhenIsolationDisabled() {
        aiProperties.getTenant().setEnabled(false);

        assertThat(milvusVectorStoreService.collectionNameFor("acme"))
                .isEqualTo("ai_agent_documents");
    }

    @Test
    void shouldIsolateInMemoryVectorsByTenant() {
        InMemoryVectorStoreService store = new InMemoryVectorStoreService();
        Embedding embedding = new Embedding(new float[]{1.0f, 0.0f});
        store.addAll(List.of(embedding), List.of(TextSegment.from("tenant-a-doc")), "tenantA");

        assertThat(store.search(embedding, 5, 0.0, "tenantA")).hasSize(1);
        assertThat(store.search(embedding, 5, 0.0, "tenantB")).isEmpty();
        assertThat(store.search(embedding, 5, 0.0)).isEmpty();
        assertThat(store.fetchAllChunks(10, "tenantA")).hasSize(1);
        assertThat(store.fetchAllChunks(10, "tenantB")).isEmpty();
    }
}
