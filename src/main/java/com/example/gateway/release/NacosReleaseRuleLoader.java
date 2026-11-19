package com.example.gateway.release;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 从 Nacos 加载发布规则（dataId: springcloud-release-rules, group: DEFAULT_GROUP, 类型 YAML）。
 *
 * <p>与 Sentinel 规则加载器同一套路：复用 SCA 已建好的 ConfigService，避免引入
 * nacos-client 1.x 的 datasource 包。规则变更后发布 RefreshRoutesEvent，
 * 让 CachingRouteLocator 丢弃缓存、重新调用 {@link ReleaseRuleRouteDefinitionLocator}。
 *
 * <p>解析失败时保留上一次成功的规则（宁可用旧规则，也不要因为一次手抖把路由全清空）。
 */
@Component
public class NacosReleaseRuleLoader {

    private static final Logger log = LoggerFactory.getLogger(NacosReleaseRuleLoader.class);

    public static final String DATA_ID = "springcloud-release-rules";
    public static final String GROUP = "DEFAULT_GROUP";

    private final AtomicReference<ReleaseRules> current = new AtomicReference<>(new ReleaseRules());
    private final ObjectMapper mapper = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final ApplicationEventPublisher publisher;

    public NacosReleaseRuleLoader(ObjectProvider<NacosConfigManager> configManagerProvider,
                                  ApplicationEventPublisher publisher) {
        this.publisher = publisher;
        NacosConfigManager configManager = configManagerProvider.getIfAvailable();
        if (configManager == null) {
            log.warn("NacosConfigManager 不可用，发布规则不会从配置中心加载（仅使用本地路由）");
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
            log.info("发布规则已订阅 Nacos: {}@{}", DATA_ID, GROUP);
        } catch (Exception e) {
            log.warn("订阅发布规则失败: {}", e.getMessage());
        }
    }

    /** 当前生效的规则；永不返回 null */
    public ReleaseRules current() {
        return current.get();
    }

    private void apply(String content, String from) {
        if (content == null || content.isBlank()) {
            log.warn("Nacos 上 {} 为空，保持现有发布规则不变", DATA_ID);
            return;
        }
        try {
            ReleaseRules parsed = mapper.readValue(content, ReleaseRules.class);
            if (parsed == null) {
                log.warn("发布规则解析结果为空，忽略（来源={}）", from);
                return;
            }
            current.set(parsed);
            int n = parsed.getServices() == null ? 0 : parsed.getServices().size();
            log.info("发布规则已更新（来源={}），服务数={}", from, n);
            parsed.getServices().forEach((k, v) -> log.info("  rule: service={}, mode={}, routes={}",
                    k, v.getMode(), v.getRoutes() == null ? 0 : v.getRoutes().size()));
            // 路由缓存需要重建，否则请求仍走旧路由
            publisher.publishEvent(new RefreshRoutesEvent(this));
        } catch (Exception e) {
            log.error("解析发布规则失败，保留上一次生效的规则: {}", e.getMessage());
        }
    }
}
