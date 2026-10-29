package io.github.appcommentfilter.hook;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public final class XiaohongshuGenderTasksTest {
    @Test public void timedOutBlockingSubscribeDoesNotBlockTimersAndDisposesLateHandle() throws Exception {
        XiaohongshuGenderTasks tasks = new XiaohongshuGenderTasks();
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1);
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), disposed = new CountDownLatch(1), resolved = new CountDownLatch(1);
        try {
            XiaohongshuGenderCache cache = new XiaohongshuGenderCache(new XiaohongshuGenderCache.Scheduler() {
                public long now() { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()); }
                public XiaohongshuGenderCache.Cancel schedule(Runnable action, long delay) {
                    java.util.concurrent.ScheduledFuture<?> f = timer.schedule(action, delay, TimeUnit.MILLISECONDS);
                    return () -> f.cancel(false);
                }
            }, (id, completion) -> tasks.submit(() -> {
                started.countDown();
                // Model a host subscription that ignores interruption until it returns.
                boolean done = false;
                while (!done) try { release.await(); done = true; } catch (InterruptedException ignored) { }
                return disposed::countDown;
            }, completion::failure), resolved::countDown, 2, 4, 8, 1_000, 1_000, 1_000, 100, 0);
            cache.setEnabled(true); assertTrue(cache.lookup("author").pending);
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertTrue("Timer must resolve even though subscribe is blocked", resolved.await(2, TimeUnit.SECONDS));
            assertFalse(cache.lookup("author").pending);
            release.countDown(); assertTrue("Late subscription must be disposed", disposed.await(2, TimeUnit.SECONDS));
            cache.close();
        } finally { release.countDown(); tasks.close(); timer.shutdownNow(); }
    }
    @Test public void cancelledLateSubscriptionIsDisposedAndFailuresAreReported() throws Exception {
        XiaohongshuGenderTasks tasks = new XiaohongshuGenderTasks();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), disposed = new CountDownLatch(1), failed = new CountDownLatch(1);
        AtomicInteger unexpectedFailure = new AtomicInteger();
        try {
            XiaohongshuGenderCache.Cancel job = tasks.submit(() -> {
                entered.countDown();
                while (true) try { release.await(); break; } catch (InterruptedException ignored) { }
                return disposed::countDown;
            }, unexpectedFailure::incrementAndGet);
            assertTrue(entered.await(2, TimeUnit.SECONDS)); job.cancel(); release.countDown();
            assertTrue(disposed.await(2, TimeUnit.SECONDS)); assertEquals(0, unexpectedFailure.get());
            tasks.submit(() -> { throw new IllegalStateException("host failure"); }, failed::countDown);
            assertTrue(failed.await(2, TimeUnit.SECONDS));
        } finally { release.countDown(); tasks.close(); }
    }
}
