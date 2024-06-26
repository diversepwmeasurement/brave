/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave.jakarta.jms;

import brave.Span;
import brave.internal.Nullable;
import brave.messaging.ProducerRequest;
import brave.propagation.Propagation.RemoteGetter;
import brave.propagation.Propagation.RemoteSetter;
import jakarta.jms.Destination;
import jakarta.jms.JMSProducer;

import static brave.internal.Throwables.propagateIfFatal;
import static brave.jakarta.jms.JmsTracing.log;

// intentionally not yet public until we add tag parsing functionality
final class JMSProducerRequest extends ProducerRequest {
  static final RemoteGetter<JMSProducerRequest> GETTER = new RemoteGetter<JMSProducerRequest>() {
    @Override public Span.Kind spanKind() {
      return Span.Kind.PRODUCER;
    }

    @Override public String get(JMSProducerRequest request, String name) {
      return request.getStringProperty(name);
    }

    @Override public String toString() {
      return "JMSProducer::getStringProperty";
    }
  };

  static final RemoteSetter<JMSProducerRequest> SETTER = new RemoteSetter<JMSProducerRequest>() {
    @Override public Span.Kind spanKind() {
      return Span.Kind.PRODUCER;
    }

    @Override public void put(JMSProducerRequest request, String name, String value) {
      request.setProperty(name, value);
    }

    @Override public String toString() {
      return "JMSProducer::setProperty";
    }
  };

  final JMSProducer delegate;
  @Nullable final Destination destination;

  JMSProducerRequest(JMSProducer delegate, @Nullable Destination destination) {
    if (delegate == null) throw new NullPointerException("delegate == null");
    this.delegate = delegate;
    this.destination = destination;
  }

  @Override public Span.Kind spanKind() {
    return Span.Kind.PRODUCER;
  }

  @Override public Object unwrap() {
    return delegate;
  }

  @Override public String operation() {
    return "send";
  }

  @Override public String channelKind() {
    return MessageParser.channelKind(destination);
  }

  @Override public String channelName() {
    return MessageParser.channelName(destination);
  }

  @Override public String messageId() {
    return null; // message ID not available from JMSProducer
  }

  @Nullable String getStringProperty(String name) {
    try {
      return delegate.getStringProperty(name);
    } catch (Throwable t) {
      propagateIfFatal(t);
      log(t, "error getting property {0} from producer {1}", name, delegate);
      return null;
    }
  }

  void setProperty(String name, String value) {
    try {
      delegate.setProperty(name, value);
    } catch (Throwable t) {
      propagateIfFatal(t);
      log(t, "error setting property {0} on producer {1}", name, delegate);
    }
  }
}
