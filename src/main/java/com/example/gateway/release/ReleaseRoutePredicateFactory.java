package com.example.gateway.release;

import org.springframework.cloud.gateway.handler.predicate.AbstractRoutePredicateFactory;
import org.springframework.cloud.gateway.handler.predicate.RoutePredicateFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 自定义谓词：按请求头做「定向灰度」与「固定分组」判定。
 *
 * <p>为什么必须自己写：SCG 内置的 Header 谓词无法表达
 * <ul>
 *   <li>取反（稳定版路由需要「不在白名单」语义）</li>
 *   <li>哈希分桶（A/B 测试要求同一用户永远落同一桶，Weight 谓词是随机数，做不到）</li>
 * </ul>
 *
 * <p>配置参数：
 * <pre>
 * header       必填，参与判定的请求头
 * values       可选，命中白名单（逗号分隔）
 * buckets      可选，分桶总数
 * canaryBuckets 可选，命中金丝雀的桶号（逗号分隔）
 * negate       可选，true 表示取反（稳定版路由用）
 * </pre>
 */
@Component
public class ReleaseRoutePredicateFactory extends AbstractRoutePredicateFactory<ReleaseRoutePredicateFactory.Config> {

    public ReleaseRoutePredicateFactory() {
        super(Config.class);
    }

    @Override
    public Predicate<ServerWebExchange> apply(Config config) {
        final String role = config.getRole();
        // 权重模式：同一请求只掷一次骰子，存在 exchange attributes 里，两条路由共享
        if (role != null && !role.isBlank()) {
            final String group = config.getGroup() == null ? "default" : config.getGroup();
            final int stableWeight = config.getStableWeight() == null ? 100 : config.getStableWeight();
            final boolean canaryRole = "canary".equalsIgnoreCase(role);
            final String attrKey = "release.bucket." + group;
            return exchange -> {
                Integer r = (Integer) exchange.getAttributes().get(attrKey);
                if (r == null) {
                    r = java.util.concurrent.ThreadLocalRandom.current().nextInt(100) + 1; // 1..100
                    exchange.getAttributes().put(attrKey, r);
                }
                boolean stableHit = r <= stableWeight;
                return canaryRole != stableHit;
            };
        }

        final String header = config.getHeader();
        final List<String> values = parseList(config.getValues());
        final int buckets = config.getBuckets() == null ? 0 : config.getBuckets();
        final java.util.Set<Integer> canaryBuckets = parseBuckets(config.getCanaryBuckets());
        final boolean negate = Boolean.parseBoolean(config.getNegate());

        return exchange -> {
            String v = exchange.getRequest().getHeaders().getFirst(header);
            boolean in;
            if (!values.isEmpty()) {
                in = v != null && values.contains(v);
            } else if (buckets > 0) {
                // 用 CRC32 而不是 String.hashCode：后者对 "user-1/user-2/..." 这类规律取值分布很差，
                // 会导致某个桶区间几乎没人命中，A/B 比例失真
                in = v != null && canaryBuckets.contains(bucketOf(v, buckets));
            } else {
                in = false;
            }
            return negate != in;
        };
    }

    /** 一致性哈希分桶：同一取值永远落同一桶，且分布均匀 */
    private static int bucketOf(String value, int buckets) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return (int) Math.floorMod(crc.getValue(), buckets);
    }

    /** 解析桶集合，支持 "0-49" 区间与逐个列出的混合写法 */
    private static java.util.Set<Integer> parseBuckets(String raw) {
        java.util.Set<Integer> out = new java.util.HashSet<>();
        for (String s : parseList(raw)) {
            int dash = s.indexOf('-');
            try {
                if (dash > 0) {
                    int from = Integer.parseInt(s.substring(0, dash).trim());
                    int to = Integer.parseInt(s.substring(dash + 1).trim());
                    for (int i = from; i <= to; i++) {
                        out.add(i);
                    }
                } else {
                    out.add(Integer.parseInt(s));
                }
            } catch (NumberFormatException e) {
                // 忽略非法桶号，避免一条脏配置把整个路由搞崩
            }
        }
        return out;
    }

    private static List<String> parseList(String raw) {        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String s : raw.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /** 该谓词在 Nacos 里引用时的名字：ReleaseRoute */
    @Override
    public String name() {
        return "ReleaseRoute";
    }

    public static class Config {
        private String header;
        private String values;
        private Integer buckets;
        private String canaryBuckets;
        private String negate;
        /** 权重模式：stable / canary */
        private String role;
        /** 权重模式：随机分组标识，同一请求共享同一个随机数 */
        private String group;
        /** 权重模式：stable 版本百分比（1-100） */
        private Integer stableWeight;

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }

        public String getGroup() {
            return group;
        }

        public void setGroup(String group) {
            this.group = group;
        }

        public Integer getStableWeight() {
            return stableWeight;
        }

        public void setStableWeight(Integer stableWeight) {
            this.stableWeight = stableWeight;
        }

        public String getHeader() {
            return header;
        }

        public void setHeader(String header) {
            this.header = header;
        }

        public String getValues() {
            return values;
        }

        public void setValues(String values) {
            this.values = values;
        }

        public Integer getBuckets() {
            return buckets;
        }

        public void setBuckets(Integer buckets) {
            this.buckets = buckets;
        }

        public String getCanaryBuckets() {
            return canaryBuckets;
        }

        public void setCanaryBuckets(String canaryBuckets) {
            this.canaryBuckets = canaryBuckets;
        }

        public String getNegate() {
            return negate;
        }

        public void setNegate(String negate) {
            this.negate = negate;
        }
    }
}
