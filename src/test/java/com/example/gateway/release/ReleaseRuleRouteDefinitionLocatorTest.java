package com.example.gateway.release;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规则展开单测：N 路 targets+weights、0 权重跳过、旧格式等价、全 0 不崩、新旧 YAML 解析。
 * 直接调用 package-private {@link ReleaseRuleRouteDefinitionLocator#expandRules(ReleaseRules)}，不依赖 Nacos。
 */
class ReleaseRuleRouteDefinitionLocatorTest {

    private final ReleaseRuleRouteDefinitionLocator locator = new ReleaseRuleRouteDefinitionLocator(null);

    private static final String LEGACY_YAML = """
            services:
              springcloud-demo:
                mode: "canary"
                stable:
                  service: "springcloud-demo"
                  name: "stable"
                canary:
                  service: "springcloud-demo-canary"
                  name: "canary"
                routes:
                - match:
                    path: "/api/v1/demo/**"
                  split:
                    type: "weight"
                    stable: 85
                    canary: 15
            """;

    private static final String NEW_YAML = """
            services:
              springcloud-demo:
                mode: "multi-version"
                targets:
                  v1: { service: "springcloud-demo", name: "v1" }
                  v2: { service: "springcloud-demo-canary", name: "v2" }
                  v3: { service: "springcloud-demo-v3", name: "v3" }
                routes:
                - match: { path: "/api/v1/demo/**" }
                  split:
                    type: "weight"
                    weights: { v1: 70, v2: 20, v3: 10 }
            """;

    private static ReleaseRules.Variant variant(String service, String name) {
        ReleaseRules.Variant v = new ReleaseRules.Variant();
        v.setService(service);
        v.setName(name);
        return v;
    }

    /** 旧格式规则：stable 85 / canary 15 */
    private ReleaseRules legacyRules() {
        ReleaseRules rules = new ReleaseRules();
        ReleaseRules.ServiceRule svc = new ReleaseRules.ServiceRule();
        svc.setStable(variant("springcloud-demo", "stable"));
        svc.setCanary(variant("springcloud-demo-canary", "canary"));

        ReleaseRules.ReleaseRoute route = new ReleaseRules.ReleaseRoute();
        ReleaseRules.Match match = new ReleaseRules.Match();
        match.setPath("/api/v1/demo/**");
        ReleaseRules.Split split = new ReleaseRules.Split();
        split.setType("weight");
        split.setStable(85);
        split.setCanary(15);
        route.setMatch(match);
        route.setSplit(split);
        svc.setRoutes(List.of(route));

        Map<String, ReleaseRules.ServiceRule> services = new LinkedHashMap<>();
        services.put("springcloud-demo", svc);
        rules.setServices(services);
        return rules;
    }

    /** v2 格式规则：targets v1/v2/v3，权重按目标声明顺序传入 */
    private ReleaseRules multiTargetRules(int w1, int w2, int w3) {
        ReleaseRules rules = new ReleaseRules();
        ReleaseRules.ServiceRule svc = new ReleaseRules.ServiceRule();

        Map<String, ReleaseRules.Variant> targets = new LinkedHashMap<>();
        targets.put("v1", variant("springcloud-demo", "v1"));
        targets.put("v2", variant("springcloud-demo-canary", "v2"));
        targets.put("v3", variant("springcloud-demo-v3", "v3"));
        svc.setTargets(targets);

        ReleaseRules.ReleaseRoute route = new ReleaseRules.ReleaseRoute();
        ReleaseRules.Match match = new ReleaseRules.Match();
        match.setPath("/api/v1/demo/**");
        ReleaseRules.Split split = new ReleaseRules.Split();
        split.setType("weight");
        Map<String, Integer> weights = new LinkedHashMap<>();
        weights.put("v1", w1);
        weights.put("v2", w2);
        weights.put("v3", w3);
        split.setWeights(weights);
        route.setMatch(match);
        route.setSplit(split);
        svc.setRoutes(List.of(route));

        Map<String, ReleaseRules.ServiceRule> services = new LinkedHashMap<>();
        services.put("demo", svc);
        rules.setServices(services);
        return rules;
    }

    private static List<String> routeIds(List<RouteDefinition> routes) {
        return routes.stream().map(RouteDefinition::getId).toList();
    }

    private static PredicateDefinition splitPredicate(RouteDefinition route) {
        return route.getPredicates().get(1);
    }

    /** 0 权重的 target 不生成路由；梯子串仍完整保留（含 0 权重项） */
    @Test
    void zeroWeightTargetSkipped() {
        List<RouteDefinition> routes = locator.expandRules(multiTargetRules(70, 0, 30));

        assertEquals(2, routes.size());
        assertEquals(List.of("demo-v1-0", "demo-v3-0"), routeIds(routes));

        PredicateDefinition pd = splitPredicate(routes.get(0));
        assertEquals("ReleaseRoute", pd.getName());
        assertEquals("v1", pd.getArgs().get("role"));
        assertEquals("demo-w0", pd.getArgs().get("group"));
        assertEquals("v1:70,v2:0,v3:30", pd.getArgs().get("targets"));
    }

    /** 旧格式（stable:85 / canary:15）展开结果与改造前完全一致：id / uri / 谓词 args 逐项断言 */
    @Test
    void legacyFormatExpansionIdentical() {
        List<RouteDefinition> routes = locator.expandRules(legacyRules());
        assertEquals(2, routes.size());

        RouteDefinition stable = routes.get(0);
        assertEquals("springcloud-demo-stable-0", stable.getId());
        assertEquals("lb://springcloud-demo", stable.getUri().toString());
        PredicateDefinition stablePd = splitPredicate(stable);
        assertEquals("stable", stablePd.getArgs().get("role"));
        assertEquals("springcloud-demo-w0", stablePd.getArgs().get("group"));
        assertEquals("85", stablePd.getArgs().get("stableWeight"));

        RouteDefinition canary = routes.get(1);
        assertEquals("springcloud-demo-canary-0", canary.getId());
        assertEquals("lb://springcloud-demo-canary", canary.getUri().toString());
        PredicateDefinition canaryPd = splitPredicate(canary);
        assertEquals("canary", canaryPd.getArgs().get("role"));
        assertEquals("springcloud-demo-w0", canaryPd.getArgs().get("group"));
        assertEquals("85", canaryPd.getArgs().get("stableWeight"));
    }

    /** 标准 N 路展开：3 条路由，id / uri / group / 完整梯子串符合契约 */
    @Test
    void multiTargetExpansion() {
        List<RouteDefinition> routes = locator.expandRules(multiTargetRules(70, 20, 10));

        assertEquals(3, routes.size());
        assertEquals(List.of("demo-v1-0", "demo-v2-0", "demo-v3-0"), routeIds(routes));
        assertEquals("lb://springcloud-demo", routes.get(0).getUri().toString());
        assertEquals("lb://springcloud-demo-canary", routes.get(1).getUri().toString());
        assertEquals("lb://springcloud-demo-v3", routes.get(2).getUri().toString());

        for (RouteDefinition route : routes) {
            PredicateDefinition pd = splitPredicate(route);
            assertEquals("demo-w0", pd.getArgs().get("group"));
            assertEquals("v1:70,v2:20,v3:10", pd.getArgs().get("targets"));
        }
        assertEquals("v1", splitPredicate(routes.get(0)).getArgs().get("role"));
        assertEquals("v2", splitPredicate(routes.get(1)).getArgs().get("role"));
        assertEquals("v3", splitPredicate(routes.get(2)).getArgs().get("role"));
    }

    /** 全部权重为 0：不抛异常、不生成权重路由 */
    @Test
    void allZeroWeightsProduceNoWeightRoutes() {
        assertDoesNotThrow(() -> {
            List<RouteDefinition> routes = locator.expandRules(multiTargetRules(0, 0, 0));
            assertTrue(routes.isEmpty());
        });
    }

    /** 新旧两种规则 YAML 都能被解析成模型 */
    @Test
    void parsesLegacyAndNewYaml() throws Exception {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        ReleaseRules legacy = mapper.readValue(LEGACY_YAML, ReleaseRules.class);
        ReleaseRules.ServiceRule legacySvc = legacy.getServices().get("springcloud-demo");
        assertEquals("springcloud-demo", legacySvc.getStable().getService());
        assertEquals("springcloud-demo-canary", legacySvc.getCanary().getService());
        assertEquals(15, legacySvc.getRoutes().get(0).getSplit().getCanary());

        ReleaseRules fresh = mapper.readValue(NEW_YAML, ReleaseRules.class);
        ReleaseRules.ServiceRule freshSvc = fresh.getServices().get("springcloud-demo");
        assertEquals(3, freshSvc.getTargets().size());
        assertEquals("springcloud-demo-canary", freshSvc.getTargets().get("v2").getService());
        Map<String, Integer> weights = freshSvc.getRoutes().get(0).getSplit().getWeights();
        assertEquals(70, weights.get("v1"));
        assertEquals(20, weights.get("v2"));
        assertEquals(10, weights.get("v3"));
    }
}
