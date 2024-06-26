/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave.dubbo;

import brave.Clock;
import brave.Tag;
import brave.handler.MutableSpan;
import brave.propagation.CurrentTraceContext.Scope;
import brave.propagation.SamplingFlags;
import brave.propagation.TraceContext;
import brave.rpc.RpcResponseParser;
import brave.rpc.RpcRuleSampler;
import brave.rpc.RpcTracing;
import brave.test.util.AssertableCallback;
import java.util.Map;
import org.apache.dubbo.common.beanutil.JavaBeanDescriptor;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.bootstrap.DubboBootstrap;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static brave.Span.Kind.CLIENT;
import static brave.rpc.RpcRequestMatchers.methodEquals;
import static brave.rpc.RpcRequestMatchers.serviceEquals;
import static brave.sampler.Sampler.ALWAYS_SAMPLE;
import static brave.sampler.Sampler.NEVER_SAMPLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ITTracingFilter_Consumer extends ITTracingFilter {
  ReferenceConfig<GraterService> wrongClient;

  @BeforeEach void setup() {
    server.initService();
    init();
    server.start();
    String url = "dubbo://" + server.ip() + ":" + server.port() + "?scope=remote&generic=bean";
    client = new ReferenceConfig<>();
    client.setGeneric("true");
    client.setFilter("tracing");
    client.setRetries(-1);
    client.setInterface(GreeterService.class);
    client.setUrl(url);

    wrongClient = new ReferenceConfig<>();
    wrongClient.setRetries(-1);
    wrongClient.setGeneric("true");
    wrongClient.setFilter("tracing");
    wrongClient.setInterface(GraterService.class);
    wrongClient.setUrl(url);

    DubboBootstrap.getInstance().application(application)
        .reference(client)
        .reference(wrongClient)
        .start();

    init();

    // perform a warmup request to allow CI to fail quicker
    client.get().sayHello("jorge");
    server.takeRequest();
    testSpanHandler.takeRemoteSpan(CLIENT);
  }

  @AfterEach void stop() {
    if (wrongClient != null) wrongClient.destroy();
    super.stop();
  }

  @Test void propagatesNewTrace() {
    client.get().sayHello("jorge");

    TraceContext extracted = server.takeRequest().context();
    assertThat(extracted.sampled()).isTrue();
    assertThat(extracted.parentIdString()).isNull();
    assertSameIds(testSpanHandler.takeRemoteSpan(CLIENT), extracted);
  }

  @Test void propagatesChildOfCurrentSpan() {
    TraceContext parent = newTraceContext(SamplingFlags.SAMPLED);
    try (Scope scope = currentTraceContext.newScope(parent)) {
      client.get().sayHello("jorge");
    }

    TraceContext extracted = server.takeRequest().context();
    assertThat(extracted.sampled()).isTrue();
    assertChildOf(extracted, parent);
    assertSameIds(testSpanHandler.takeRemoteSpan(CLIENT), extracted);
  }

  /** Unlike Brave 3, Brave 4 propagates trace ids even when unsampled */
  @Test void propagatesUnsampledContext() {
    TraceContext parent = newTraceContext(SamplingFlags.NOT_SAMPLED);
    try (Scope scope = currentTraceContext.newScope(parent)) {
      client.get().sayHello("jorge");
    }

    TraceContext extracted = server.takeRequest().context();
    assertThat(extracted.sampled()).isFalse();
    assertChildOf(extracted, parent);
  }

  @Test void propagatesBaggage() {
    TraceContext parent = newTraceContext(SamplingFlags.SAMPLED);
    try (Scope scope = currentTraceContext.newScope(parent)) {
      BAGGAGE_FIELD.updateValue(parent, "joey");
      client.get().sayHello("jorge");
    }

    TraceContext extracted = server.takeRequest().context();
    assertThat(BAGGAGE_FIELD.getValue(extracted)).isEqualTo("joey");

    testSpanHandler.takeRemoteSpan(CLIENT);
  }

  @Test void propagatesBaggage_unsampled() {
    TraceContext parent = newTraceContext(SamplingFlags.NOT_SAMPLED);
    try (Scope scope = currentTraceContext.newScope(parent)) {
      BAGGAGE_FIELD.updateValue(parent, "joey");
      client.get().sayHello("jorge");
    }

    TraceContext extracted = server.takeRequest().context();
    assertThat(BAGGAGE_FIELD.getValue(extracted)).isEqualTo("joey");
  }

  /** This prevents confusion as a blocking client should end before, the start of the next span. */
  @Test void clientTimestampAndDurationEnclosedByParent() {
    TraceContext parent = newTraceContext(SamplingFlags.SAMPLED);
    Clock clock = tracing.clock(parent);

    long start = clock.currentTimeMicroseconds();
    try (Scope scope = currentTraceContext.newScope(parent)) {
      client.get().sayHello("jorge");
    }
    long finish = clock.currentTimeMicroseconds();

    MutableSpan clientSpan = testSpanHandler.takeRemoteSpan(CLIENT);
    assertChildOf(clientSpan, parent);
    assertSpanInInterval(clientSpan, start, finish);
  }

  /**
   * This tests that the parent is determined at the time the request was made, not when the request
   * was executed.
   */
  @Test void usesParentFromInvocationTime() {
    AssertableCallback<String> items1 = new AssertableCallback<>();
    AssertableCallback<String> items2 = new AssertableCallback<>();

    TraceContext parent = newTraceContext(SamplingFlags.SAMPLED);
    try (Scope scope = currentTraceContext.newScope(parent)) {
      RpcContext.getContext().asyncCall(() -> client.get().sayHello("jorge"))
          .whenComplete(items1);
      RpcContext.getContext().asyncCall(() -> client.get().sayHello("romeo"))
          .whenComplete(items2);
    }

    try (Scope scope = currentTraceContext.newScope(null)) {
      // complete within a different scope
      items1.join();
      items2.join();

      for (int i = 0; i < 2; i++) {
        TraceContext extracted = server.takeRequest().context();
        assertChildOf(extracted, parent);
      }
    }

    // The spans may report in a different order than the requests
    for (int i = 0; i < 2; i++) {
      assertChildOf(testSpanHandler.takeRemoteSpan(CLIENT), parent);
    }
  }

  @Test void reportsClientKindToZipkin() {
    client.get().sayHello("jorge");

    testSpanHandler.takeRemoteSpan(CLIENT);
  }

  @Test void defaultSpanNameIsMethodName() {
    client.get().sayHello("jorge");

    assertThat(testSpanHandler.takeRemoteSpan(CLIENT).name())
        .isEqualTo("brave.dubbo.GreeterService/sayHello");
  }

  @Test void onTransportException_setError() {
    server.stop();

    assertThatThrownBy(() -> client.get().sayHello("jorge"))
        .isInstanceOf(RpcException.class);

    testSpanHandler.takeRemoteSpanWithErrorMessage(CLIENT, ".*Not found exported service: brave.dubbo.GreeterService.*");
  }

  @Test void onTransportException_setError_async() {
    server.stop();

    RpcContext.getContext().asyncCall(() -> client.get().sayHello("romeo"));

    testSpanHandler.takeRemoteSpanWithErrorMessage(CLIENT, ".*Not found exported service: brave.dubbo.GreeterService.*");
  }

  @Test void finishesOneWaySpan() {
    RpcContext.getContext().asyncCall(() -> {
      client.get().sayHello("romeo");
    });

    testSpanHandler.takeRemoteSpan(CLIENT);
  }

  @Test void setError_onUnimplemented() {
    assertThatThrownBy(() -> wrongClient.get().sayHello("jorge"))
        .isInstanceOf(RpcException.class);

    MutableSpan span =
        testSpanHandler.takeRemoteSpanWithErrorMessage(CLIENT, ".*Fail to decode request.*");

    assertThat(span.tags())
        .containsEntry("rpc.error_code", "NETWORK_EXCEPTION");
  }

  /* RpcTracing-specific feature tests */

  @Test void customSampler() {
    RpcTracing rpcTracing = RpcTracing.newBuilder(tracing).clientSampler(RpcRuleSampler.newBuilder()
        .putRule(methodEquals("sayGoodbye"), NEVER_SAMPLE)
        .putRule(serviceEquals("brave.dubbo"), ALWAYS_SAMPLE)
        .build()).build();
    init().setRpcTracing(rpcTracing);

    // unsampled
    client.get().sayGoodbye("jorge");

    // sampled
    client.get().sayHello("jorge");

    assertThat(testSpanHandler.takeRemoteSpan(CLIENT).name()).endsWith("sayHello");
    // @After will also check that sayGoodbye was not sampled
  }

  @Test void customParser() {
    Tag<DubboResponse> javaValue = new Tag<DubboResponse>("dubbo.result_value") {
      @Override protected String parseValue(DubboResponse input, TraceContext context) {
        Result result = input.result();
        if (result == null) return null;
        JavaBeanDescriptor value = (JavaBeanDescriptor) result.getValue();
        String s = String.valueOf(value.getProperty("value"));
        System.out.println(s);
        return s;
      }
    };

    RpcTracing rpcTracing = RpcTracing.newBuilder(tracing)
        .clientResponseParser((res, context, span) -> {
          RpcResponseParser.DEFAULT.parse(res, context, span);
          if (res instanceof DubboResponse) {
            javaValue.tag((DubboResponse) res, span);
          }
        }).build();
    init().setRpcTracing(rpcTracing);

    String javaResult = client.get().sayHello("jorge");

    Map<String, String> tags = testSpanHandler.takeRemoteSpan(CLIENT).tags();
    assertThat(tags).containsEntry("dubbo.result_value", javaResult);
  }
}
