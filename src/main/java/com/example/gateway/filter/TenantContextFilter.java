package com.example.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * 在网关统一注入租户与链路标识。
 *
 * 约定：下游服务只信任网关注入的 X-Tenant-Id / X-Trace-Id，
 * 不自行解析 token、不自行猜租户。
 *
 * 生产环境这里应配合 JWT 校验（OAuth2 Resource Server）：
 * 先验签拿到 claim 里的 tenantId，再与请求头比对，不一致直接 403。
 * 当前 demo 为了便于验证限流，未带 token 时回落为 anonymous。
 */
@Component
public class TenantContextFilter implements GlobalFilter, Ordered {

    public static final String TENANT_HEADER = "X-Tenant-Id";
    public static final String TRACE_HEADER = "X-Trace-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String tenantHeader = exchange.getRequest().getHeaders().getFirst(TENANT_HEADER);
        String tenantId = (tenantHeader == null || tenantHeader.isBlank()) ? "anonymous" : tenantHeader;
        String traceId = resolveTraceId(exchange);

        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.set(TENANT_HEADER, tenantId);
                    headers.set(TRACE_HEADER, traceId);
                })
                .build();

        ServerWebExchange mutated = exchange.mutate().request(request).build();
        mutated.getResponse().getHeaders().set(TRACE_HEADER, traceId);

        String finalTenant = tenantId;
        return chain.filter(mutated)
                .contextWrite(ctx -> ctx.put("tenantId", finalTenant).put("traceId", traceId));
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
        return -2;
    }
}
