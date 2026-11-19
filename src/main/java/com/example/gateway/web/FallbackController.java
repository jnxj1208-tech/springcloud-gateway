package com.example.gateway.web;

import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 网关兜底响应：路由表中最后一条 `Path=/**` 会 forward 到这里。
 *
 * 为什么不用 nginx 的默认 404：nginx 返回 HTML，客户端拿到一个看不懂的页面，
 * 排查时也拿不到 traceId。统一成 JSON 后，调用方和日志能用同一个 traceId 对齐。
 *
 * 注意：这个路径（/__fallback）不要出现在对外文档里，它只是内部约定。
 */
@RestController
public class FallbackController {

    @RequestMapping("/__fallback")
    public ResponseEntity<Map<String, Object>> fallback(ServerWebExchange exchange) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", OffsetDateTime.now().toString());
        body.put("status", HttpStatus.NOT_FOUND.value());
        body.put("error", "Not Found");
        body.put("message", "no route matched; expected path prefix /api/v{n}/{domain}/...");
        body.put("path", originalPath(exchange));
        body.put("method", exchange.getRequest().getMethod().name());
        body.put("traceId", exchange.getRequest().getHeaders().getFirst("X-Trace-Id"));
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    /**
     * forward 之后 request 的 path 已经变成 /__fallback，
     * 真正被请求的原始路径存在 gateway 的 attribute 里，取它才是对调用方有用的信息。
     */
    private String originalPath(ServerWebExchange exchange) {
        List<URI> originals = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ORIGINAL_REQUEST_URL_ATTR);
        if (originals != null && !originals.isEmpty()) {
            URI last = originals.get(originals.size() - 1);
            if (last.getPath() != null && !last.getPath().isBlank()) {
                return last.getPath();
            }
        }
        return exchange.getRequest().getPath().value();
    }
}
