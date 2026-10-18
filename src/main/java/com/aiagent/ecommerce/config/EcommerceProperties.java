package com.aiagent.ecommerce.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "ecommerce")
public class EcommerceProperties {

    private Milvus milvus = new Milvus();
    private Import importConfig = new Import();
    private Generator generator = new Generator();

    @Data
    public static class Milvus {
        private String collection = "ecommerce_qa";
        private int dimension = 1024;
    }

    @Data
    public static class Import {
        /** 每个批次处理的记录数（embedding 批量 + DB 批量）。 */
        private int batchSize = 64;
        /** 批次间隔毫秒，0 表示不等待（仅用于限速本地服务）。 */
        private int batchIntervalMs = 0;
        /** 是否启用内容哈希去重（record_hash 唯一索引兜底）。 */
        private boolean deduplicate = true;
        /** 意图分类器配置。 */
        private Classifier classifier = new Classifier();
    }

    @Data
    public static class Classifier {
        private boolean enabled = true;
        /** 类别 -> 关键词列表；question 命中权重 2，answer 命中权重 1。 */
        private Map<String, List<String>> keywords = new LinkedHashMap<>();
    }

    @Data
    public static class Generator {
        private int targetPerCategory = 500;
        private int batchSize = 5;
        private List<String> categories;

        /** 生成模型提供方：local(Ollama) | doubao | deepseek | qianwen。 */
        private String provider = "local";
        /** 以下三项留空时复用 ai.model.&lt;provider&gt; 中的同名配置。 */
        private String baseUrl;
        private String apiKey;
        private String modelName;
        /** 单次调用最大输出 token；本地小模型批量生成时不宜过大，避免截断。 */
        private int maxTokens = 2048;
        private double temperature = 0.5;
        /** 本地大模型生成较慢，默认给足 180 秒。 */
        private int timeoutSeconds = 180;
        /** 每次调用后的间隔毫秒，本地模型可设为 0。 */
        private long requestIntervalMs = 500;
    }
}
