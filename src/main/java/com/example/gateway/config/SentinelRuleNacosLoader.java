package com.example.gateway.config;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Sentinel 网关限流规则从 Nacos 配置中心加载 + 监听变更。
 *
 * <p>dataId: springcloud-gateway-flow-rules，group: DEFAULT_GROUP，格式为 JSON 数组，
 * 例如：[{"resource":"springcloud-demo-route","resourceMode":0,"count":5,"intervalSec":1,
 * "paramItem":{"parseStrategy":2,"fieldName":"X-Tenant-Id","matchStrategy":0}}]
 *
 * <p>改动这份配置后立即生效，不需要重新构建镜像或重启 Pod。
 * 刻意不用 sentinel-datasource-nacos：那个包带的是 nacos-client 1.x，
 * 和 Nacos 3.x 服务端的鉴权/配置接口不兼容，这里复用 SCA 已经建好的 ConfigService。
 */
public class SentinelRuleNacosLoader {

    private static final Logger log = LoggerFactory.getLogger(SentinelRuleNacosLoader.class);

    private static final String DATA_ID = "springcloud-gateway-flow-rules";
    private static final String GROUP = "DEFAULT_GROUP";

    private final ObjectMapper objectMapper = new ObjectMapper();

    public SentinelRuleNacosLoader(ObjectProvider<NacosConfigManager> configManagerProvider) {
        NacosConfigManager configManager = configManagerProvider.getIfAvailable();
        if (configManager == null) {
            log.warn("NacosConfigManager 不可用，Sentinel 规则不会从配置中心加载");
            return;
        }

        ConfigService configService = configManager.getConfigService();
        try {
            apply(configService.getConfig(DATA_ID, GROUP, 5000), "startup");

            configService.addListener(DATA_ID, GROUP, new Listener() {
                @Override
                public Executor getExecutor() {
                    return null;
                }

                @Override
                public void receiveConfigInfo(String configInfo) {
                    apply(configInfo, "nacos-push");
                }
            });
            log.info("Sentinel 规则已订阅 Nacos: {}@{}", DATA_ID, GROUP);
        } catch (Exception e) {
            log.warn("订阅 Sentinel 规则失败: {}", e.getMessage());
        }
    }

    private void apply(String content, String from) {
        if (content == null || content.isBlank()) {
            log.warn("Nacos 上 {} 为空，跳过分加载（当前无自定义限流规则）", DATA_ID);
            return;
        }
        try {
            List<GatewayFlowRule> rules = objectMapper.readValue(
                    content, new TypeReference<List<GatewayFlowRule>>() {
                    });
            GatewayRuleManager.loadRules(new HashSet<>(rules));
            log.info("Sentinel 规则已更新（来源={}），共 {} 条", from, rules.size());
            rules.forEach(r -> log.info("  rule: resource={}, count={}/{}s", r.getResource(), r.getCount(), r.getIntervalSec()));
        } catch (Exception e) {
            log.error("解析/加载 Sentinel 规则失败，内容未生效: {}", e.getMessage());
        }
    }
}
