package com.example.gateway.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 网关内部异常指标：覆盖应用子系统自身失败（Sentinel 日志目录不可写）与 JVM 未捕获异常，
 * 这类问题不会体现在 HTTP 请求指标里，只能靠旁路探针 / 默认未捕获异常Handler 暴露。
 *
 * <p>暴露的 Prometheus 指标：
 * <ul>
 *   <li>{@code gateway_internal_error_total{source=...}} —— 内部子系统失败计数</li>
 *   <li>{@code uncaught_exception_total{thread=...}} —— 未捕获异常计数，thread 标签已归一化</li>
 * </ul>
 */
@Component
public class InternalErrorMetrics {

    private static final Logger log = LoggerFactory.getLogger(InternalErrorMetrics.class);

    /** Sentinel 数据文件 / 日志目录不可写（含探活异常）。 */
    public static final String SOURCE_SENTINEL_LOG_DIR = "sentinel-log-dir-unwritable";

    private static final String DEFAULT_PROBE_DIR = "/tmp/csp-logs";
    private static final String SYSPROP_SENTINEL_LOG_DIR = "csp.sentinel.log.dir";

    private final MeterRegistry registry;
    private final String configuredProbeDir;
    private final long probeIntervalSeconds;
    private final long failureCooldownSeconds;
    private final DirectoryWritableChecker checker;

    /** source -> 上次计数时间戳（毫秒），用于失败计数节流，避免目录持续不可写时每轮泄洪式猛涨。 */
    private final Map<String, Long> lastFailureAtMillis = new ConcurrentHashMap<>();

    private ScheduledExecutorService executor;
    private volatile Thread.UncaughtExceptionHandler previousHandler;

    @FunctionalInterface
    interface DirectoryWritableChecker {
        /** 返回 true 表示目录可创建且可写入临时文件；抛异常按不可写处理。 */
        boolean isWritable(Path dir) throws Exception;
    }

    @Autowired
    public InternalErrorMetrics(MeterRegistry registry,
                                @Value("${gateway.internal-error.log-probe-dir:}") String configuredProbeDir,
                                @Value("${gateway.internal-error.probe-interval-seconds:60}") long probeIntervalSeconds,
                                @Value("${gateway.internal-error.failure-cooldown-seconds:300}") long failureCooldownSeconds) {
        this(registry, configuredProbeDir, probeIntervalSeconds, failureCooldownSeconds, InternalErrorMetrics::defaultCheck);
    }

    InternalErrorMetrics(MeterRegistry registry,
                          String configuredProbeDir,
                          long probeIntervalSeconds,
                          long failureCooldownSeconds,
                          DirectoryWritableChecker checker) {
        this.registry = registry;
        this.configuredProbeDir = configuredProbeDir;
        this.probeIntervalSeconds = Math.max(15, probeIntervalSeconds);
        this.failureCooldownSeconds = failureCooldownSeconds;
        this.checker = checker;
    }

    @PostConstruct
    void initialize() {
        installUncaughtExceptionHandler();
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread t = new Thread(runnable, "gateway-internal-error-probe");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleAtFixedRate(this::probeSentinelLogDir,
                probeIntervalSeconds, probeIntervalSeconds, TimeUnit.SECONDS);
        log.info("内部异常指标已装配：日志目录探活间隔={}s，失败节流窗口={}s", probeIntervalSeconds, failureCooldownSeconds);
    }

    @PreDestroy
    void shutdown() {
        if (executor != null) {
            executor.shutdownNow();
        }
        Thread.setDefaultUncaughtExceptionHandler(previousHandler);
    }

    /** 定时探活 Sentinel 日志目录：实际写一个临时文件再删掉，写不进去就算内部异常。 */
    private void probeSentinelLogDir() {
        Path dir = resolveProbeDir();
        try {
            if (!checker.isWritable(dir)) {
                recordInternalError(SOURCE_SENTINEL_LOG_DIR);
                log.warn("Sentinel 日志目录不可写：{}", dir);
            }
        } catch (Exception e) {
            recordInternalError(SOURCE_SENTINEL_LOG_DIR);
            log.warn("Sentinel 日志目录探活失败：{}", dir, e);
        }
    }

    private Path resolveProbeDir() {
        if (configuredProbeDir != null && !configuredProbeDir.isBlank()) {
            return Path.of(configuredProbeDir);
        }
        String sysprop = System.getProperty(SYSPROP_SENTINEL_LOG_DIR);
        if (sysprop != null && !sysprop.isBlank()) {
            return Path.of(sysprop);
        }
        return Path.of(DEFAULT_PROBE_DIR);
    }

    /** 记录一次内部失败；同一 source 在节流窗口内重复失败只计一次。 */
    public void recordInternalError(String source) {
        long now = System.currentTimeMillis();
        long cooldownMillis = failureCooldownSeconds * 1000L;
        Long last = lastFailureAtMillis.get(source);
        if (last != null && now - last < cooldownMillis) {
            return;
        }
        lastFailureAtMillis.put(source, now);
        Counter.builder("gateway.internal.error")
                .description("Gateway internal/subsystem failure counter")
                .tags("source", source)
                .register(registry)
                .increment();
    }

    private void installUncaughtExceptionHandler() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(this::onUncaught);
    }

    /** 先记指标，再链式调用原有 handler；不吞异常、不改变原有处理行为。 */
    void onUncaught(Thread thread, Throwable ex) {
        try {
            recordUncaught(thread, ex);
        } catch (Exception metricFailure) {
            log.warn("记录 uncaught exception 指标失败", metricFailure);
        }
        if (previousHandler != null) {
            try {
                previousHandler.uncaughtException(thread, ex);
            } catch (Exception delegateFailure) {
                // 不吞掉委托调用本身，但隔离旧 handler 的二次异常，避免中断 Handler 链
                log.warn("原有未捕获异常 Handler 执行失败", delegateFailure);
            }
        }
    }

    void setPreviousHandler(Thread.UncaughtExceptionHandler handler) {
        this.previousHandler = handler;
    }

    void recordUncaught(Thread thread, Throwable ex) {
        String threadTag = normalizeThreadName(thread == null ? null : thread.getName());
        Counter.builder("uncaught.exception")
                .description("Uncaught exceptions observed via the JVM default uncaught exception handler")
                .tags("thread", threadTag)
                .register(registry)
                .increment();
        log.warn("未捕获异常 thread={}", threadTag, ex);
    }

    /**
     * 线程名归一化：去掉线程池编号后缀，避免 tag 基数爆炸。
     * 例如 {@code sentinel-datafile-log-executor-thread-7} → {@code sentinel-datafile-log-executor-thread}，
     * {@code http-nio-8080-exec-10} → {@code http-nio-8080-exec}。
     */
    static String normalizeThreadName(String name) {
        if (name == null || name.isBlank()) {
            return "unknown";
        }
        String n = name.trim();
        // 反复剥离 "-1" / "_12" 这类编号尾巴（池名可能带多级编号）
        while (n.matches(".*[-_]\\d+$")) {
            n = n.replaceAll("[-_]\\d+$", "");
        }
        // 再剥离没有分隔符的纯数字尾巴
        n = n.replaceAll("\\d+$", "");
        if (n.isBlank()) {
            return "unknown";
        }
        return n;
    }

    private static boolean defaultCheck(Path dir) throws Exception {
        Files.createDirectories(dir);
        Path tmp = dir.resolve(".gateway-probe-" + UUID.randomUUID());
        try {
            Files.writeString(tmp, "probe");
        } finally {
            Files.deleteIfExists(tmp);
        }
        return true;
    }
}
