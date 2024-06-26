/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave.jms;

import brave.Span;
import brave.messaging.MessagingRuleSampler;
import brave.messaging.MessagingTracing;
import brave.sampler.Sampler;
import java.util.concurrent.CountDownLatch;
import javax.jms.CompletionListener;
import javax.jms.JMSContext;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageProducer;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static brave.messaging.MessagingRequestMatchers.channelNameEquals;
import static org.assertj.core.api.Assertions.assertThat;

/** When adding tests here, also add to {@link brave.jms.ITTracingJMSProducer} */
class ITJms_2_0_TracingMessageProducer extends ITJms_1_1_TracingMessageProducer {
  @Override JmsExtension newJmsExtension() {
    return new ArtemisJmsExtension();
  }

  @Test void should_complete_on_callback() throws JMSException {
    should_complete_on_callback(
      listener -> messageProducer.send(jms.destination, message, listener));
  }

  @Test void should_complete_on_callback_queue() throws JMSException {
    should_complete_on_callback(
      listener -> queueSender.send(jms.queue, message, listener));
  }

  @Test void should_complete_on_callback_topic() throws JMSException {
    should_complete_on_callback(
      listener -> topicPublisher.send(jms.topic, message, listener));
  }

  void should_complete_on_callback(JMSAsync async) throws JMSException {
    async.send(new CompletionListener() {
      @Override public void onCompletion(Message message) {
        tracing.tracer().currentSpanCustomizer().tag("onCompletion", "");
      }

      @Override public void onException(Message message, Exception exception) {
        tracing.tracer().currentSpanCustomizer().tag("onException", "");
      }
    });

    assertThat(testSpanHandler.takeRemoteSpan(Span.Kind.PRODUCER).tags())
      .containsKey("onCompletion");
  }

  @Test
  @Disabled("https://issues.apache.org/jira/browse/ARTEMIS-2054")
  void should_complete_on_error_callback() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);

    // To force error to be on callback thread, we need to wait until message is
    // queued. Only then, shutdown the session.
    try (MessageProducer producer = jms.session.createProducer(jms.queue)) {
      producer.send(jms.queue, message, new CompletionListener() {
        @Override public void onCompletion(Message message) {
          try {
            latch.await();
          } catch (InterruptedException e) {
          }
        }

        @Override public void onException(Message message, Exception exception) {
        }
      });
    }

    // If we hang here, this means the above send is not actually non-blocking!
    // Assuming messages are sent sequentially in a queue, the below should block until the forme
    // went through.
    queueSender.send(jms.queue, message, new CompletionListener() {
      @Override public void onCompletion(Message message) {
        tracing.tracer().currentSpanCustomizer().tag("onCompletion", "");
      }

      @Override public void onException(Message message, Exception exception) {
        tracing.tracer().currentSpanCustomizer().tag("onException", "");
      }
    });

    jms.afterEach(null);
    latch.countDown();

    testSpanHandler.takeRemoteSpanWithErrorTag(Span.Kind.PRODUCER, "onException");
  }

  @Test public void customSampler() throws JMSException {
    MessagingRuleSampler producerSampler = MessagingRuleSampler.newBuilder()
      .putRule(channelNameEquals(jms.queue.getQueueName()), Sampler.NEVER_SAMPLE)
      .build();

    try (MessagingTracing messagingTracing = MessagingTracing.newBuilder(tracing)
      .producerSampler(producerSampler)
      .build();
         JMSContext context = JmsTracing.create(messagingTracing)
           .connectionFactory(((ArtemisJmsExtension) jms).factory)
           .createContext(JMSContext.AUTO_ACKNOWLEDGE)
    ) {
      context.createProducer().send(jms.queue, "foo");
    }

    Message received = queueReceiver.receive();

    assertThat(propertiesToMap(received)).containsKey("b3")
      // Check that the injected context was not sampled
      .satisfies(m -> assertThat(m.get("b3")).endsWith("-0"));

    // @After will also check that the producer was not sampled
  }

  interface JMSAsync {
    void send(CompletionListener listener) throws JMSException;
  }
}
