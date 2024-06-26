/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave.features.handler;

import brave.Tracing;
import brave.test.TestSpanHandler;
import io.micrometer.core.instrument.search.MeterNotFoundException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.failBecauseExceptionWasNotThrown;

class SpanMetricsCustomizerTest {
  SimpleMeterRegistry registry = new SimpleMeterRegistry();
  SpanMetricsCustomizer spanMetricsCustomizer = new SpanMetricsCustomizer(registry, "span", "foo");

  TestSpanHandler spans = new TestSpanHandler();
  Tracing tracing;

  @BeforeEach void setup() {
    Tracing.Builder builder = Tracing.newBuilder().addSpanHandler(spans);

    // It is typical for multiple customizers to collaborate on a builder.
    // This example is simplified to use only one customizer.
    spanMetricsCustomizer.customize(builder);
    tracing = builder.build();
  }

  @AfterEach void after() {
    tracing.close();
    registry.close();
  }

  @Test void onlyRecordsSpansMatchingSpanName() {
    tracing.tracer().nextSpan().name("foo").start().finish();
    tracing.tracer().nextSpan().name("bar").start().finish();
    tracing.tracer().nextSpan().name("foo").start().finish();

    assertThat(registry.get("span")
      .tags("name", "foo", "exception", "None").timer().count())
      .isEqualTo(2L);

    try {
      registry.get("span").tags("name", "bar", "exception", "None").timer();

      failBecauseExceptionWasNotThrown(MeterNotFoundException.class);
    } catch (MeterNotFoundException expected) {
    }
  }

  @Test void addsExceptionTagToSpan() {
    tracing.tracer().nextSpan().name("foo").start()
      .tag("error", "wow")
      .error(new IllegalStateException())
      .finish();

    assertThat(registry.get("span")
      .tags("name", "foo", "exception", "IllegalStateException").timer().count())
      .isEqualTo(1L);
    assertThat(spans.get(0).tags())
      .containsEntry("exception", "IllegalStateException");
  }
}
