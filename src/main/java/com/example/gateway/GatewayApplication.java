package com.example.gateway;

import com.alibaba.csp.sentinel.init.InitExecutor;
import com.example.gateway.config.GatewayAuthProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(GatewayAuthProperties.class)
public class GatewayApplication {

    public static void main(String[] args) {
        // Sentinel Dashboard 接入：dashboard 地址由环境变量注入，未配置则只做本地限流
        String dashboard = System.getenv("SENTINEL_DASHBOARD");
        if (dashboard != null && !dashboard.isBlank()) {
            System.setProperty("csp.sentinel.dashboard.server", dashboard);
        }
        // 上报给 dashboard 的可达 IP（Pod 内取 Downward API 注入的 POD_IP）
        String podIp = System.getenv("POD_IP");
        if (podIp != null && !podIp.isBlank()) {
            System.setProperty("csp.sentinel.heartbeat.client.ip", podIp);
        }
        // 触发 Sentinel 初始化与心跳（规则由 config 包加载，dashboard 可远程改）
        InitExecutor.doInit();

        SpringApplication.run(GatewayApplication.class, args);
    }
}
