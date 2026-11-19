package com.example.gateway.release;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 把发布规则展开成标准的网关路由定义。
 *
 * <p>生成的顺序与优先级：
 * <ul>
 *   <li>规则生成的路由 order = -1，先于 Nacos 里 profile 路由（order = 0）匹配；</li>
 *   <li>fallback 路由（Path=/**，order = 0）留在 profile 配置里，自然排在最后；</li>
 *   <li>规则为空时本 locator 返回空列表，网关行为退化为原来的单条 lb:// 路由，零影响。</li>
 * </ul>
 */
@Component
public class ReleaseRuleRouteDefinitionLocator implements RouteDefinitionLocator {

    private static final Logger log = LoggerFactory.getLogger(ReleaseRuleRouteDefinitionLocator.class);

    /** 规则路由统一优先于 profile 中声明的路由 */
    private static final int RULE_ORDER = -1;

    private final NacosReleaseRuleLoader loader;

    public ReleaseRuleRouteDefinitionLocator(NacosReleaseRuleLoader loader) {
        this.loader = loader;
    }

    @Override
    public Flux<RouteDefinition> getRouteDefinitions() {
        return Flux.fromIterable(expand(loader.current()));
    }

    private List<RouteDefinition> expand(ReleaseRules rules) {
        List<RouteDefinition> out = new ArrayList<>();
        if (rules == null || rules.isEmpty()) {
            log.debug("发布规则为空，使用 profile 中的路由");
            return out;
        }
        for (Map.Entry<String, ReleaseRules.ServiceRule> entry : rules.getServices().entrySet()) {
            String key = entry.getKey();
            ReleaseRules.ServiceRule svc = entry.getValue();
            if (svc == null || svc.getRoutes() == null || svc.getRoutes().isEmpty()) {
                continue;
            }
            String stableService = resolveService(svc.getStable(), key);
            String canaryService = resolveService(svc.getCanary(), null);
            int index = 0;
            for (ReleaseRules.ReleaseRoute route : svc.getRoutes()) {
                String path = route.getMatch() == null ? null : route.getMatch().getPath();
                if (path == null || path.isBlank()) {
                    log.warn("规则缺少 match.path，跳过: service={}, index={}", key, index);
                    continue;
                }
                ReleaseRules.Split split = route.getSplit();
                String type = split == null || split.getType() == null ? "weight" : split.getType();
                switch (type) {
                    case "weight" -> expandWeight(out, key, index, path, stableService, canaryService, split);
                    case "header" -> expandConditional(out, key, index, path, stableService, canaryService,
                            route.getMatch(), false);
                    case "fixed-group" -> expandConditional(out, key, index, path, stableService, canaryService,
                            route.getMatch(), true);
                    default -> log.warn("未知的 split.type={}，跳过: service={}", type, key);
                }
                index++;
            }
        }
        if (!out.isEmpty()) {
            log.info("发布规则展开完成，生成 {} 条路由", out.size());
        }
        return out;
    }

    private String resolveService(ReleaseRules.Variant variant, String fallback) {
        if (variant == null || variant.getService() == null || variant.getService().isBlank()) {
            return fallback;
        }
        return variant.getService();
    }

    /** 按权重分流：生成 stable / canary 两条路由，共用 ReleaseRoute 谓词的同一随机数 */
    private void expandWeight(List<RouteDefinition> out, String key, int index, String path,
                              String stableService, String canaryService, ReleaseRules.Split split) {
        int canaryWeight = split.getCanary() == null ? 0 : split.getCanary();
        if (canaryWeight > 0 && canaryService == null) {
            log.warn("规则声明了 canary 权重但未配置 canary.service，金丝雀路由被忽略: service={}", key);
            canaryWeight = 0;
        }
        String group = key + "-w" + index;

        PredicateDefinition stablePredicate = predicate("ReleaseRoute", Map.of(
                "role", "stable",
                "group", group,
                "stableWeight", String.valueOf(100 - canaryWeight)));
        out.add(route(key + "-stable-" + index, stableService, order(RULE_ORDER),
                predicates(path, stablePredicate)));

        if (canaryWeight > 0) {
            PredicateDefinition canaryPredicate = predicate("ReleaseRoute", Map.of(
                    "role", "canary",
                    "group", group,
                    "stableWeight", String.valueOf(100 - canaryWeight)));
            out.add(route(key + "-canary-" + index, canaryService, order(RULE_ORDER),
                    predicates(path, canaryPredicate)));
        }
        log.info("权重分流: service={}, stable={}%, canary={}%", key, 100 - canaryWeight, canaryWeight);
    }

    /** 按请求头定向（灰度）或按哈希分桶（A/B）：生成互斥的两条路由 */
    private void expandConditional(List<RouteDefinition> out, String key, int index, String path,
                                   String stableService, String canaryService,
                                   ReleaseRules.Match match, boolean fixedGroup) {
        if (canaryService == null) {
            log.warn("条件分流缺少 canary.service，跳过: service={}", key);
            return;
        }
        String header;
        Map<String, String> canaryArgs = new java.util.LinkedHashMap<>();
        if (fixedGroup) {
            header = resolveGroupHeader(match);
            if (header == null) {
                log.warn("fixed-group 规则缺少 match.groupBy，跳过: service={}", key);
                return;
            }
            if (match.getBuckets() == null || match.getBuckets() <= 0 || match.getCanaryBuckets() == null
                    || match.getCanaryBuckets().isEmpty()) {
                log.warn("fixed-group 规则缺少 buckets/canaryBuckets，跳过: service={}", key);
                return;
            }
            canaryArgs.put("header", header);
            canaryArgs.put("buckets", String.valueOf(match.getBuckets()));
            canaryArgs.put("canaryBuckets", join(match.getCanaryBuckets()));
            canaryArgs.put("negate", "false");
        } else {
            header = match.getHeader();
            if (header == null || header.isBlank() || match.getValues() == null || match.getValues().isEmpty()) {
                log.warn("header 规则缺少 match.header/values，跳过: service={}", key);
                return;
            }
            canaryArgs.put("header", header);
            canaryArgs.put("values", join(match.getValues()));
            canaryArgs.put("negate", "false");
        }

        // 金丝雀：命中条件
        out.add(route(key + "-canary-" + index, canaryService, order(RULE_ORDER),
                predicates(path, predicate("ReleaseRoute", canaryArgs))));

        // 稳定：同一条件的取反
        Map<String, String> stableArgs = new java.util.LinkedHashMap<>(canaryArgs);
        stableArgs.put("negate", "true");
        out.add(route(key + "-stable-" + index, stableService, order(RULE_ORDER),
                predicates(path, predicate("ReleaseRoute", stableArgs))));

        log.info("条件分流({}): service={}, header={}, canaryService={}", fixedGroup ? "fixed-group" : "header",
                key, header, canaryService);
    }

    private String resolveGroupHeader(ReleaseRules.Match match) {
        String groupBy = match.getGroupBy();
        if (groupBy == null || groupBy.isBlank()) {
            return match.getHeader();
        }
        int idx = groupBy.indexOf(':');
        return idx < 0 ? groupBy.trim() : groupBy.substring(idx + 1).trim();
    }

    private List<PredicateDefinition> predicates(String path, PredicateDefinition splitPredicate) {
        List<PredicateDefinition> list = new ArrayList<>();
        list.add(predicate("Path", Map.of("pattern", path)));
        if (splitPredicate != null) {
            list.add(splitPredicate);
        }
        return list;
    }

    private PredicateDefinition predicate(String name, Map<String, String> args) {
        // 必须用无参构造 + setName：PredicateDefinition(String) 是按 "name=value" 短格式解析的，
        // 直接传 "Weight" 会抛 ValidationException（must be of the form name=value）
        PredicateDefinition pd = new PredicateDefinition();
        pd.setName(name);
        pd.setArgs(new java.util.LinkedHashMap<>(args));
        return pd;
    }

    private RouteDefinition route(String id, String service, int order, List<PredicateDefinition> predicates) {
        RouteDefinition rd = new RouteDefinition();
        rd.setId(id);
        rd.setUri(URI.create("lb://" + service));
        rd.setPredicates(predicates);
        rd.setOrder(order);
        rd.setMetadata(Map.of("release-rule", true));
        return rd;
    }

    private static int order(int value) {
        return value;
    }

    private static String join(List<?> list) {
        StringBuilder sb = new StringBuilder();
        for (Object o : list) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(o);
        }
        return sb.toString();
    }
}
