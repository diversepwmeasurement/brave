/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave.http;

import brave.Tracing;
import brave.propagation.CurrentTraceContext.Scope;
import brave.propagation.TraceContext;
import brave.sampler.Sampler;
import io.undertow.Undertow;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import static io.undertow.util.Headers.CONTENT_TYPE;

@Measurement(iterations = 5, time = 1)
@Warmup(iterations = 10, time = 1)
@Fork(3)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Threads(2)
@State(org.openjdk.jmh.annotations.Scope.Benchmark)
public abstract class HttpClientBenchmarks<C> {
  protected abstract C newClient(HttpTracing httpTracing) throws Exception;

  protected abstract C newClient() throws Exception;

  protected abstract void get(C client) throws Exception;

  protected abstract void close(C client) throws Exception;

  Undertow server;
  String baseUrl;
  C client;
  C tracedClient;
  C unsampledClient;
  TraceContext context =
    TraceContext.newBuilder().traceIdHigh(333L).traceId(444L).spanId(3).sampled(true).build();

  protected String baseUrl() {
    return baseUrl;
  }

  @Setup(Level.Trial) public void init() throws Exception {
    server = Undertow.builder()
      .addHttpListener(0, "127.0.0.1")
      .setHandler(exchange -> {
        exchange.getResponseHeaders().put(CONTENT_TYPE, "text/plain; charset=UTF-8");
        exchange.getResponseSender().send("hello world");
      }).build();
    server.start();
    baseUrl = "http://127.0.0.1:" +
      ((InetSocketAddress) server.getListenerInfo().get(0).getAddress()).getPort();

    client = newClient();
    tracedClient = newClient(HttpTracing.create(
      Tracing.newBuilder().build()
    ));
    unsampledClient = newClient(HttpTracing.create(
      Tracing.newBuilder().sampler(Sampler.NEVER_SAMPLE).build()
    ));
  }

  @TearDown(Level.Trial) public void close() throws Exception {
    close(client);
    close(unsampledClient);
    close(tracedClient);
    server.stop();
    Tracing.current().close();
  }

  @Benchmark public void client_get() throws Exception {
    get(client);
  }

  @Benchmark public void unsampledClient_get() throws Exception {
    get(unsampledClient);
  }

  @Benchmark public void tracedClient_get() throws Exception {
    get(tracedClient);
  }

  @Benchmark public void tracedClient_get_resumeTrace() throws Exception {
    try (Scope scope = Tracing.current().currentTraceContext().newScope(context)) {
      get(tracedClient);
    }
  }
}
