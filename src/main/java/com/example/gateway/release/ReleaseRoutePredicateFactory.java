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
        final String header = config.getHeader();
        final List<String> values = parseList(config.getValues());
        final int buckets = config.getBuckets() == null ? 0 : config.getBuckets();
        final List<String> canaryBuckets = parseList(config.getCanaryBuckets());
        final boolean negate = Boolean.parseBoolean(config.getNegate());

        return exchange -> {
            String v = exchange.getRequest().getHeaders().getFirst(header);
            boolean in;
            if (!values.isEmpty()) {
                in = v != null && values.contains(v);
            } else if (buckets > 0) {
                in = v != null && canaryBuckets.contains(String.valueOf(Math.floorMod(v.hashCode(), buckets)));
            } else {
                in = false;
            }
            return negate != in;
        };
    }

    private static List<String> parseList(String raw) {
        List<String> out = new ArrayList<>();
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
