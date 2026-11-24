package com.example.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Set;
import java.util.UUID;

/**
 * 在网关统一注入链路标识，并剥掉客户端自带的身份头。
 *
 * 约定：下游服务只信任网关注入的 X-Tenant-Id / X-Account-Id / X-Role-Codes，
 * 不自行解析 token、不自行猜租户。
 *
 * 身份头的可信来源只有一个：JwtAuthFilter 验签后的 claim。
 * 本过滤器只做三件事：
 *   1) 删除入站请求里的 X-Tenant-Id / X-Account-Id / X-Role-Codes（防止伪造）
 *   2) 生成 / 复用 traceId，写入请求头与响应头
 *   3) 记录原始 path（forward 兜底路由会把 path 改写掉）
 *
 * 历史行为已废弃：`没有 token 就回落为 anonymous`。那等于把租户身份交给客户端，
 * 任何人加一个 X-Tenant-Id 就能跨租户读数据；现在改为不注入任何身份头，
 * 由下游返回 100011「缺少租户上下文」。
 */
@Component
public class TenantContextFilter implements GlobalFilter, Ordered {

    public static final String TENANT_HEADER = "X-Tenant-Id";
    public static final String TRACE_HEADER = "X-Trace-Id";
    /** 原始请求路径：兜底路由 forward 之后 path 会被改写，靠它把真实路径带过去。 */
    public static final String ORIGINAL_PATH_HEADER = "X-Original-Path";
    public static final String ACCOUNT_HEADER = "X-Account-Id";
    public static final String ROLE_HEADER = "X-Role-Codes";

    private static final Set<String> UNTRUSTED_HEADERS = Set.of(TENANT_HEADER, ACCOUNT_HEADER, ROLE_HEADER);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String traceId = resolveTraceId(exchange);
        String originalPath = exchange.getRequest().getPath().value();

        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> {
                    UNTRUSTED_HEADERS.forEach(headers::remove);
                    headers.set(TRACE_HEADER, traceId);
                    headers.set(ORIGINAL_PATH_HEADER, originalPath);
                })
                .build();

        ServerWebExchange mutated = exchange.mutate().request(request).build();
        mutated.getResponse().getHeaders().set(TRACE_HEADER, traceId);

        return chain.filter(mutated)
                .contextWrite(ctx -> ctx.put("traceId", traceId));
    }

    private String resolveTraceId(ServerWebExchange exchange) {
        // 优先复用上游 W3C traceparent 的 trace-id 段，保证与 OTel 链路一致
        String traceparent = exchange.getRequest().getHeaders().getFirst("traceparent");
        if (traceparent != null) {
            String[] parts = traceparent.split("-");
            if (parts.length >= 3 && parts[1].length() == 32) {
                return parts[1];
            }
        }
        String existing = exchange.getRequest().getHeaders().getFirst(TRACE_HEADER);
        if (existing != null && !existing.isBlank()) {
            return existing;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    @Override
    public int getOrder() {
        // 必须早于 JwtAuthFilter(-3)：先把伪造头清掉，再由鉴权过滤器写入可信值
        return -4;
    }
}
