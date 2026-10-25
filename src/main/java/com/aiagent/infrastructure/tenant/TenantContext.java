package com.aiagent.infrastructure.tenant;

import org.springframework.util.StringUtils;

import java.util.concurrent.Callable;
import java.util.regex.Pattern;

/**
 * 当前请求的租户上下文。
 *
 * <p>租户 ID 由 {@link TenantFilter} 从请求头解析并绑定到线程；异步任务需要显式
 * 通过 {@link #wrap(Callable)} / {@link #wrap(Runnable)} 传递，避免上下文丢失后
 * 退化成默认租户造成越权。</p>
 */
public final class TenantContext {

    public static final String DEFAULT_TENANT = "default";

    /** 限制字符集，确保可以直接拼进 Milvus collection 名称与 Redis key。 */
    private static final Pattern VALID_TENANT = Pattern.compile("[A-Za-z0-9_]{1,32}");

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static String currentTenant() {
        String tenant = CURRENT.get();
        return StringUtils.hasText(tenant) ? tenant : DEFAULT_TENANT;
    }

    public static boolean isDefaultTenant() {
        return DEFAULT_TENANT.equals(currentTenant());
    }

    /**
     * 校验并归一化租户 ID；非法值直接拒绝，避免静默落到默认租户形成越权访问。
     */
    public static String resolve(String rawTenant, String defaultTenant) {
        String fallback = StringUtils.hasText(defaultTenant) && VALID_TENANT.matcher(defaultTenant).matches()
                ? defaultTenant
                : DEFAULT_TENANT;
        if (!StringUtils.hasText(rawTenant)) {
            return fallback;
        }
        String normalized = rawTenant.trim();
        if (!VALID_TENANT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "Invalid tenant id; only letters, digits and underscore are allowed (max 32 characters)");
        }
        return normalized;
    }

    static void set(String tenant) {
        CURRENT.set(tenant);
    }

    static void clear() {
        CURRENT.remove();
    }

    /** 把当前租户绑定到异步任务，任务结束后恢复调用线程原有的上下文。 */
    public static <T> Callable<T> wrap(Callable<T> task) {
        String captured = currentTenant();
        return () -> {
            String previous = CURRENT.get();
            CURRENT.set(captured);
            try {
                return task.call();
            } finally {
                restore(previous);
            }
        };
    }

    public static Runnable wrap(Runnable task) {
        String captured = currentTenant();
        return () -> {
            String previous = CURRENT.get();
            CURRENT.set(captured);
            try {
                task.run();
            } finally {
                restore(previous);
            }
        };
    }

    private static void restore(String previous) {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }
}
