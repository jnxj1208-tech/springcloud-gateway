package com.example.gateway.filter;

import com.example.gateway.config.GatewayAuthProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 网关鉴权：验 accessToken，把身份写进下游头。
 *
 * 为什么必须在网关做：
 *   下游服务只信 X-Tenant-Id（TenantContextFilter 直接 getHeader），
 *   网关不验签就等于租户由客户端自报，任何人加一个 header 就能跨租户读数据。
 *
 * 执行顺序（数字越小越早）：
 *   -4 TenantContextFilter  剥离伪造头 + 生成 traceId + 记录原始 path
 *   -3 JwtAuthFilter        验签 + 注入可信身份头
 *   之后才是 Sentinel / 路由
 *
 * 注入的三个头与 platform-starters 的 photo-saas-starter-tenant 约定一致：
 *   X-Tenant-Id / X-Account-Id / X-Role-Codes
 *
 * 错误码遵循平台规范（系统域 8=网关 / 大类 1=认证）：
 *   810001 缺少凭证        401
 *   810002 凭证无效        401
 *   810003 临时令牌不可用  403（必须先 select-tenant 换正式 token）
 *   810004 令牌缺少租户    403
 *   810005 租户已注销      403
 *   890001 密钥未配置      500（启动即失败，运行期兜底）
 */
@Component
public class JwtAuthFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

    public static final String TENANT_HEADER = "X-Tenant-Id";
    public static final String ACCOUNT_HEADER = "X-Account-Id";
    public static final String ROLE_HEADER = "X-Role-Codes";

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String CLAIM_ACCOUNT = "accountId";
    private static final String CLAIM_TENANT = "tenantId";
    private static final String CLAIM_ROLES = "roleCodes";
    private static final String CLAIM_TENANT_STATUS = "tenantStatus";
    private static final String CLAIM_TYPE = "type";
    private static final String TYPE_TEMP = "TEMP";
    private static final String STATUS_CLOSED = "CLOSED";

    private static final PathPatternParser PATH_PARSER = new PathPatternParser();
    /** 请求里永远不该被信任的头，无论哪个分支都要先删掉 */
    private static final Set<String> UNTRUSTED_HEADERS = Set.of(TENANT_HEADER, ACCOUNT_HEADER, ROLE_HEADER);

    private final GatewayAuthProperties props;
    /**
     * 自带 Jackson 2 实例：Spring Boot 4 的自动配置默认提供的是 Jackson 3 的 ObjectMapper，
     * com.fasterxml 的 ObjectMapper 不再是可注入的 bean（注入会直接启动失败）。
     * 这里只用来序列化一个极小的错误体，自己 new 一个即可。
     */
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SecretKey key;
    private final List<PathPattern> whitelist;

    public JwtAuthFilter(GatewayAuthProperties props) {
        this.props = props;
        this.whitelist = props.getWhitelist() == null
                ? List.of()
                : props.getWhitelist().stream().map(PATH_PARSER::parse).toList();

        String secret = props.getJwt().getSecret();
        boolean usable = secret != null && secret.getBytes(StandardCharsets.UTF_8).length >= 32;
        if (props.isEnabled() && !usable) {
            // 宁可启动失败也不要静默放行：放行的代价是任意越权
            throw new IllegalStateException(
                    "gateway.auth.enabled=true 但 JWT_SECRET 缺失或不足 32 字节（当前 "
                            + (secret == null ? 0 : secret.getBytes(StandardCharsets.UTF_8).length) + " 字节）");
        }
        this.key = usable ? Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)) : null;
        log.info("网关鉴权已启用 enabled={} issuer={} whitelist={}",
                props.isEnabled(), props.getJwt().getIssuer(), whitelist.size());
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 无论走哪个分支，客户端自带的身份头一律剥掉，避免被下游误用
        ServerHttpRequest.Builder sanitized = exchange.getRequest().mutate()
                .headers(headers -> UNTRUSTED_HEADERS.forEach(headers::remove));

        if (!props.isEnabled()) {
            return chain.filter(exchange.mutate().request(sanitized.build()).build());
        }

        String path = exchange.getRequest().getPath().value();
        if (isWhitelisted(path)) {
            return chain.filter(exchange.mutate().request(sanitized.build()).build());
        }

        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return reject(exchange, HttpStatus.UNAUTHORIZED, 810001, "缺少登录凭证，请重新登录");
        }

        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(props.getJwt().getIssuer())
                    .build()
                    .parseSignedClaims(authorization.substring(BEARER_PREFIX.length()).trim())
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("token 验签失败 path={} reason={}", path, e.getClass().getSimpleName());
            return reject(exchange, HttpStatus.UNAUTHORIZED, 810002, "登录状态已失效，请重新登录");
        }

        if (TYPE_TEMP.equals(claims.get(CLAIM_TYPE, String.class))) {
            return reject(exchange, HttpStatus.FORBIDDEN, 810003, "请先选择租户后再访问");
        }

        String tenantId = claims.get(CLAIM_TENANT, String.class);
        String accountId = claims.get(CLAIM_ACCOUNT, String.class);
        if (isBlank(tenantId) || isBlank(accountId)) {
            return reject(exchange, HttpStatus.FORBIDDEN, 810004, "登录凭证缺少租户信息，请重新选择租户");
        }

        if (STATUS_CLOSED.equals(claims.get(CLAIM_TENANT_STATUS, String.class))) {
            return reject(exchange, HttpStatus.FORBIDDEN, 810005, "租户已注销");
        }

        String roles = joinRoles(claims.get(CLAIM_ROLES));

        ServerHttpRequest request = sanitized
                .headers(headers -> {
                    headers.set(TENANT_HEADER, tenantId);
                    headers.set(ACCOUNT_HEADER, accountId);
                    if (!roles.isEmpty()) {
                        headers.set(ROLE_HEADER, roles);
                    }
                })
                .build();

        ServerWebExchange mutated = exchange.mutate().request(request).build();
        return chain.filter(mutated)
                .contextWrite(ctx -> ctx.put("tenantId", tenantId).put("accountId", accountId));
    }

    private boolean isWhitelisted(String path) {
        for (PathPattern pattern : whitelist) {
            if (pattern.matches(org.springframework.http.server.PathContainer.parsePath(path))) {
                return true;
            }
        }
        return false;
    }

    private static String joinRoles(Object raw) {
        if (raw instanceof List<?> list) {
            return list.stream().map(String::valueOf).filter(s -> !s.isBlank()).reduce((a, b) -> a + "," + b).orElse("");
        }
        return raw == null ? "" : String.valueOf(raw);
    }

    private static boolean isBlank(String v) {
        return v == null || v.isBlank();
    }

    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, int code, String message) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", OffsetDateTime.now().toString());
        body.put("status", status.value());
        body.put("code", code);
        body.put("message", message);
        body.put("data", null);
        body.put("path", exchange.getRequest().getPath().value());
        body.put("traceId", exchange.getRequest().getHeaders().getFirst(TenantContextFilter.TRACE_HEADER));

        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            bytes = ("{\"code\":" + code + ",\"message\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8);
        }
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        // 必须晚于 TenantContextFilter(-4)：它负责剥离伪造头与生成 traceId
        return -3;
    }
}
