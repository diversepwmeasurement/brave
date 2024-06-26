/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave;

import brave.propagation.CurrentTraceContext;
import brave.propagation.StrictCurrentTraceContext;
import brave.propagation.TraceContext;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * This class is in a separate test as ExecutorService has more features than everything else
 *
 * <p>Tests were ported from com.github.kristofa.brave.BraveExecutorServiceTest
 */
class CurrentTraceContextExecutorServiceTest {
  // Ensures one at-a-time, but also on a different thread
  ExecutorService wrappedExecutor = Executors.newSingleThreadExecutor();

  // override default so that it isn't inheritable
  CurrentTraceContext currentTraceContext = StrictCurrentTraceContext.create();
  ExecutorService executor = currentTraceContext.executorService(wrappedExecutor);

  TraceContext context = TraceContext.newBuilder().traceId(1).spanId(1).build();
  TraceContext context2 = TraceContext.newBuilder().traceId(2).spanId(1).build();

  @AfterEach void shutdownExecutor() throws InterruptedException {
    wrappedExecutor.shutdown();
    wrappedExecutor.awaitTermination(1, TimeUnit.SECONDS);
  }

  final TraceContext[] threadValues = new TraceContext[2];
  CountDownLatch latch = new CountDownLatch(1);

  @Test void execute() throws Exception {
    eachTaskHasCorrectSpanAttached(() -> {
      executor.execute(() -> {
        threadValues[0] = currentTraceContext.get();
        try {
          latch.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          e.printStackTrace();
        }
      });
      // this won't run immediately because the other is blocked
      executor.execute(() -> threadValues[1] = currentTraceContext.get());
      return null;
    });
  }

  @Test void submit_Runnable() throws Exception {
    eachTaskHasCorrectSpanAttached(() -> {
      executor.submit(() -> {
        threadValues[0] = currentTraceContext.get();
        try {
          latch.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          e.printStackTrace();
        }
      });
      // this won't run immediately because the other is blocked
      return executor.submit(() -> threadValues[1] = currentTraceContext.get());
    });
  }

  @Test void submit_Callable() throws Exception {
    eachTaskHasCorrectSpanAttached(() -> {
      executor.submit(() -> {
        threadValues[0] = currentTraceContext.get();
        latch.await();
        return true;
      });
      // this won't run immediately because the other is blocked
      return executor.submit(() -> threadValues[1] = currentTraceContext.get());
    });
  }

  @Test void invokeAll() throws Exception {
    eachTaskHasCorrectSpanAttached(() -> executor.invokeAll(asList(
      () -> {
        threadValues[0] = currentTraceContext.get();
        // Can't use externally supplied latch as invokeAll calls get before returning!
        Thread.sleep(100); // block the queue in a dodgy compromise
        return true;
      },
      // this won't run immediately because the other is blocked
      () -> threadValues[1] = currentTraceContext.get())
    ));
  }

  void eachTaskHasCorrectSpanAttached(Callable<?> scheduleTwoTasks) throws Exception {
    // First task should block the queue, forcing the latter to not be scheduled immediately
    // Both should have the same parent, as the parent applies to the task creation time, not
    // execution time.
    try (CurrentTraceContext.Scope scope = currentTraceContext.newScope(context)) {
      scheduleTwoTasks.call();
    }

    // switch the current span to something else. If there's a bug, when the
    // second runnable starts, it will have this span as opposed to the one it was
    // invoked with
    try (CurrentTraceContext.Scope scope = currentTraceContext.newScope(context2)) {
      latch.countDown();
      shutdownExecutor();
      assertThat(threadValues)
        .containsExactly(context, context);
    }
  }
}
