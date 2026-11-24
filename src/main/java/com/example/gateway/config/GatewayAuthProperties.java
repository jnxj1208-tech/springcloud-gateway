package com.example.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 网关鉴权配置。
 *
 * 对应 application.yml / Nacos 里的 gateway.auth.*：
 *   gateway:
 *     auth:
 *       enabled: true
 *       whitelist: [/api/v1/identity/auth/**, /actuator/**]
 *       jwt:
 *         secret: ${JWT_SECRET}
 *         issuer: photo-saas-identity
 */
@ConfigurationProperties(prefix = "gateway.auth")
public class GatewayAuthProperties {

    /**
     * 总开关。false 时网关只做 trace 透传，不做验签（仅限本地调试，
     * 生产必须 true，否则下游又回到"谁都能自报租户"的状态）。
     */
    private boolean enabled = true;

    /** 免验签路径，支持 Ant 风格通配：/api/v1/identity/auth/** 。 */
    private List<String> whitelist = new ArrayList<>(List.of(
            "/api/v1/identity/auth/**",
            "/actuator/**",
            "/__fallback"
    ));

    private final Jwt jwt = new Jwt();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getWhitelist() {
        return whitelist;
    }

    public void setWhitelist(List<String> whitelist) {
        this.whitelist = whitelist;
    }

    public Jwt getJwt() {
        return jwt;
    }

    public static class Jwt {

        /** 与 identity-service 共用同一个密钥（K8s Secret identity-secret/jwt-secret）。 */
        private String secret = "";

        /** 签发方，必须与 identity 的 identity.jwt.issuer 一致。 */
        private String issuer = "photo-saas-identity";

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }
    }
}
