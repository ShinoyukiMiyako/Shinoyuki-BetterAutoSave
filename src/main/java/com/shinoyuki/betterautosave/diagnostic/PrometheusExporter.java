package com.shinoyuki.betterautosave.diagnostic;

import com.shinoyuki.betterautosave.BetterAutoSaveMod;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * v0.9: Prometheus metrics HTTP exporter.
 *
 * <p>用 JDK 内置 {@link HttpServer} 在 {@code bindAddress:port/metrics} 端点
 * 暴露 {@link SaveMetrics} 当前快照 (Prometheus exposition format).
 * 零外部依赖, 不引入 simpleclient 库.
 *
 * <p><b>线程模型</b>: 单 daemon 线程 executor. Prometheus 默认 scrape
 * 间隔 15s, 单 mod 单 endpoint 没必要多线程. metrics.snapshot() 内部
 * 用 {@code LongAdder.sum() / AtomicLong.get()} 全部 lock-free, 不
 * 阻塞 BAS 主路径.
 *
 * <p><b>启动失败</b>: bind 端口被占用 / 地址非法 → {@link #start()} 抛
 * IOException. 调用方 (BetterAutoSaveMod.onServerStarting) 应捕获并
 * log error, 服务端继续运行不 crash. 跟 BAS degraded mode 哲学一致.
 *
 * <p><b>请求异常</b>: handler 内部所有异常 try-catch + log, 不 propagate
 * 到 HttpServer (避免单次抓取异常 kill 整个 server 线程). 失败的抓取
 * Prometheus 自身会重试.
 */
public final class PrometheusExporter {

    private static final Logger LOGGER = BetterAutoSaveMod.LOGGER;
    private static final String METRICS_PATH = "/metrics";
    private static final String CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";
    private static final int BACKLOG = 4;
    private static final int STOP_TIMEOUT_SECONDS = 1;

    private static final AtomicLong THREAD_INDEX = new AtomicLong();

    private final SaveMetrics metrics;
    private final String bindAddress;
    private final int port;

    private volatile HttpServer server;
    private volatile boolean started;

    public PrometheusExporter(SaveMetrics metrics, String bindAddress, int port) {
        this.metrics = metrics;
        this.bindAddress = bindAddress;
        this.port = port;
    }

    public synchronized void start() throws IOException {
        if (started) {
            return;
        }
        InetSocketAddress addr = new InetSocketAddress(bindAddress, port);
        HttpServer s = HttpServer.create(addr, BACKLOG);
        s.createContext(METRICS_PATH, this::handleMetrics);
        s.createContext("/", this::handleNotFound);
        s.setExecutor(Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "BetterAutoSave-Prometheus-" + THREAD_INDEX.incrementAndGet());
            t.setDaemon(true);
            return t;
        }));
        s.start();
        this.server = s;
        this.started = true;
        LOGGER.info("[BetterAutoSave] Prometheus exporter listening on {}:{}{}",
                bindAddress, port, METRICS_PATH);
    }

    public synchronized void stop() {
        if (!started) {
            return;
        }
        HttpServer s = this.server;
        try {
            if (s != null) {
                s.stop(STOP_TIMEOUT_SECONDS);
            }
        } catch (Throwable t) {
            LOGGER.warn("[BetterAutoSave] Prometheus exporter stop threw", t);
        }
        this.server = null;
        this.started = false;
        LOGGER.info("[BetterAutoSave] Prometheus exporter stopped");
    }

    /** 仅测试用: 拿到实际绑定端口 (port=0 时 ephemeral port). */
    public int actualPort() {
        HttpServer s = this.server;
        return s != null ? s.getAddress().getPort() : -1;
    }

    private void handleMetrics(HttpExchange exchange) {
        try (exchange) {
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String body = PrometheusFormatter.format(metrics.snapshot());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", CONTENT_TYPE);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } catch (Throwable t) {
            LOGGER.error("[BetterAutoSave] Prometheus /metrics handler threw", t);
        }
    }

    private void handleNotFound(HttpExchange exchange) {
        try (exchange) {
            byte[] bytes = "Not Found. Use GET /metrics.\n".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } catch (Throwable t) {
            LOGGER.error("[BetterAutoSave] Prometheus 404 handler threw", t);
        }
    }
}
