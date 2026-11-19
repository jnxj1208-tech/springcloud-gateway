package com.example.gateway.config;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.csp.sentinel.adapter.gateway.sc.SentinelGatewayFilter;
import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.GatewayCallbackManager;
import com.alibaba.csp.sentinel.adapter.gateway.sc.exception.SentinelGatewayBlockExceptionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.result.view.ViewResolver;

import java.util.List;
import java.util.Map;

/**
 * Sentinel 网关模式装配。
 *
 * <p>限流规则不再硬编码在代码里，而是从 Nacos 配置中心加载（见 {@link SentinelRuleNacosLoader}），
 * 改阈值/加规则只需改 Nacos 配置，不需要重新构建镜像。
 */
@Configuration
public class SentinelGatewayConfig {

    private static final Logger log = LoggerFactory.getLogger(SentinelGatewayConfig.class);

    private final List<ViewResolver> viewResolvers;
    private final ServerCodecConfigurer serverCodecConfigurer;

    public SentinelGatewayConfig(ObjectProvider<List<ViewResolver>> viewResolversProvider,
                                 ServerCodecConfigurer serverCodecConfigurer) {
        this.viewResolvers = viewResolversProvider.getIfAvailable(List::of);
        this.serverCodecConfigurer = serverCodecConfigurer;
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SentinelGatewayBlockExceptionHandler sentinelGatewayBlockExceptionHandler() {
        return new SentinelGatewayBlockExceptionHandler(viewResolvers, serverCodecConfigurer);
    }

    @Bean
    @Order(-1)
    public GlobalFilter sentinelGatewayFilter() {
        return new SentinelGatewayFilter();
    }

    @Bean
    public SentinelBlockHandlerInitializer sentinelBlockHandlerInitializer() {
        return new SentinelBlockHandlerInitializer();
    }

    @Bean
    public SentinelRuleNacosLoader sentinelRuleNacosLoader(ObjectProvider<NacosConfigManager> provider) {
        return new SentinelRuleNacosLoader(provider);
    }

    /**
     * 自定义 429 响应。Sentinel 1.8.9 默认的 DefaultBlockRequestHandler 调用了
     * Spring 6 的 MediaType.sortBySpecificityAndQuality()，Spring Framework 7（Boot 4）
     * 已移除该方法，触发限流时会抛 NoSuchMethodError 变成 502。
     */
    static class SentinelBlockHandlerInitializer {
        SentinelBlockHandlerInitializer() {
            GatewayCallbackManager.setBlockHandler((exchange, t) -> {
                String tenant = exchange.getRequest().getHeaders().getFirst("X-Tenant-Id");
                String path = exchange.getRequest().getPath().value();
                log.warn("rate limited: tenant={}, path={}, rule={}", tenant, path, t.getClass().getSimpleName());
                return ServerResponse.status(HttpStatus.TOO_MANY_REQUESTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(BodyInserters.fromValue(Map.of(
                                "code", 429,
                                "message", "rate limited by sentinel",
                                "tenant", tenant == null ? "anonymous" : tenant,
                                "rule", t.getClass().getSimpleName())));
            });
        }
    }
}
