package com.bank.aml.observability;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OTLP 导出链路的端到端冒烟测试：起一个本地 HTTP 服务当接收端，真的导出一个 span， 断言对方**收到了合法的 OTLP/protobuf 报文且里面就是那个
 * span**。
 *
 * <p>
 * 为什么需要它：这一层的配置（{@code application.yml} 默认不设端点）决定了平时**不导出**，
 * 于是任何测试都不会碰它——而它在生产里一旦配置起来就必须能用。此前这条路径的覆盖率是零。
 *
 * <p>
 * 更要紧的是，本工程把 OTLP 的 okhttp 发送器换成了纯 JDK 的那个（理由写在 {@code backend/pom.xml} 里）。两者都是通过
 * {@code ServiceLoader} 注册的 {@code HttpSenderProvider}，**换错了不会有任何编译期症状**——只会在运维把
 * {@code management.otlp.tracing.endpoint} 配上之后，从"追踪后端没数据"开始查。 这个测试就是那次替换的安全网。
 */
class OtlpExportSmokeTest {

    private static final String SPAN_NAME = "otlp-export-probe";

    @Test
    void exportsASpanToAnOtlpHttpEndpoint() throws Exception {
        HttpServer receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CompletableFuture<Received> captured = new CompletableFuture<>();
        receiver.createContext("/v1/traces", exchange -> captured.complete(read(exchange)));
        receiver.start();

        SdkTracerProvider provider = null;
        try {
            OtlpHttpSpanExporter exporter = OtlpHttpSpanExporter.builder()
                .setEndpoint("http://127.0.0.1:" + receiver.getAddress().getPort() + "/v1/traces")
                .build();
            // SimpleSpanProcessor 是同步的：end() 返回时导出已经完成，
            // 因此下面的断言不依赖 sleep，也不会偶发地读到一个还没到的报文。
            provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
            Tracer tracer = provider.get("otlp-smoke");
            Span span = tracer.spanBuilder(SPAN_NAME).startSpan();
            span.end();

            Received got = captured.get(20, TimeUnit.SECONDS);
            assertThat(got.status()).isEqualTo(200);

            String body = got.bodyAsText();
            assertThat(body).as("导出的是 OTLP protobuf，span 名必须以 UTF-8 出现在报文里").contains(SPAN_NAME);

            // 顺带把"到底该用哪个发送器"钉住：okhttp 发送器会把 okhttp3 带上 classpath，
            // 而它拖来的 kotlin-stdlib 在依赖漏洞扫描里占了两条告警（其中一条 9.8）。
            // 若将来有人把 okhttp 发送器加回来，这里会红，并指向 pom.xml 里那段说明。
            assertThat(classpathHasOkHttp()).as("OTLP 应使用纯 JDK 发送器；okhttp 发送器会带回 kotlin-stdlib，见 backend/pom.xml")
                .isFalse();
        }
        finally {
            if (provider != null) {
                provider.close();
            }
            receiver.stop(0);
        }
    }

    private static boolean classpathHasOkHttp() {
        try {
            Class.forName("okhttp3.OkHttpClient");
            return true;
        }
        catch (ClassNotFoundException ex) {
            return false;
        }
    }

    private static Received read(HttpExchange exchange) throws IOException {
        byte[] raw;
        try (InputStream in = exchange.getRequestBody()) {
            raw = in.readAllBytes();
        }
        String encoding = exchange.getRequestHeaders().getFirst("Content-Encoding");
        byte[] body = (encoding != null && encoding.contains("gzip")) ? gunzip(raw) : raw;
        byte[] ack = "{}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, ack.length);
        try (var out = exchange.getResponseBody()) {
            out.write(ack);
        }
        return new Received(exchange.getResponseCode(), body);
    }

    private static byte[] gunzip(byte[] raw) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(raw))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);
            return out.toByteArray();
        }
    }

    /** 接收端捕获到的报文：状态码 + **解压后**的原始字节。 */
    private record Received(int status, byte[] body) {

        String bodyAsText() {
            return new String(this.body, StandardCharsets.UTF_8);
        }

    }

}
