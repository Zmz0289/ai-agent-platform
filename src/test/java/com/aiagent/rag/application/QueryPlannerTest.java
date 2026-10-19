package com.aiagent.rag.application;

import com.aiagent.infrastructure.config.AiProperties;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueryPlannerTest {

    private static final String MULTI_INTENT_QUESTION = "今天能发货吗？具体什么时候发货？什么时候到货？";

    @Mock
    private ChatLanguageModel chatLanguageModel;

    private AiProperties aiProperties;
    private QueryPlanner queryPlanner;

    @BeforeEach
    void setUp() {
        aiProperties = new AiProperties();
        aiProperties.getRag().getQueryPlan().setEnabled(true);
        queryPlanner = new QueryPlanner(aiProperties, chatLanguageModel, Runnable::run);
    }

    @Test
    void shouldSkipLlmWhenGateDoesNotTrigger() {
        QueryPlanResult result = queryPlanner.plan("RAG 是什么");

        assertThat(result.isPlanned()).isFalse();
        assertThat(result.getReason()).isEqualTo("gate_skipped");
        assertThat(result.getRetrievalQueries()).containsExactly("RAG 是什么");
        verifyNoInteractions(chatLanguageModel);
    }

    @Test
    void shouldSkipLlmWhenQueryPlanDisabled() {
        aiProperties.getRag().getQueryPlan().setEnabled(false);

        QueryPlanResult result = queryPlanner.plan(MULTI_INTENT_QUESTION);

        assertThat(result.isPlanned()).isFalse();
        assertThat(result.getReason()).isEqualTo("query_plan_disabled");
        verifyNoInteractions(chatLanguageModel);
    }

    @Test
    void shouldProduceSubQueriesBeforeExpansions() {
        when(chatLanguageModel.generate(anyString())).thenReturn("""
                ```json
                {"expansions":["发货时效"],"subQueries":["什么时候发货","什么时候到货"],"reason":"multi intent"}
                ```
                """);

        QueryPlanResult result = queryPlanner.plan(MULTI_INTENT_QUESTION);

        assertThat(result.isPlanned()).isTrue();
        assertThat(result.isFallback()).isFalse();
        assertThat(result.getReason()).isEqualTo("multi intent");
        assertThat(result.getSubQueries()).containsExactly("什么时候发货", "什么时候到货");
        assertThat(result.getExpansions()).containsExactly("发货时效");
        assertThat(result.getRetrievalQueries()).containsExactly(
                MULTI_INTENT_QUESTION, "什么时候发货", "什么时候到货", "发货时效");
        assertThat(result.isMultiQuery()).isTrue();
    }

    @Test
    void shouldCapVariantsAndDropDuplicatesAndOriginalEcho() {
        aiProperties.getRag().getQueryPlan().setMaxSubQueries(2);
        aiProperties.getRag().getQueryPlan().setMaxExpansions(1);
        when(chatLanguageModel.generate(anyString())).thenReturn("""
                {"subQueries":["什么时候发货","什么时候发货","什么时候到货","什么时候退款"],
                 "expansions":["发货时效","到货时效"]}
                """);

        QueryPlanResult result = queryPlanner.plan(MULTI_INTENT_QUESTION);

        assertThat(result.getSubQueries()).containsExactly("什么时候发货", "什么时候到货");
        assertThat(result.getExpansions()).containsExactly("发货时效");
        assertThat(result.getRetrievalQueries()).hasSize(4);
    }

    @Test
    void shouldIgnoreVariantsWhenBothCapabilitiesDisabled() {
        aiProperties.getRag().getQueryPlan().setExpansionEnabled(false);
        aiProperties.getRag().getQueryPlan().setDecompositionEnabled(false);
        String question = MULTI_INTENT_QUESTION + "另外请说明运费以及发票开具方式。";
        when(chatLanguageModel.generate(anyString())).thenReturn(
                "{\"subQueries\":[\"什么时候发货\"],\"expansions\":[\"发货时效\"]}");

        QueryPlanResult result = queryPlanner.plan(question);

        assertThat(result.isPlanned()).isFalse();
        assertThat(result.getReason()).isEqualTo("no_variants_returned");
        assertThat(result.getRetrievalQueries()).containsExactly(question);
    }

    @Test
    void shouldFallBackToSingleQueryWhenLlmFails() {
        when(chatLanguageModel.generate(anyString())).thenThrow(new IllegalStateException("llm down"));

        QueryPlanResult result = queryPlanner.plan(MULTI_INTENT_QUESTION);

        assertThat(result.isPlanned()).isFalse();
        assertThat(result.isFallback()).isTrue();
        assertThat(result.getReason()).isEqualTo("planner_error");
        assertThat(result.getRetrievalQueries()).containsExactly(MULTI_INTENT_QUESTION);
    }

    @Test
    void shouldFallBackWhenResponseIsNotJson() {
        when(chatLanguageModel.generate(anyString())).thenReturn("我认为这个问题包含两个子问题。");

        QueryPlanResult result = queryPlanner.plan(MULTI_INTENT_QUESTION);

        assertThat(result.isPlanned()).isFalse();
        assertThat(result.getReason()).isEqualTo("unparsable_response");
    }
}
