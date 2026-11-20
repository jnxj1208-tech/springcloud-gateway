package com.example.gateway.release;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;

import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ReleaseRoute 谓词单测：覆盖 N 路 targets 区间边界与旧 stable/canary 二分分支。
 */
class ReleaseRoutePredicateFactoryTest {

    private static final String LADDER = "v1:70,v2:20,v3:10";
    private static final String GROUP = "g1";

    private ServerWebExchange exchangeWithBucket(int bucket) {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/demo/x"));
        exchange.getAttributes().put("release.bucket." + GROUP, bucket);
        return exchange;
    }

    private Predicate<ServerWebExchange> multiTargetPredicate(String role) {
        ReleaseRoutePredicateFactory.Config config = new ReleaseRoutePredicateFactory.Config();
        config.setRole(role);
        config.setGroup(GROUP);
        config.setTargets(LADDER);
        return new ReleaseRoutePredicateFactory().apply(config);
    }

    private Predicate<ServerWebExchange> legacyPredicate(String role, int stableWeight) {
        ReleaseRoutePredicateFactory.Config config = new ReleaseRoutePredicateFactory.Config();
        config.setRole(role);
        config.setGroup(GROUP);
        config.setStableWeight(stableWeight);
        return new ReleaseRoutePredicateFactory().apply(config);
    }

    /**
     * 三组权重 v1:70 / v2:20 / v3:10 的区间边界：
     * 1-70 v1、71-90 v2、91-100 v3。预置 bucket 为 69/70/71/90/91，
     * 断言分别只有 v1、v1、v2、v2、v3 命中，且同一随机数下三条路由互斥、恰好一条命中。
     */
    @Test
    void boundaryBucketsHitExactlyOneTarget() {
        List<String> roles = List.of("v1", "v2", "v3");
        // bucket → 期望命中的下标（0=v1, 1=v2, 2=v3）
        int[][] cases = {{69, 0}, {70, 0}, {71, 1}, {90, 1}, {91, 2}};

        for (int[] c : cases) {
            int bucket = c[0];
            int expected = c[1];
            ServerWebExchange exchange = exchangeWithBucket(bucket);

            long hits = 0;
            for (int i = 0; i < roles.size(); i++) {
                boolean hit = multiTargetPredicate(roles.get(i)).test(exchange);
                if (hit) {
                    hits++;
                    assertEquals(roles.get(expected), roles.get(i),
                            "bucket=" + bucket + " 应命中 " + roles.get(expected));
                }
            }
            assertEquals(1, hits, "bucket=" + bucket + " 三条路由必须恰好一条命中");
        }
    }

    /** 旧格式二分分支：stableWeight=85 时 1-85 stable、86-100 canary，行为保持原样 */
    @Test
    void legacyBinaryBranchUnchanged() {
        Predicate<ServerWebExchange> stable = legacyPredicate("stable", 85);
        Predicate<ServerWebExchange> canary = legacyPredicate("canary", 85);

        assertTrue(stable.test(exchangeWithBucket(85)));
        assertFalse(canary.test(exchangeWithBucket(85)));
        assertFalse(stable.test(exchangeWithBucket(86)));
        assertTrue(canary.test(exchangeWithBucket(86)));
    }
}
