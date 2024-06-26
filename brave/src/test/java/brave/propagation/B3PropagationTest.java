/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave.propagation;

import brave.Request;
import brave.Span;
import brave.internal.Platform;
import brave.propagation.B3Propagation.Format;
import brave.propagation.Propagation.RemoteSetter;
import brave.propagation.TraceContext.Injector;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class B3PropagationTest {
  String traceIdHigh = "0000000000000009";
  String traceId = "0000000000000001";
  String parentId = "0000000000000002";
  String spanId = "0000000000000003";

  TraceContext context = TraceContext.newBuilder().traceId(1).parentId(2).spanId(3).build();

  Propagation<String> propagation = B3Propagation.B3_STRING;
  @Mock Platform platform;

  @Test void keys_defaultToAll() {
      propagation = B3Propagation.newFactoryBuilder()
        .build().get();

      assertThat(propagation.keys()).containsExactly(
        "b3",
        "X-B3-TraceId",
        "X-B3-SpanId",
        "X-B3-ParentSpanId",
        "X-B3-Sampled",
        "X-B3-Flags"
      );
  }

  @Test void keys_withoutB3Single() {
      propagation = B3Propagation.newFactoryBuilder()
      .injectFormat(Span.Kind.PRODUCER, Format.MULTI)
      .injectFormat(Span.Kind.CONSUMER, Format.MULTI)
      .build().get();

    assertThat(propagation.keys()).containsExactly(
      "X-B3-TraceId",
      "X-B3-SpanId",
      "X-B3-ParentSpanId",
      "X-B3-Sampled",
      "X-B3-Flags"
    );
  }

  @Test void keys_onlyB3Single() {
    propagation = B3Propagation.newFactoryBuilder()
      .injectFormat(Format.SINGLE)
      .injectFormat(Span.Kind.CLIENT, Format.SINGLE)
      .injectFormat(Span.Kind.SERVER, Format.SINGLE)
      .build().get();

    assertThat(propagation.keys()).containsOnly("b3");
  }

  @Test void injectFormat() {
    B3Propagation.Factory factory = (B3Propagation.Factory) B3Propagation.newFactoryBuilder()
      .injectFormat(Format.SINGLE)
      .build();

    assertThat(factory.injectorFactory).extracting("injectorFunction")
      .isEqualTo(Format.SINGLE);
  }

  @Test void injectKindFormat() {
    B3Propagation.Factory factory = (B3Propagation.Factory) B3Propagation.newFactoryBuilder()
      .injectFormat(Span.Kind.CLIENT, Format.SINGLE)
      .build();

    assertThat(factory.injectorFactory).extracting("clientInjectorFunction")
      .isEqualTo(Format.SINGLE);
  }

  @Test void injectKindFormats() {
    B3Propagation.Factory factory = (B3Propagation.Factory) B3Propagation.newFactoryBuilder()
      .injectFormats(Span.Kind.CLIENT, Format.SINGLE, Format.MULTI)
      .build();

    assertThat(factory.injectorFactory).extracting("clientInjectorFunction.injectorFunctions")
      .asInstanceOf(InstanceOfAssertFactories.ARRAY)
      .containsExactly(Format.SINGLE, Format.MULTI);
  }

  @Test void injectKindFormats_cantBeSame() {
    assertThatThrownBy(() -> B3Propagation.newFactoryBuilder()
      .injectFormats(Span.Kind.CLIENT, Format.MULTI, Format.MULTI))
      .isInstanceOf(IllegalArgumentException.class);
  }

  @Test void injectKindFormats_cantBeBothSingle() {
    assertThatThrownBy(() -> B3Propagation.newFactoryBuilder()
      .injectFormats(Span.Kind.CLIENT, Format.SINGLE, Format.SINGLE_NO_PARENT))
      .isInstanceOf(IllegalArgumentException.class);
  }

  static class ClientRequest extends Request {
    final Map<String, String> headers = new LinkedHashMap<>();

    @Override public Span.Kind spanKind() {
      return Span.Kind.CLIENT;
    }

    @Override public Object unwrap() {
      return this;
    }

    void header(String key, String value) {
      headers.put(key, value);
    }
  }

  @Test void clientUsesB3Multi() {
    ClientRequest request = new ClientRequest();
    Propagation.B3_STRING.injector(ClientRequest::header).inject(context, request);

    assertThat(request.headers)
      .hasSize(3)
      .containsEntry("X-B3-TraceId", "0000000000000001")
      .containsEntry("X-B3-ParentSpanId", "0000000000000002")
      .containsEntry("X-B3-SpanId", "0000000000000003");
  }

  static class ProducerRequest extends Request {
    final Map<String, String> headers = new LinkedHashMap<>();

    @Override public Span.Kind spanKind() {
      return Span.Kind.PRODUCER;
    }

    @Override public Object unwrap() {
      return this;
    }

    void header(String key, String value) {
      headers.put(key, value);
    }
  }

  @Test void producerUsesB3SingleNoParent_deferred() {
    // This injector won't know the type it is injecting until the call to inject()
    Injector<ProducerRequest> injector = Propagation.B3_STRING.injector(ProducerRequest::header);

    ProducerRequest request = new ProducerRequest();
    injector.inject(context, request);

    assertThat(request.headers)
      .hasSize(1)
      .containsEntry("b3", "0000000000000001-0000000000000003");
  }

  static class ProducerSetter implements RemoteSetter<ProducerRequest> {
    @Override public Span.Kind spanKind() {
      return Span.Kind.PRODUCER;
    }

    @Override public void put(ProducerRequest request, String fieldName, String value) {
      request.header(fieldName, value);
    }
  }

  @Test void producerUsesB3SingleNoParent() {
    // This injector needs no instanceof checks during inject()
    Injector<ProducerRequest> injector = Propagation.B3_STRING.injector(new ProducerSetter());

    ProducerRequest request = new ProducerRequest();
    injector.inject(context, request);

    assertThat(request.headers)
      .hasSize(1)
      .containsEntry("b3", "0000000000000001-0000000000000003");
  }

  @Test void canConfigureSingle() {
    propagation = B3Propagation.newFactoryBuilder()
      .injectFormat(Format.SINGLE_NO_PARENT)
      .build().get();

    Map<String, String> request = new LinkedHashMap<>(); // not a brave.Request
    propagation.<Map<String, String>>injector(Map::put).inject(context, request);

    assertThat(request)
      .hasSize(1)
      .containsEntry("b3", "0000000000000001-0000000000000003");
  }

  @Test void canConfigureBasedOnKind() {
    propagation = B3Propagation.newFactoryBuilder()
      .injectFormats(Span.Kind.CLIENT, Format.SINGLE, Format.MULTI)
      .build().get();

    ClientRequest request = new ClientRequest();
    propagation.injector(ClientRequest::header).inject(context, request);

    assertThat(request.headers)
      .hasSize(4)
      .containsEntry("X-B3-TraceId", traceId)
      .containsEntry("X-B3-ParentSpanId", parentId)
      .containsEntry("X-B3-SpanId", spanId)
      .containsEntry("b3", traceId + "-" + spanId + "-" + parentId);
  }

  @Test void extract_notYetSampled() {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("X-B3-TraceId", traceId);
    headers.put("X-B3-SpanId", spanId);

    assertThat(extract(headers).sampled()).isNull();
  }

  @Test void extract_sampled() {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("X-B3-TraceId", traceId);
    headers.put("X-B3-SpanId", spanId);

    headers.put("X-B3-Sampled", "1");

    assertThat(extract(headers).sampled()).isTrue();

    headers.put("X-B3-Sampled", "true"); // old clients

    assertThat(extract(headers).sampled()).isTrue();
  }

  @Test void extract_128Bit() {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("X-B3-TraceId", traceIdHigh + traceId);
    headers.put("X-B3-SpanId", spanId);

    assertThat(extract(headers).context()).isEqualToComparingFieldByField(TraceContext.newBuilder()
      .traceIdHigh(Long.parseUnsignedLong(traceIdHigh, 16))
      .traceId(Long.parseUnsignedLong(traceId, 16))
      .spanId(Long.parseUnsignedLong(spanId, 16)).build()
    );
  }

  @Test void extract_padded() {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("X-B3-TraceId", "0000000000000000" + traceId);
    headers.put("X-B3-SpanId", spanId);

    assertThat(extract(headers).context()).isEqualToComparingFieldByField(TraceContext.newBuilder()
      .traceId(Long.parseUnsignedLong(traceId, 16))
      .spanId(Long.parseUnsignedLong(spanId, 16)).build()
    );
  }

  @Test void extract_padded_right() {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("X-B3-TraceId", traceIdHigh + "0000000000000000");
    headers.put("X-B3-SpanId", spanId);

    assertThat(extract(headers).context()).isEqualToComparingFieldByField(TraceContext.newBuilder()
      .traceIdHigh(Long.parseUnsignedLong(traceIdHigh, 16))
      .spanId(Long.parseUnsignedLong(spanId, 16)).build()
    );
  }

  @Test void extract_zeros_traceId() {
    try (MockedStatic<Platform> mb = mockStatic(Platform.class)) {
      mb.when(Platform::get).thenReturn(platform);

      Map<String, String> headers = new LinkedHashMap<>();
      headers.put("X-B3-TraceId", "0000000000000000");
      headers.put("X-B3-SpanId", spanId);

      assertThat(extract(headers).context()).isNull();

      verify(platform).log("Invalid input: traceId was all zeros", null);
    }
  }

  @Test void extract_zeros_traceId_128() {
    try (MockedStatic<Platform> mb = mockStatic(Platform.class)) {
      mb.when(Platform::get).thenReturn(platform);

      Map<String, String> headers = new LinkedHashMap<>();
      headers.put("X-B3-TraceId", "00000000000000000000000000000000");
      headers.put("X-B3-SpanId", spanId);

      assertThat(extract(headers).context()).isNull();

      verify(platform).log("Invalid input: traceId was all zeros", null);
    }
  }

  @Test void extract_zeros_spanId() {
    try (MockedStatic<Platform> mb = mockStatic(Platform.class)) {
      mb.when(Platform::get).thenReturn(platform);

      Map<String, String> headers = new LinkedHashMap<>();
      headers.put("X-B3-TraceId", traceId);
      headers.put("X-B3-SpanId", "0000000000000000");

      assertThat(extract(headers).context()).isNull();

      verify(platform).log("Invalid input: spanId was all zeros", null);
    }
  }

  @Test void extract_sampled_false() {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("X-B3-TraceId", traceId);
    headers.put("X-B3-SpanId", spanId);

    headers.put("X-B3-Sampled", "0");

    assertThat(extract(headers).sampled()).isFalse();

    headers.put("X-B3-Sampled", "false"); // old clients

    assertThat(extract(headers).sampled()).isFalse();
  }

  @Test void extract_sampledCorrupt() {
    try (MockedStatic<Platform> mb = mockStatic(Platform.class)) {
      mb.when(Platform::get).thenReturn(platform);

      Map<String, String> headers = new LinkedHashMap<>();
      headers.put("X-B3-TraceId", traceId);
      headers.put("X-B3-SpanId", spanId);

      Stream.of("", "d", "💩", "hello").forEach(sampled -> {
        headers.put("X-B3-Sampled", sampled);
        assertThat(extract(headers)).isSameAs(TraceContextOrSamplingFlags.EMPTY);

        verify(platform).log("Invalid input: expected 0 or 1 for X-B3-Sampled, but found '{0}'",
          sampled, null);
      });
    }
  }

  @Test void build_defaultIsSingleton() {
    assertThat(B3Propagation.newFactoryBuilder().build())
        .isSameAs(B3Propagation.FACTORY);
  }

  @Test void equalsAndHashCode() {
    // same instance are equivalent
    Propagation.Factory factory = B3Propagation.newFactoryBuilder()
        .injectFormat(Span.Kind.CLIENT, Format.SINGLE_NO_PARENT)
        .injectFormat(Span.Kind.SERVER, Format.SINGLE_NO_PARENT)
        .build();
    assertThat(factory).isEqualTo(factory);

    // same formats are equivalent
    Propagation.Factory sameFields = B3Propagation.newFactoryBuilder()
        .injectFormat(Span.Kind.CLIENT, Format.SINGLE_NO_PARENT)
        .injectFormat(Span.Kind.SERVER, Format.SINGLE_NO_PARENT)
        .build();
    assertThat(factory.equals(sameFields)).isTrue();
    assertThat(sameFields).isEqualTo(factory);
    assertThat(sameFields).hasSameHashCodeAs(factory);

    // different formats are not equivalent
    assertThat(factory).isNotEqualTo(B3Propagation.FACTORY);
    assertThat(B3Propagation.FACTORY).isNotEqualTo(factory);
    assertThat(factory.hashCode()).isNotEqualTo(B3Propagation.FACTORY.hashCode());
  }

  TraceContextOrSamplingFlags extract(Map<String, String> headers) {
    return propagation.<Map<String, String>>extractor(Map::get).extract(headers);
  }
}
