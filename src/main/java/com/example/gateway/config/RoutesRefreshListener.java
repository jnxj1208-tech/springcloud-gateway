package com.example.gateway.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Nacos 配置变更 → 刷新网关路由。
 *
 * <p>只改 Nacos 上的 springcloud-gateway.yaml 是不够的：Spring 只会 rebind
 * GatewayProperties，而 CachingRouteLocator 里已解析的路由缓存不会自动失效，
 * 请求仍走旧路由。这里把 EnvironmentChangeEvent 翻译成 RefreshRoutesEvent，
 * 让路由缓存真正重建，做到"改配置即生效"。
 */
@Component
public class RoutesRefreshListener {

    private static final Logger log = LoggerFactory.getLogger(RoutesRefreshListener.class);

    private final ApplicationEventPublisher publisher;

    public RoutesRefreshListener(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @EventListener(EnvironmentChangeEvent.class)
    public void onEnvironmentChange(EnvironmentChangeEvent event) {
        log.info("检测到配置变更（{} 项），刷新网关路由", event.getKeys().size());
        publisher.publishEvent(new RefreshRoutesEvent(this));
    }
}
