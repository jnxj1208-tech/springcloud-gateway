package com.example.gateway.config;

import com.alibaba.csp.sentinel.adapter.gateway.common.SentinelGatewayConstants;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayParamFlowItem;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
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

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sentinel 网关模式。
 *
 * 两个资源维度：
 *  1) ROUTE_ID：整条路由的总 QPS 兜底
 *  2) ROUTE_ID + 请求参数（X-Tenant-Id 头）：按租户限流
 *
 * 规则启动时内置一份（保证冷启动即可用），运行期间可通过 Sentinel Dashboard
 * 直接推送覆盖（默认内存态，重启丢失；生产改为 Nacos datasource 持久化）。
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
    public SentinelRulesInitializer sentinelRulesInitializer() {
        return new SentinelRulesInitializer();
    }

    /** 初始化网关限流规则 */
    static class SentinelRulesInitializer {
        SentinelRulesInitializer() {
            initGatewayRules();
        }

        private void initGatewayRules() {
            Set<GatewayFlowRule> rules = new HashSet<>();

            // 1. 路由级兜底：整条路由 20 QPS
            rules.add(new GatewayFlowRule("springcloud-demo-route")
                    .setResourceMode(SentinelGatewayConstants.RESOURCE_MODE_ROUTE_ID)
                    .setCount(20)
                    .setIntervalSec(1));

            // 2. 租户级：同一租户 5 QPS，按 X-Tenant-Id 请求头精确匹配
            rules.add(new GatewayFlowRule("springcloud-demo-route")
                    .setResourceMode(SentinelGatewayConstants.RESOURCE_MODE_ROUTE_ID)
                    .setCount(5)
                    .setIntervalSec(1)
                    .setParamItem(new GatewayParamFlowItem()
                            .setParseStrategy(SentinelGatewayConstants.PARAM_PARSE_STRATEGY_HEADER)
                            .setFieldName("X-Tenant-Id")
                            .setMatchStrategy(SentinelGatewayConstants.PARAM_MATCH_STRATEGY_EXACT)));

            GatewayRuleManager.loadRules(rules);
            log.info("Sentinel gateway rules loaded: {}", rules.size());

            // 必须自定义 429 响应：Sentinel 1.8.9 默认的 DefaultBlockRequestHandler
            // 调用了 Spring 6 的 MediaType.sortBySpecificityAndQuality()，
            // Spring Framework 7（Boot 4）已移除该方法，触发限流时会抛 NoSuchMethodError 变成 502
            GatewayCallbackManager.setBlockHandler((exchange, t) -> {
                String tenant = exchange.getRequest().getHeaders().getFirst("X-Tenant-Id");
                log.warn("rate limited: tenant={}, exception={}", tenant, t.getClass().getSimpleName());
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
