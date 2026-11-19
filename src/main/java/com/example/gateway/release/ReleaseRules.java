package com.example.gateway.release;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 发布规则模型：来自 Nacos 配置 {@code springcloud-release-rules}（YAML）。
 *
 * <p>设计原则：规则只描述「谁是新版本、按什么条件分流」，不描述具体实现。
 * 网关启动后由 {@link NacosReleaseRuleLoader} 载入，{@link ReleaseRuleRouteDefinitionLocator}
 * 展开为标准 RouteDefinition，因此 Sentinel 流控、TraceId、fallback 全部照旧生效。
 *
 * <p>示例：
 * <pre>
 * release:
 *   services:
 *     springcloud-demo:
 *       mode: canary
 *       stable:
 *         service: springcloud-demo
 *       canary:
 *         service: springcloud-demo-canary
 *       routes:
 *         - match:
 *             path: /api/v1/demo/**
 *           split:
 *             type: weight
 *             stable: 85
 *             canary: 15
 * </pre>
 */
public class ReleaseRules {

    private Map<String, ServiceRule> services = new LinkedHashMap<>();

    public Map<String, ServiceRule> getServices() {
        return services;
    }

    public void setServices(Map<String, ServiceRule> services) {
        this.services = services == null ? new LinkedHashMap<>() : services;
    }

    public boolean isEmpty() {
        return services == null || services.isEmpty();
    }

    /** 一个微服务的发布规则 */
    public static class ServiceRule {
        /** none | multi-batch | canary | gray | bluegreen | ab */
        private String mode = "none";
        private Variant stable;
        private Variant canary;
        private List<ReleaseRoute> routes = new ArrayList<>();

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public Variant getStable() {
            return stable;
        }

        public void setStable(Variant stable) {
            this.stable = stable;
        }

        public Variant getCanary() {
            return canary;
        }

        public void setCanary(Variant canary) {
            this.canary = canary;
        }

        public List<ReleaseRoute> getRoutes() {
            return routes;
        }

        public void setRoutes(List<ReleaseRoute> routes) {
            this.routes = routes == null ? new ArrayList<>() : routes;
        }
    }

    /** 一个版本在 Nacos 中的服务名 */
    public static class Variant {
        private String service;
        /** 可读名称，仅用于日志与路由 id */
        private String name;

        public String getService() {
            return service;
        }

        public void setService(String service) {
            this.service = service;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    /** 一条分流规则 */
    public static class ReleaseRoute {
        private Match match;
        private Split split;

        public Match getMatch() {
            return match;
        }

        public void setMatch(Match match) {
            this.match = match;
        }

        public Split getSplit() {
            return split;
        }

        public void setSplit(Split split) {
            this.split = split;
        }
    }

    /** 匹配条件 */
    public static class Match {
        /** 路径前缀，例如 /api/v1/demo/** */
        private String path;
        /** 定向灰度的请求头名，例如 X-Tenant-Id */
        private String header;
        /** 命中该头的取值白名单 */
        private List<String> values = new ArrayList<>();
        /** 固定分组依据，形如 header:X-User-Id */
        private String groupBy;
        /** 分桶总数，例如 100 */
        private Integer buckets;
        /** 命中金丝雀的桶号，支持区间写法，例如 ["0-49"] 或 [0, 49, "60-70"] */
        private List<String> canaryBuckets = new ArrayList<>();

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public String getHeader() {
            return header;
        }

        public void setHeader(String header) {
            this.header = header;
        }

        public List<String> getValues() {
            return values;
        }

        public void setValues(List<String> values) {
            this.values = values == null ? new ArrayList<>() : values;
        }

        public String getGroupBy() {
            return groupBy;
        }

        public void setGroupBy(String groupBy) {
            this.groupBy = groupBy;
        }

        public Integer getBuckets() {
            return buckets;
        }

        public void setBuckets(Integer buckets) {
            this.buckets = buckets;
        }

        public List<String> getCanaryBuckets() {
            return canaryBuckets;
        }

        public void setCanaryBuckets(List<String> canaryBuckets) {
            this.canaryBuckets = canaryBuckets == null ? new ArrayList<>() : canaryBuckets;
        }
    }

    /** 分流方式 */
    public static class Split {
        /** weight | header | fixed-group */
        private String type = "weight";
        /** 稳定版权重（0-100） */
        private Integer stable = 100;
        /** 金丝雀权重（0-100） */
        private Integer canary = 0;

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public Integer getStable() {
            return stable;
        }

        public void setStable(Integer stable) {
            this.stable = stable;
        }

        public Integer getCanary() {
            return canary;
        }

        public void setCanary(Integer canary) {
            this.canary = canary;
        }
    }
}
