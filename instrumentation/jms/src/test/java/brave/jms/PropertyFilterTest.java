/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave.jms;

import java.util.Collections;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.TextMessage;
import org.apache.activemq.command.ActiveMQTextMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static brave.test.util.ClassLoaders.assertRunIsUnloadable;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class PropertyFilterTest {

  @AfterEach void clear() {
    PropertyFilter.MESSAGE_PROPERTIES_BUFFER.remove();
  }

  @Test void filterProperties_message_empty() {
    TextMessage message = new ActiveMQTextMessage();

    PropertyFilter.filterProperties(message, Collections.singleton("b3"));
  }

  @Test void filterProperties_message_allTypes() throws JMSException {
    TextMessage message = newMessageWithAllTypes();
    message.setStringProperty("b3", "00f067aa0ba902b7-00f067aa0ba902b7-1");

    PropertyFilter.filterProperties(message, Collections.singleton("b3"));

    assertThat(message).isEqualToIgnoringGivenFields(newMessageWithAllTypes(), "processAsExpired");
  }

  // When brave-instrumentation-jms is wrapped around an AWS SQSConnectionFactory, PropertyFilter.filterProperties()
  // attempts to re-set properties on the received SQSMessage object. Doing so fails because SQSMessage throws an
  // IllegalArgumentException if either the property name or value are empty. (Even though the properties came from
  // SQSMessage to begin with.) Verify the IllegalArgumentException does not escape its try/catch block resulting in the
  // message silently failing to process.
  //
  // https://github.com/awslabs/amazon-sqs-java-messaging-lib/blob/b462bdceac814c56e75ee0ba638b3928ce8adee1/src/main/java/com/amazon/sqs/javamessaging/message/SQSMessage.java#L904-L909
  @Test void filterProperties_message_handlesOnSetException() throws JMSException {
    Message message = mock(Message.class);
    when(message.getPropertyNames()).thenReturn(
      Collections.enumeration(Collections.singletonList("JMS_SQS_DeduplicationId")));
    when(message.getObjectProperty("JMS_SQS_DeduplicationId")).thenReturn("");
    doThrow(new IllegalArgumentException()).when(message).setObjectProperty(anyString(), eq(""));

    assertThatCode(() -> PropertyFilter.filterProperties(message,
      Collections.singleton("b3"))).doesNotThrowAnyException();
  }

  @Test void filterProperties_message_passesFatalOnSetException() throws JMSException {
    Message message = mock(Message.class);
    when(message.getPropertyNames()).thenReturn(
      Collections.enumeration(Collections.singletonList("JMS_SQS_DeduplicationId")));
    when(message.getObjectProperty("JMS_SQS_DeduplicationId")).thenReturn("");
    doThrow(new LinkageError()).when(message).setObjectProperty(anyString(), eq(""));

    assertThatThrownBy(
      () -> PropertyFilter.filterProperties(message, Collections.singleton("b3"))).isInstanceOf(
      LinkageError.class);
  }

  static TextMessage newMessageWithAllTypes() throws JMSException {
    TextMessage message = new ActiveMQTextMessage();
    setAllPropertyTypes(message);
    return message;
  }

  static void setAllPropertyTypes(TextMessage message) throws JMSException {
    message.setBooleanProperty("boolean", true);
    message.setByteProperty("byte", Byte.MAX_VALUE);
    message.setDoubleProperty("double", Double.MIN_VALUE);
    message.setFloatProperty("float", Float.MIN_VALUE);
    message.setIntProperty("int", Integer.MIN_VALUE);
    message.setLongProperty("long", Long.MIN_VALUE);
    message.setObjectProperty("object", Collections.emptyMap());
    message.setShortProperty("short", Short.MIN_VALUE);
    message.setStringProperty("string", "string");
  }

  @Test void filterProperties_message_doesntPreventClassUnloading() {
    assertRunIsUnloadable(FilterMessage.class, getClass().getClassLoader());
  }

  static class FilterMessage implements Runnable {
    @Override public void run() {
      ActiveMQTextMessage message = new ActiveMQTextMessage();
      try {
        message.setStringProperty("b3", "00f067aa0ba902b7-00f067aa0ba902b7-1");
        message.setIntProperty("one", 1);
      } catch (JMSException e) {
        throw new AssertionError(e);
      }

      PropertyFilter.filterProperties(message, Collections.singleton("b3"));

      try {
        assertThat(message.propertyExists("b3")).isFalse();
        assertThat(message.getIntProperty("one")).isEqualTo(1);
      } catch (JMSException e) {
        throw new AssertionError(e);
      }
    }
  }
}
