/*
 * Copyright 2013-2024 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package brave.http;

import brave.ScopedSpan;
import brave.Tracing;
import brave.http.HttpClientAdapters.FromRequestAdapter;
import brave.propagation.TraceContext;
import brave.test.IntegrationTestSpanHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import static brave.Span.Kind.CLIENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Deprecated
class DeprecatedHttpClientHandlerTest {
  @RegisterExtension IntegrationTestSpanHandler spanHandler = new IntegrationTestSpanHandler();

  TraceContext context = TraceContext.newBuilder().traceId(1L).spanId(1L).sampled(true).build();
  @Mock HttpSampler sampler;
  HttpTracing httpTracing;
  HttpClientHandler<Object, Object> handler;

  @Spy HttpClientParser parser = new HttpClientParser();
  @Mock HttpClientAdapter<Object, Object> adapter;
  @Mock TraceContext.Injector<Object> injector;
  @Mock Object request;
  @Mock Object response;

  @BeforeEach void init() {
    init(httpTracingBuilder(tracingBuilder()));
  }

  void init(HttpTracing.Builder builder) {
    close();
    httpTracing = builder.build();
    handler = HttpClientHandler.create(httpTracing, adapter);
  }

  HttpTracing.Builder httpTracingBuilder(Tracing.Builder tracingBuilder) {
    return HttpTracing.newBuilder(tracingBuilder.build())
      .clientSampler(sampler).clientParser(parser);
  }

  Tracing.Builder tracingBuilder() {
    return Tracing.newBuilder().addSpanHandler(spanHandler);
  }

  @AfterEach void close() {
    Tracing current = Tracing.current();
    if (current != null) current.close();
  }

  @Test void handleSend_defaultsToMakeNewTrace() {
    when(sampler.trySample(any(FromRequestAdapter.class))).thenReturn(null);

    assertThat(handler.handleSend(injector, request))
      .extracting(brave.Span::isNoop, s -> s.context().parentId())
      .containsExactly(false, null);
  }

  @Test void handleSend_makesAChild() {
    ScopedSpan parent = httpTracing.tracing().tracer().startScopedSpan("test");
    try {
      assertThat(handler.handleSend(injector, request))
        .extracting(brave.Span::isNoop, s -> s.context().parentId())
        .containsExactly(false, parent.context().spanId());
    } finally {
      parent.finish();
    }
    spanHandler.takeLocalSpan();
  }

  @Test void handleSend_makesRequestBasedSamplingDecision() {
    // request sampler says false eventhough trace ID sampler would have said true
    when(sampler.trySample(any(FromRequestAdapter.class))).thenReturn(false);
    init(httpTracingBuilder(tracingBuilder()));

    assertThat(handler.handleSend(injector, request).isNoop()).isTrue();
  }

  @Test void handleSend_injectsTheTraceContext() {
    TraceContext context = handler.handleSend(injector, request).context();

    verify(injector).inject(context, request);
  }

  @Test void handleSend_injectsTheTraceContext_onTheRequest() {
    HttpClientRequest customRequest = mock(HttpClientRequest.class);
    TraceContext context = handler.handleSend(injector, customRequest, request).context();

    verify(injector).inject(context, customRequest);
  }

  @Test void handleSend_addsClientAddressWhenOnlyServiceName() {
    when(sampler.trySample(any(FromRequestAdapter.class))).thenReturn(null);

    httpTracing = httpTracing.clientOf("remote-service");

    HttpClientHandler.create(httpTracing, adapter).handleSend(injector, request).finish();

    assertThat(spanHandler.takeRemoteSpan(CLIENT).remoteServiceName())
      .isEqualTo("remote-service");
  }

  @Test void handleSend_skipsClientAddressWhenUnparsed() {
    when(sampler.trySample(any(FromRequestAdapter.class))).thenReturn(null);

    handler.handleSend(injector, request).finish();

    assertThat(spanHandler.takeRemoteSpan(CLIENT).remoteServiceName())
      .isNull();
  }

  @Test void handleReceive() {
    brave.Span span = mock(brave.Span.class);
    when(span.context()).thenReturn(context);
    when(span.customizer()).thenReturn(span);

    handler.handleReceive(response, null, span);

    verify(parser).response(eq(adapter), eq(response), isNull(), eq(span));
  }

  @Test void handleReceive_oneOfResponseError() {
    brave.Span span = mock(brave.Span.class);

    assertThatThrownBy(() -> handler.handleReceive(null, null, span))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessage("Either the response or error parameters may be null, but not both");
  }
}
