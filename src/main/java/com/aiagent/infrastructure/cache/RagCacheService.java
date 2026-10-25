package com.aiagent.infrastructure.cache;

import com.aiagent.infrastructure.metrics.PlatformMetricsService;
import com.aiagent.infrastructure.tenant.TenantContext;
import com.aiagent.knowledge.domain.RetrievalChunk;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagCacheService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final PlatformMetricsService metricsService;

    private static final String CACHE_PREFIX = "ai:rag-cache:";
    private static final long CACHE_TTL_HOURS = 1;

    @PostConstruct
    public void init() {
        log.info("RAG cache service initialized, TTL: {}h", CACHE_TTL_HOURS);
    }

    @SuppressWarnings("unchecked")
    public List<RetrievalChunk> getCachedResults(String query) {
        String key = cacheKey(query);
        List<RetrievalChunk> result = CacheExceptionHandler.safeRead("RAG", () -> {
            Object cached = redisTemplate.opsForValue().get(key);
            return cached instanceof List ? (List<RetrievalChunk>) cached : null;
        });
        metricsService.recordRagCache(result != null);
        metricsService.recordCacheOperation("rag", "get", result != null);
        return result;
    }
    public void cacheResults(String query, List<RetrievalChunk> results) {
        if (results == null || results.isEmpty()) {
            return;
        }
        String key = cacheKey(query);
        CacheExceptionHandler.safeWrite("RAG", () -> {
            redisTemplate.opsForValue().set(key, results, CACHE_TTL_HOURS, TimeUnit.HOURS);
        });
    }

    /**
     * default 租户沿用原有 key（兼容存量缓存），其他租户增加租户前缀，避免跨租户命中召回结果。
     */
    private String cacheKey(String query) {
        String tenant = TenantContext.currentTenant();
        String prefix = TenantContext.DEFAULT_TENANT.equals(tenant)
                ? CACHE_PREFIX
                : CACHE_PREFIX + tenant + ":";
        return CacheKeyUtil.buildKey(prefix, query);
    }
}
