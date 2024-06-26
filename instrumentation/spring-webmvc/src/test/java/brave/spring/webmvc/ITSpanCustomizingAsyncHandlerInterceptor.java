/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave.spring.webmvc;

import brave.Span;
import brave.test.http.ITServletContainer;
import brave.test.http.Jetty9ServerController;
import java.util.EnumSet;
import javax.servlet.DispatcherType;
import javax.servlet.FilterRegistration.Dynamic;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.ContextLoaderListener;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurerAdapter;

import static org.assertj.core.api.Assertions.assertThat;

/** This tests when you use servlet for tracing but MVC for tagging */
class ITSpanCustomizingAsyncHandlerInterceptor extends ITServletContainer {
  public ITSpanCustomizingAsyncHandlerInterceptor() {
    super(new Jetty9ServerController());
  }

  @Test void addsControllerTags() throws Exception {
    get("/foo");

    assertThat(testSpanHandler.takeRemoteSpan(Span.Kind.SERVER).tags())
      .containsEntry("mvc.controller.class", "Servlet3TestController")
      .containsEntry("mvc.controller.method", "foo");
  }

  @Configuration
  @EnableWebMvc
  static class TracingConfig extends WebMvcConfigurerAdapter {
    @Override public void addInterceptors(InterceptorRegistry registry) {
      registry.addInterceptor(new SpanCustomizingAsyncHandlerInterceptor());
    }
  }

  @Override public void init(ServletContextHandler handler) {
    AnnotationConfigWebApplicationContext appContext =
      new AnnotationConfigWebApplicationContext() {
        // overriding this allows us to register dependencies of TracingHandlerInterceptor
        // without passing static state to a configuration class.
        @Override protected void loadBeanDefinitions(DefaultListableBeanFactory beanFactory) {
          beanFactory.registerSingleton("httpTracing", httpTracing);
          super.loadBeanDefinitions(beanFactory);
        }
      };

    appContext.register(Servlet3TestController.class); // the test resource
    appContext.register(TracingConfig.class); // generic tracing setup
    DispatcherServlet servlet = new DispatcherServlet(appContext);
    servlet.setDispatchOptionsRequest(true);
    ServletHolder servletHolder = new ServletHolder(servlet);
    servletHolder.setAsyncSupported(true);
    handler.addServlet(servletHolder, "/*");
    handler.addEventListener(new ContextLoaderListener(appContext));

    // add the trace filter, which lazy initializes a real tracing filter from the spring context
    Dynamic filterRegistration =
      handler.getServletContext().addFilter("tracingFilter", DelegatingTracingFilter.class);
    filterRegistration.setAsyncSupported(true);
    filterRegistration.addMappingForUrlPatterns(EnumSet.allOf(DispatcherType.class), true, "/*");
  }
}
