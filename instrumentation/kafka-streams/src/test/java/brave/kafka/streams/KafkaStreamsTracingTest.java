/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave.kafka.streams;

import brave.Span;
import brave.propagation.CurrentTraceContext.Scope;
import java.util.Date;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.ProcessorSupplier;
import org.apache.kafka.streams.processor.api.Record;
import org.junit.jupiter.api.Test;

import static brave.test.ITRemote.BAGGAGE_FIELD;
import static brave.test.ITRemote.BAGGAGE_FIELD_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

public class KafkaStreamsTracingTest extends KafkaStreamsTest {
  @Test void nextSpan_uses_current_context() {
    ProcessorContext<String, String> fakeProcessorContext = processorV2ContextSupplier.get();
    Span child;
    try (Scope scope = tracing.currentTraceContext().newScope(parent)) {
      child = kafkaStreamsTracing.nextSpan(fakeProcessorContext, new RecordHeaders());
    }
    child.finish();

    assertThat(child.context().parentIdString()).isEqualTo(parent.spanIdString());
  }

  @Test void nextSpan_should_create_span_if_no_headers() {
    ProcessorContext<String, String> fakeProcessorContext = processorV2ContextSupplier.get();
    assertThat(kafkaStreamsTracing.nextSpan(fakeProcessorContext, new RecordHeaders())).isNotNull();
  }

  @Test void nextSpan_should_tag_app_id_and_task_id() {
    ProcessorContext<String, String> fakeProcessorContext = processorV2ContextSupplier.get();
    kafkaStreamsTracing.nextSpan(fakeProcessorContext, new RecordHeaders()).start().finish();

    assertThat(spans.get(0).tags()).containsOnly(
      entry("kafka.streams.application.id", TEST_APPLICATION_ID),
      entry("kafka.streams.task.id", TEST_TASK_ID));
  }

  @Test void newProcessorSupplier_should_tag_app_id_and_task_id() {
    Processor<String, String, String, String> processor =
      fakeV2ProcessorSupplier.get();
    processor.init(processorV2ContextSupplier.get());
    processor.process(new Record<>(TEST_KEY, TEST_VALUE, new Date().getTime()));

    assertThat(spans.get(0).tags()).containsOnly(
      entry("kafka.streams.application.id", TEST_APPLICATION_ID),
      entry("kafka.streams.task.id", TEST_TASK_ID));
  }

  @Test void newProcessorSupplier_should_add_baggage_field() {
    ProcessorSupplier<String, String, String, String>
      processorSupplier = kafkaStreamsTracing.process("forward-1", () -> record ->
      assertThat(BAGGAGE_FIELD.getValue(currentTraceContext.get()))
        .isEqualTo("user1"));
    Headers headers = new RecordHeaders().add(BAGGAGE_FIELD_KEY, "user1".getBytes());
    Processor<String, String, String, String> processor = processorSupplier.get();
    processor.init(processorV2ContextSupplier.get());
    processor.process(new Record<>(TEST_KEY, TEST_VALUE, new Date().getTime(), headers));
  }
}
