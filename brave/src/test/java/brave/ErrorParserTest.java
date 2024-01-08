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
package brave;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@Deprecated
@ExtendWith(MockitoExtension.class)
class ErrorParserTest {
  @Mock SpanCustomizer customizer;
  @Mock ScopedSpan scopedSpan;
  ErrorParser parser = new ErrorParser();

  @Test void error_customizer() {
    parser.error(new RuntimeException("this cake is a lie"), customizer);

    verify(customizer).tag("error", "this cake is a lie");
  }

  @Test void error_customizer_asTag() {
    parser.tag(new RuntimeException("this cake is a lie"), customizer);

    verify(customizer).tag("error", "this cake is a lie");
  }

  @Test void error_scopedSpan() {
    parser.error(new RuntimeException("this cake is a lie"), scopedSpan);

    verify(scopedSpan).tag("error", "this cake is a lie");
  }

  @Test void error_noMessage_customizer() {
    parser.error(new RuntimeException(), customizer);

    verify(customizer).tag("error", "RuntimeException");
  }

  @Test void error_noMessage_scopedSpan() {
    parser.error(new RuntimeException(), scopedSpan);

    verify(scopedSpan).tag("error", "RuntimeException");
  }

  @Test void error_noop_customizer() {
    ErrorParser.NOOP.error(new RuntimeException("this cake is a lie"), customizer);

    verifyNoMoreInteractions(customizer);
  }

  @Test void error_noop_scopedSpan() {
    ErrorParser.NOOP.error(new RuntimeException("this cake is a lie"), scopedSpan);

    verifyNoMoreInteractions(scopedSpan);
  }

  @Test void parse_anonymous() {
    assertThat(ErrorParser.parse(new RuntimeException() {
    })).isEqualTo("RuntimeException");
  }

  @Test void parse_anonymous_message() {
    assertThat(ErrorParser.parse(new RuntimeException("this cake is a lie") {
    })).isEqualTo("this cake is a lie");
  }

  ErrorParser subclassErrorParser = new ErrorParser() {
    @Override protected void error(Throwable error, Object span) {
      tag(span, "noterror", "the cake is fine");
    }
  };

  @Test void subclass() {
    subclassErrorParser.error(new RuntimeException("this cake is a lie"), customizer);

    verify(customizer).tag("noterror", "the cake is fine");
  }

  @Test void subclass_asTag() {
    subclassErrorParser.tag(new RuntimeException("this cake is a lie"), customizer);

    verify(customizer).tag("noterror", "the cake is fine");
  }
}
