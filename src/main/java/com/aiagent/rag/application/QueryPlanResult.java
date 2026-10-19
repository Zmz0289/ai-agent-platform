package com.aiagent.rag.application;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 查询规划结果：L1 扩展检索式 + L2 独立子问题。
 *
 * <p>{@code retrievalQueries} 已去重并保证原问题在首位，可直接交给多路召回。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryPlanResult {

    private String originalQuery;
    /** 是否真正调用了 LLM 规划（被门控跳过或关闭时为 false）。 */
    private boolean planned;
    /** 规划调用失败后是否降级为单 query。 */
    private boolean fallback;
    /** 规划依据或降级原因，用于 trace 与排查。 */
    private String reason;
    private List<String> subQueries;
    private List<String> expansions;
    /** 原问题 + 子问题 + 扩展检索式（去重后，原问题在首位）。 */
    private List<String> retrievalQueries;

    public boolean isMultiQuery() {
        return retrievalQueries != null && retrievalQueries.size() > 1;
    }

    public static QueryPlanResult single(String question, String reason) {
        String normalized = question == null ? "" : question;
        return QueryPlanResult.builder()
                .originalQuery(normalized)
                .planned(false)
                .fallback(false)
                .reason(reason)
                .subQueries(List.of())
                .expansions(List.of())
                .retrievalQueries(List.of(normalized))
                .build();
    }
}
