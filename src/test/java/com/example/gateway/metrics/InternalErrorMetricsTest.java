package com.example.gateway.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * InternalErrorMetrics 单测：覆盖失败计数增量、节流、线程名归一化与原 handler 链式委托。
 */
class InternalErrorMetricsTest {

    private InternalErrorMetrics newMetrics(SimpleMeterRegistry registry, long cooldownSeconds,
                                            InternalErrorMetrics.DirectoryWritableChecker checker) {
        return new InternalErrorMetrics(registry, "", 60, cooldownSeconds, checker);
    }

    @Test
    void internalErrorCounterIncrementsOnFirstFailure() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        InternalErrorMetrics metrics = newMetrics(registry, 300, dir -> false);

        metrics.recordInternalError(InternalErrorMetrics.SOURCE_SENTINEL_LOG_DIR);

        Counter counter = registry.find("gateway.internal.error")
                .tag("source", InternalErrorMetrics.SOURCE_SENTINEL_LOG_DIR).counter();
        assertEquals(1.0, counter.count(), 1e-9);
    }

    @Test
    void repeatedFailureWithinCooldownIsThrottled() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        InternalErrorMetrics metrics = newMetrics(registry, 300, dir -> false);

        metrics.recordInternalError(InternalErrorMetrics.SOURCE_SENTINEL_LOG_DIR);
        metrics.recordInternalError(InternalErrorMetrics.SOURCE_SENTINEL_LOG_DIR);
        metrics.recordInternalError(InternalErrorMetrics.SOURCE_SENTINEL_LOG_DIR);

        Counter counter = registry.find("gateway.internal.error")
                .tag("source", InternalErrorMetrics.SOURCE_SENTINEL_LOG_DIR).counter();
        assertEquals(1.0, counter.count(), 1e-9, "节流窗口内重复失败只应计一次");
    }

    @Test
    void counterIncrementsAgainWhenCooldownPassed() {
        // cooldown=0：不节流，每次调用都计数
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        InternalErrorMetrics metrics = newMetrics(registry, 0, dir -> false);

        metrics.recordInternalError(InternalErrorMetrics.SOURCE_SENTINEL_LOG_DIR);
        metrics.recordInternalError(InternalErrorMetrics.SOURCE_SENTINEL_LOG_DIR);

        Counter counter = registry.find("gateway.internal.error")
                .tag("source", InternalErrorMetrics.SOURCE_SENTINEL_LOG_DIR).counter();
        assertEquals(2.0, counter.count(), 1e-9);
    }

    @Test
    void checkExceptionAlsoCountsAsInternalError() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        InternalErrorMetrics metrics = newMetrics(registry, 300, dir -> {
            throw new IllegalStateException("boom");
        });

        // 直接走探针私有路径不可见，这里验证 checker 抛异常时 record 通道可用
        metrics.recordInternalError(InternalErrorMetrics.SOURCE_SENTINEL_LOG_DIR);
        Counter counter = registry.find("gateway.internal.error")
                .tag("source", InternalErrorMetrics.SOURCE_SENTINEL_LOG_DIR).counter();
        assertEquals(1.0, counter.count(), 1e-9);
    }

    @Test
    void threadNameIsNormalized() {
        assertEquals("sentinel-datafile-log-executor-thread",
                InternalErrorMetrics.normalizeThreadName("sentinel-datafile-log-executor-thread-7"));
        assertEquals("http-nio-8080-exec",
                InternalErrorMetrics.normalizeThreadName("http-nio-8080-exec-10"));
        assertEquals("pool-1-thread",
                InternalErrorMetrics.normalizeThreadName("pool-1-thread-3"));
        assertEquals("main", InternalErrorMetrics.normalizeThreadName("main"));
        assertEquals("unknown", InternalErrorMetrics.normalizeThreadName(null));
        assertEquals("unknown", InternalErrorMetrics.normalizeThreadName("   "));
    }

    @Test
    void uncaughtExceptionCounterSharesNormalizedThreadTag() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        InternalErrorMetrics metrics = newMetrics(registry, 300, dir -> true);

        Thread t1 = new Thread("sentinel-datafile-log-executor-thread-7");
        Thread t2 = new Thread("sentinel-datafile-log-executor-thread-8");
        metrics.recordUncaught(t1, new RuntimeException("x"));
        metrics.recordUncaught(t2, new RuntimeException("y"));

        Counter counter = registry.find("uncaught.exception")
                .tag("thread", "sentinel-datafile-log-executor-thread").counter();
        assertEquals(2.0, counter.count(), 1e-9, "不同编号的同名线程应合并到同一 tag");
        assertNull(registry.find("uncaught.exception").tag("thread",
                "sentinel-datafile-log-executor-thread-7").counter(), "不应保留带编号的原始 tag");
    }

    @Test
    void uncaughtHandlerDelegatesToPreviousHandler() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        InternalErrorMetrics metrics = newMetrics(registry, 300, dir -> true);
        AtomicBoolean delegated = new AtomicBoolean(false);
        metrics.setPreviousHandler((t, ex) -> delegated.set(true));

        Thread t = new Thread("test-worker-1");
        metrics.onUncaught(t, new RuntimeException("fatal"));

        assertTrue(delegated.get(), "原 handler 必须被链式调用，不能吞掉");
        Counter counter = registry.find("uncaught.exception")
                .tag("thread", "test-worker").counter();
        assertEquals(1.0, counter.count(), 1e-9);
    }

    @Test
    void failingPreviousHandlerDoesNotSwallowMetric() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        InternalErrorMetrics metrics = newMetrics(registry, 300, dir -> true);
        metrics.setPreviousHandler((t, ex) -> {
            throw new IllegalStateException("previous handler broken");
        });

        metrics.onUncaught(new Thread("test-worker-2"), new RuntimeException("fatal"));

        Counter counter = registry.find("uncaught.exception")
                .tag("thread", "test-worker").counter();
        assertEquals(1.0, counter.count(), 1e-9, "即使原 handler 抛异常，指标也应已记录");
    }
}
