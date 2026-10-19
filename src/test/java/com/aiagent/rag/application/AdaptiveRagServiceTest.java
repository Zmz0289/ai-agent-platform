package com.aiagent.rag.application;

import com.aiagent.infrastructure.config.AiProperties;
import com.aiagent.infrastructure.metrics.PlatformMetricsService;
import com.aiagent.knowledge.domain.RetrievalChunk;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdaptiveRagServiceTest {

    @Mock
    private MultiRecallService multiRecallService;

    @Mock
    private QueryPlanner queryPlanner;

    @Mock
    private PlatformMetricsService metricsService;

    private AdaptiveRagService adaptiveRagService;

    @BeforeEach
    void setUp() {
        AiProperties aiProperties = new AiProperties();
        aiProperties.getRag().getAdaptive().setEnabled(true);
        aiProperties.getRag().setTopK(5);
        aiProperties.getRag().setSimilarityThreshold(0.7);
        when(metricsService.startSample()).thenReturn(Timer.start());
        lenient().when(queryPlanner.plan(anyString()))
                .thenAnswer(invocation -> QueryPlanResult.single(
                        invocation.getArgument(0), "gate_skipped"));

        adaptiveRagService = new AdaptiveRagService(
                aiProperties,
                multiRecallService,
                new QueryRouter(aiProperties),
                new QueryRewriter(aiProperties),
                queryPlanner,
                new SelfRagVerifier(aiProperties),
                (query, chunks) -> withSemanticScores(chunks, 0.95),
                metricsService
        );
    }

    @Test
    void shouldBypassRetrievalForDirectQuestions() {
        AdaptiveRagContext context = adaptiveRagService.resolve("hello");

        assertThat(context.getRouteType()).isEqualTo(RagRouteType.DIRECT_ANSWER);
        assertThat(context.getContext()).isEmpty();
        verify(multiRecallService, never()).search(anyString(), anyInt());
    }

    @Test
    void shouldRewriteAndRetrieveForSingleHopQuestions() {
        when(multiRecallService.search(anyString(), eq(5))).thenReturn(List.of(
                RetrievalChunk.builder().id("doc-1").content("RAG 是一种检索增强生成方法").metadata(Map.of()).build()
        ));

        AdaptiveRagContext context = adaptiveRagService.resolve("RAG 是什么");

        assertThat(context.getRouteType()).isEqualTo(RagRouteType.SINGLE_HOP);
        assertThat(context.getRetrievalRounds()).isEqualTo(1);
        assertThat(context.getContext()).contains("RAG 是一种检索增强生成方法");
        assertThat(context.getContext()).doesNotContain("Adaptive RAG route");
        assertThat(context.getRewrittenQuery()).contains("RAG");
        verify(multiRecallService).search(anyString(), eq(5));
        verify(metricsService).recordAdaptiveRag(eq("SINGLE_HOP"), eq("HIGH"), anyBoolean(), eq(1), eq(1), eq("verification_high"), eq(true), any());
    }

    @Test
    void shouldRetryForMultiHopQuestionsWhenEvidenceIsWeak() {
        when(multiRecallService.search(anyString(), eq(5)))
                .thenReturn(List.of(RetrievalChunk.builder().id("doc-1").content("random text").metadata(Map.of()).build()))
                .thenReturn(List.of(RetrievalChunk.builder().id("doc-2").content("RRF 和加权融合的区别在于排序机制").metadata(Map.of()).build()));

        AdaptiveRagContext context = adaptiveRagService.resolve("RRF 和加权融合的区别是什么");

        assertThat(context.getRouteType()).isEqualTo(RagRouteType.MULTI_HOP);
        assertThat(context.getRetrievalRounds()).isEqualTo(2);
        assertThat(context.getContext()).contains("RRF 和加权融合的区别在于排序机制");
        verify(multiRecallService, times(2)).search(anyString(), eq(5));
        verify(metricsService).recordAdaptiveRag(eq("MULTI_HOP"), eq("HIGH"), anyBoolean(), eq(2), eq(1), eq("verification_high"), eq(true), any());
    }

    @Test
    void shouldUseMultiQueryRetrievalWhenPlannerReturnsVariants() {
        String question = "今天能发货吗？具体什么时候发货？什么时候到货？";
        when(queryPlanner.plan(question)).thenReturn(QueryPlanResult.builder()
                .originalQuery(question)
                .planned(true)
                .fallback(false)
                .reason("multiple questions")
                .subQueries(List.of("什么时候发货", "什么时候到货"))
                .expansions(List.of("发货时效"))
                .retrievalQueries(List.of(question, "什么时候发货", "什么时候到货", "发货时效"))
                .build());
        when(multiRecallService.searchMulti(anyList(), eq(5))).thenReturn(List.of(
                RetrievalChunk.builder().id("doc-1").content("一般 48 小时内发货，3 天到货").metadata(Map.of()).build()));

        AdaptiveRagContext context = adaptiveRagService.resolve(question);

        assertThat(context.getContext()).contains("48 小时");
        verify(multiRecallService).searchMulti(anyList(), eq(5));
        verify(multiRecallService, never()).search(anyString(), anyInt());
        assertThat(context.getRoundTraces().get(0).getRetrievalQueries())
                .containsExactly(question, "什么时候发货", "什么时候到货", "发货时效");
        assertThat(context.getRoundTraces().get(0).getQueryPlanReason()).isEqualTo("multiple questions");
    }

    @Test
    void shouldRespectSingleHopPlannedRoundLimitWhenEvidenceStaysWeak() {
        when(multiRecallService.search(anyString(), eq(5))).thenReturn(List.of(
                RetrievalChunk.builder().id("doc-1").content("无关内容").metadata(Map.of()).build()
        ));

        AdaptiveRagContext context = adaptiveRagService.resolve("RAG 是什么");

        assertThat(context.getRouteType()).isEqualTo(RagRouteType.SINGLE_HOP);
        assertThat(context.getRetrievalRounds()).isEqualTo(1);
        verify(multiRecallService, times(1)).search(anyString(), eq(5));
    }

    private List<RetrievalChunk> withSemanticScores(List<RetrievalChunk> chunks, double score) {
        return chunks.stream().map(chunk -> {
            Map<String, Object> metadata = new java.util.LinkedHashMap<>();
            if (chunk.getMetadata() != null) {
                metadata.putAll(chunk.getMetadata());
            }
            metadata.put(SemanticEvidenceReranker.SEMANTIC_SCORE_METADATA, score);
            return RetrievalChunk.builder()
                    .id(chunk.getId())
                    .content(chunk.getContent())
                    .score(chunk.getScore())
                    .metadata(metadata)
                    .build();
        }).toList();
    }
}
