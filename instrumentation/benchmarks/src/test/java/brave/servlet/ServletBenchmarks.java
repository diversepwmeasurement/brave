/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave.servlet;

import brave.Tracing;
import brave.baggage.BaggagePropagation;
import brave.baggage.BaggagePropagationConfig.SingleBaggageField;
import brave.http.HttpServerBenchmarks;
import brave.propagation.B3Propagation;
import brave.sampler.Sampler;
import io.undertow.servlet.Servlets;
import io.undertow.servlet.api.DeploymentInfo;
import io.undertow.servlet.api.FilterInfo;
import java.io.IOException;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import static brave.baggage.BaggagePropagationBenchmarks.BAGGAGE_FIELD;
import static javax.servlet.DispatcherType.REQUEST;

public class ServletBenchmarks extends HttpServerBenchmarks {

  static class HelloServlet extends HttpServlet {
    @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp)
      throws IOException {
      // noop if not configured
      BAGGAGE_FIELD.updateValue("FO");
      resp.addHeader("Content-Type", "text/plain; charset=UTF-8");
      resp.getWriter().println("hello world");
    }
  }

  public static class Unsampled extends ForwardingTracingFilter {
    public Unsampled() {
      super(TracingFilter.create(
        Tracing.newBuilder().sampler(Sampler.NEVER_SAMPLE).build()
      ));
    }
  }

  public static class Traced extends ForwardingTracingFilter {
    public Traced() {
      super(TracingFilter.create(Tracing.newBuilder().build()));
    }
  }

  public static class TracedBaggage extends ForwardingTracingFilter {
    public TracedBaggage() {
      super(TracingFilter.create(Tracing.newBuilder()
        .propagationFactory(BaggagePropagation.newFactoryBuilder(B3Propagation.FACTORY)
          .add(SingleBaggageField.remote(BAGGAGE_FIELD)).build())
        .build()));
    }
  }

  public static class Traced128 extends ForwardingTracingFilter {
    public Traced128() {
      super(TracingFilter.create(
        Tracing.newBuilder().traceId128Bit(true).build()));
    }
  }

  @Override protected void init(DeploymentInfo servletBuilder) {
    addFilterMappings(servletBuilder);
    servletBuilder.addServlets(
      Servlets.servlet("HelloServlet", HelloServlet.class).addMapping("/*")
    );
  }

  public static void addFilterMappings(DeploymentInfo servletBuilder) {
    servletBuilder.addFilter(new FilterInfo("Unsampled", Unsampled.class))
      .addFilterUrlMapping("Unsampled", "/unsampled", REQUEST)
      .addFilter(new FilterInfo("Traced", Traced.class))
      .addFilterUrlMapping("Traced", "/traced", REQUEST)
      .addFilter(new FilterInfo("TracedBaggage", TracedBaggage.class))
      .addFilterUrlMapping("TracedBaggage", "/tracedBaggage", REQUEST)
      .addFilter(new FilterInfo("Traced128", Traced128.class))
      .addFilterUrlMapping("Traced128", "/traced128", REQUEST);
  }

  // Convenience main entry-point
  public static void main(String[] args) throws RunnerException {
    Options opt = new OptionsBuilder()
      .addProfiler("gc")
      .include(".*" + ServletBenchmarks.class.getSimpleName() + ".*")
      .build();

    new Runner(opt).run();
  }

  static class ForwardingTracingFilter implements Filter {
    final Filter delegate;

    ForwardingTracingFilter(Filter delegate) {
      this.delegate = delegate;
    }

    @Override public void init(FilterConfig filterConfig) {
    }

    @Override public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse,
      FilterChain filterChain) throws IOException, ServletException {
      delegate.doFilter(servletRequest, servletResponse, filterChain);
    }

    @Override public void destroy() {
    }
  }
}
