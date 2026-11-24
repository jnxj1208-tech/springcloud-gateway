package com.example.gateway.filter;

import com.example.gateway.config.GatewayAuthProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 网关鉴权过滤器单测：重点是"不可伪造"和"错误码语义"。
 */
class JwtAuthFilterTest {

    private static final String SECRET = "identity-dev-secret-change-me-please-32bytes";
    private static final String ISSUER = "photo-saas-identity";
    private static final String PATH = "/api/v1/identity/tenants/current";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private SecretKey key;
    private JwtAuthFilter filter;

    @BeforeEach
    void setUp() {
        key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        GatewayAuthProperties props = new GatewayAuthProperties();
        props.getJwt().setSecret(SECRET);
        props.getJwt().setIssuer(ISSUER);
        props.setWhitelist(List.of("/api/v1/identity/auth/**", "/actuator/**"));
        filter = new JwtAuthFilter(props, objectMapper);
    }

    @Test
    @DisplayName("无凭证 → 401 + 810001，且不继续走链路")
    void rejectsWithoutToken() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get(PATH).build());
        AtomicReference<ServerWebExchange> reached = new AtomicReference<>();

        filter.filter(exchange, capture(reached)).block();

        assertThat(reached.get()).isNull();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(body(exchange).get("code").asInt()).isEqualTo(810001);
    }

    @Test
    @DisplayName("伪造 X-Tenant-Id 无效：没有 token 一律 401，头被剥掉")
    void forgedTenantHeaderIsIgnored() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get(PATH).header(JwtAuthFilter.TENANT_HEADER, "999").build());
        AtomicReference<ServerWebExchange> reached = new AtomicReference<>();

        filter.filter(exchange, capture(reached)).block();

        assertThat(reached.get()).isNull();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("有效 token → 注入可信 X-Tenant-Id / X-Account-Id / X-Role-Codes，覆盖伪造值")
    void injectsTrustedHeaders() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get(PATH)
                        .header("Authorization", "Bearer " + accessToken())
                        .header(JwtAuthFilter.TENANT_HEADER, "999")
                        .header(JwtAuthFilter.ACCOUNT_HEADER, "888")
                        .build());
        AtomicReference<ServerWebExchange> reached = new AtomicReference<>();

        filter.filter(exchange, capture(reached)).block();

        assertThat(reached.get()).isNotNull();
        assertThat(reached.get().getRequest().getHeaders().getFirst(JwtAuthFilter.TENANT_HEADER)).isEqualTo("2002");
        assertThat(reached.get().getRequest().getHeaders().getFirst(JwtAuthFilter.ACCOUNT_HEADER)).isEqualTo("1001");
        assertThat(reached.get().getRequest().getHeaders().getFirst(JwtAuthFilter.ROLE_HEADER)).isEqualTo("TENANT_OWNER,PHOTOGRAPHER");
    }

    @Test
    @DisplayName("tempToken 不能访问租户资源 → 403 + 810003")
    void rejectsTempToken() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get(PATH)
                        .header("Authorization", "Bearer " + tempToken())
                        .build());

        filter.filter(exchange, capture(new AtomicReference<>())).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(body(exchange).get("code").asInt()).isEqualTo(810003);
    }

    @Test
    @DisplayName("签名不匹配 → 401 + 810002")
    void rejectsForgedSignature() {
        String foreign = Jwts.builder()
                .issuer(ISSUER)
                .claim("accountId", "1001")
                .claim("tenantId", "2002")
                .expiration(Date.from(Instant.now().plus(Duration.ofMinutes(30))))
                .signWith(Keys.hmacShaKeyFor("another-secret-another-secret-32byt".getBytes(StandardCharsets.UTF_8)))
                .compact();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get(PATH).header("Authorization", "Bearer " + foreign).build());

        filter.filter(exchange, capture(new AtomicReference<>())).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(body(exchange).get("code").asInt()).isEqualTo(810002);
    }

    @Test
    @DisplayName("白名单路径免验签，但伪造头依旧被剥掉")
    void whitelistPassThrough() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/identity/auth/login")
                        .header(JwtAuthFilter.TENANT_HEADER, "999")
                        .build());
        AtomicReference<ServerWebExchange> reached = new AtomicReference<>();

        filter.filter(exchange, capture(reached)).block();

        assertThat(reached.get()).isNotNull();
        assertThat(reached.get().getRequest().getHeaders().getFirst(JwtAuthFilter.TENANT_HEADER)).isNull();
    }

    private GatewayFilterChain capture(AtomicReference<ServerWebExchange> holder) {
        return exchange -> {
            holder.set(exchange);
            return Mono.empty();
        };
    }

    private JsonNode body(MockServerWebExchange exchange) {
        try {
            return objectMapper.readTree(exchange.getResponse().getBodyAsString().block());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String accessToken() {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .issuer(ISSUER)
                .subject("1001")
                .claim("accountId", "1001")
                .claim("tenantId", "2002")
                .claim("roleCodes", List.of("TENANT_OWNER", "PHOTOGRAPHER"))
                .claim("tenantStatus", "ACTIVE")
                .issuedAt(new Date(now))
                .expiration(new Date(now + Duration.ofMinutes(30).toMillis()))
                .signWith(key)
                .compact();
    }

    private String tempToken() {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .issuer(ISSUER)
                .subject("1001")
                .claim("accountId", "1001")
                .claim("type", "TEMP")
                .issuedAt(new Date(now))
                .expiration(new Date(now + Duration.ofMinutes(5).toMillis()))
                .signWith(key)
                .compact();
    }
}
