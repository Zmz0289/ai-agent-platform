package com.aiagent.rag.application;

import com.aiagent.infrastructure.config.AiProperties;
import com.aiagent.shared.prompt.SafePromptBuilder;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * L1 查询扩展 + L2 多意图分解。
 *
 * <p>先用便宜规则判断是否值得付出一次 LLM 调用（问号数量、问题长度、并列连接词），
 * 命中后用<b>一次</b> LLM 调用同时产出：
 * <ul>
 *   <li>L1 expansions：同义/改写检索式，提升召回；</li>
 *   <li>L2 subQueries：相互独立的信息需求，分别召回后再融合。</li>
 * </ul>
 * 任何失败都降级为单 query 检索，不影响主链路可用性。
 */
@Slf4j
@Component
public class QueryPlanner {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final int MAX_QUERY_CHARS = 200;

    /** 并列/转折连接词：出现即说明一句话里可能塞了多个信息需求。 */
    private static final List<String> MULTI_INTENT_HINTS = List.of(
            "并且", "同时", "另外", "还有", "以及", "顺便", "分别", "各自",
            "除此之外", "一方面", "另一方面", "再就是", "而且");

    private static final String PLANNER_INSTRUCTION_TEMPLATE = """
            你是检索查询规划器，只输出 JSON，不要回答问题。
            任务：为知识库检索生成辅助查询。

            规则：
            1. expansions：0~%d 条与原问题语义等价的检索式，用于提升召回；不得引入用户未提到的事实、数字或承诺。
            2. subQueries：仅当用户请求包含多个相互独立的明确信息需求时才输出，0~%d 条；每条必须自带完整主语与限定条件（对象、时间、条件），单意图时输出空数组。
            3. 子问题必须能脱离上下文独立检索；不得输出"这个""它""那么"等依赖上下文的指代。
            4. 不要输出编号、解释、Markdown 或除 JSON 以外的任何内容。

            输出格式：
            {"expansions":["..."],"subQueries":["..."],"reason":"不超过50字的判断依据"}
            """;

    private final AiProperties aiProperties;
    private final ChatLanguageModel chatLanguageModel;
    private final Executor ioTaskExecutor;

    public QueryPlanner(AiProperties aiProperties,
                        ChatLanguageModel chatLanguageModel,
                        @Qualifier("ioTaskExecutor") Executor ioTaskExecutor) {
        this.aiProperties = aiProperties;
        this.chatLanguageModel = chatLanguageModel;
        this.ioTaskExecutor = ioTaskExecutor;
    }

    public QueryPlanResult plan(String question) {
        String normalized = AdaptiveRagTextSupport.normalize(question);
        AiProperties.QueryPlan config = aiProperties.getRag().getQueryPlan();
        if (!config.isEnabled()) {
            return QueryPlanResult.single(normalized, "query_plan_disabled");
        }
        if (!StringUtils.hasText(normalized)) {
            return QueryPlanResult.single(normalized, "empty_question");
        }
        if (config.isGateEnabled() && !shouldPlan(normalized, config)) {
            return QueryPlanResult.single(normalized, "gate_skipped");
        }

        try {
            String raw = callPlanner(normalized, config);
            QueryPlanPayload payload = parsePayload(raw);
            if (payload == null) {
                log.debug("Query planner returned an unparsable payload");
                return QueryPlanResult.single(normalized, "unparsable_response");
            }

            List<String> subQueries = config.isDecompositionEnabled()
                    ? sanitize(payload.getSubQueries(), normalized, config.getMaxSubQueries())
                    : List.of();
            List<String> expansions = config.isExpansionEnabled()
                    ? sanitize(payload.getExpansions(), normalized, config.getMaxExpansions())
                    : List.of();
            List<String> retrievalQueries = merge(
                    normalized, subQueries, expansions, config.getMaxQueries());
            if (retrievalQueries.size() <= 1) {
                return QueryPlanResult.single(normalized, "no_variants_returned");
            }

            String reason = StringUtils.hasText(payload.getReason())
                    ? payload.getReason().trim()
                    : "query_planned";
            log.debug("Query planned: subQueries={}, expansions={}, reason={}",
                    subQueries, expansions, reason);
            return QueryPlanResult.builder()
                    .originalQuery(normalized)
                    .planned(true)
                    .fallback(false)
                    .reason(reason)
                    .subQueries(subQueries)
                    .expansions(expansions)
                    .retrievalQueries(retrievalQueries)
                    .build();
        } catch (Exception exception) {
            log.warn("Query planning failed, falling back to single query: {}", exception.getMessage());
            QueryPlanResult fallback = QueryPlanResult.single(normalized, "planner_error");
            fallback.setFallback(true);
            return fallback;
        }
    }

    /**
     * 便宜门控：多问号 / 长问题 / 并列连接词，任一命中才调用 LLM。
     */
    boolean shouldPlan(String question, AiProperties.QueryPlan config) {
        if (config.isDecompositionEnabled()
                && countQuestionMarks(question) >= Math.max(1, config.getGateMinQuestionMarks())) {
            return true;
        }
        if (question.length() >= Math.max(1, config.getGateMinLength())) {
            return true;
        }
        return question.length() >= Math.max(1, config.getGateMinHintLength())
                && AdaptiveRagTextSupport.containsAny(question, MULTI_INTENT_HINTS);
    }

    private int countQuestionMarks(String text) {
        int count = 0;
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current == '?' || current == '？') {
                count++;
            }
        }
        return count;
    }

    private String callPlanner(String question, AiProperties.QueryPlan config) throws Exception {
        String instruction = PLANNER_INSTRUCTION_TEMPLATE.formatted(
                Math.max(0, config.getMaxExpansions()),
                Math.max(0, config.getMaxSubQueries()));
        String prompt = SafePromptBuilder.create()
                .trustedInstruction(instruction)
                .userRequest(question)
                .trustedInstruction("现在只输出 JSON 对象本身。")
                .build();

        CompletableFuture<String> future = CompletableFuture.supplyAsync(
                () -> chatLanguageModel.generate(prompt), ioTaskExecutor);
        try {
            return future.get(Math.max(1, config.getLlmTimeoutMs()), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new IllegalStateException("query planner timed out", exception);
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("query planner interrupted", exception);
        }
    }

    private QueryPlanPayload parsePayload(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(raw.substring(start, end + 1), QueryPlanPayload.class);
        } catch (Exception exception) {
            log.debug("Unable to parse query plan JSON: {}", exception.getMessage());
            return null;
        }
    }

    /**
     * 过滤空值、超长、JSON 残留、与原问题重复以及内部重复的检索式。
     */
    private List<String> sanitize(List<String> candidates, String original, int limit) {
        if (candidates == null || candidates.isEmpty() || limit <= 0) {
            return List.of();
        }
        String originalKey = normalizeKey(original);
        Map<String, String> unique = new LinkedHashMap<>();
        for (String candidate : candidates) {
            if (!StringUtils.hasText(candidate)) {
                continue;
            }
            String cleaned = candidate.replaceAll("\\s+", " ").trim();
            if (cleaned.length() < 2 || cleaned.length() > MAX_QUERY_CHARS) {
                continue;
            }
            if (cleaned.indexOf('{') >= 0 || cleaned.indexOf('}') >= 0 || cleaned.indexOf('"') >= 0) {
                continue;
            }
            String key = normalizeKey(cleaned);
            if (key.equals(originalKey)) {
                continue;
            }
            unique.putIfAbsent(key, cleaned);
            if (unique.size() >= limit) {
                break;
            }
        }
        return List.copyOf(unique.values());
    }

    private List<String> merge(String original,
                               List<String> subQueries,
                               List<String> expansions,
                               int maxQueries) {
        Map<String, String> merged = new LinkedHashMap<>();
        merged.put(normalizeKey(original), original);
        subQueries.forEach(query -> merged.putIfAbsent(normalizeKey(query), query));
        expansions.forEach(query -> merged.putIfAbsent(normalizeKey(query), query));
        return merged.values().stream().limit(Math.max(1, maxQueries)).toList();
    }

    private String normalizeKey(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }

    @Data
    @NoArgsConstructor
    private static class QueryPlanPayload {
        private List<String> expansions;
        private List<String> subQueries;
        private String reason;
    }
}
