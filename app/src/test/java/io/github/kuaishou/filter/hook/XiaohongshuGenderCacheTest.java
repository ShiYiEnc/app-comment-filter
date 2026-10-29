package io.github.kuaishou.filter.hook;

import io.github.kuaishou.filter.core.Gender;
import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import org.junit.Test;
import static org.junit.Assert.*;

public final class XiaohongshuGenderCacheTest {
    private static final class Task implements Comparable<Task>, XiaohongshuGenderCache.Cancel {
        final long at, order;
        final Runnable command;
        boolean cancelled;
        Task(long at, long order, Runnable command) { this.at = at; this.order = order; this.command = command; }
        @Override public int compareTo(Task other) { int comparison = Long.compare(at, other.at); return comparison == 0 ? Long.compare(order, other.order) : comparison; }
        @Override public void cancel() { cancelled = true; }
    }
    private static final class Clock implements XiaohongshuGenderCache.Scheduler {
        long time, order;
        final PriorityQueue<Task> tasks = new PriorityQueue<>();
        @Override public long now() { return time; }
        @Override public XiaohongshuGenderCache.Cancel schedule(Runnable command, long delay) {
            Task task = new Task(time + delay, order++, command); tasks.add(task); return task;
        }
        void advance(long elapsed) {
            long until = time + elapsed;
            while (!tasks.isEmpty() && tasks.peek().at <= until) {
                Task task = tasks.remove(); time = task.at;
                if (!task.cancelled) task.command.run();
            }
            time = until;
        }
    }
    private static final class Call implements XiaohongshuGenderCache.Cancel {
        final String id;
        final XiaohongshuGenderCache.Completion completion;
        boolean cancelled;
        Call(String id, XiaohongshuGenderCache.Completion completion) { this.id = id; this.completion = completion; }
        @Override public void cancel() { cancelled = true; }
        void resolve(int gender) { completion.success(id, gender); }
    }
    private static final class Transport implements XiaohongshuGenderCache.Transport {
        final List<Call> calls = new ArrayList<>();
        boolean synchronous;
        @Override public XiaohongshuGenderCache.Cancel request(String userId, XiaohongshuGenderCache.Completion completion) {
            Call call = new Call(userId, completion); calls.add(call);
            if (synchronous) call.resolve(1);
            return call;
        }
    }
    private static final class Fixture {
        final Clock clock = new Clock();
        final Transport transport = new Transport();
        int changed;
        final XiaohongshuGenderCache cache;
        Fixture() { this(0); }
        Fixture(long gap) {
            cache = new XiaohongshuGenderCache(clock, transport, () -> changed++, 2, 2, 3,
                    10_000, 2_000, 100, 1_000, gap);
        }
        void enable() { cache.setEnabled(true); }
    }

    @Test public void disabledAndInvalidIdsDoNotStartRequests() {
        Fixture f = new Fixture();
        assertFalse(f.cache.lookup("author").pending);
        f.enable();
        assertFalse(f.cache.lookup(null).pending); assertFalse(f.cache.lookup(" ").pending);
        assertFalse(f.cache.lookup("x".repeat(257)).pending);
        f.clock.advance(10_000); assertTrue(f.transport.calls.isEmpty());
    }

    @Test public void repeatedAuthorsShareRequestAndResolvedGender() {
        Fixture f = new Fixture(); f.enable();
        assertTrue(f.cache.lookup("a").pending); assertTrue(f.cache.lookup("a").pending);
        f.clock.advance(0); assertEquals(1, f.transport.calls.size());
        assertTrue(f.cache.lookup("a").pending);
        f.transport.calls.get(0).resolve(0);
        assertEquals(Gender.M, f.cache.lookup("a").gender); assertFalse(f.cache.lookup("a").pending);
        assertEquals(1, f.changed); assertTrue(f.transport.calls.get(0).cancelled);
    }

    @Test public void boundedConcurrencyAndQueueDoNotLeaveOverflowPending() {
        Fixture f = new Fixture(); f.enable();
        for (String id : List.of("a", "b", "c", "d")) assertTrue(f.cache.lookup(id).pending);
        assertFalse(f.cache.lookup("overflow").pending);
        f.clock.advance(0); assertEquals(2, f.transport.calls.size());
        f.transport.calls.get(0).resolve(1); f.clock.advance(0);
        assertEquals(3, f.transport.calls.size()); assertEquals("c", f.transport.calls.get(2).id);
        f.transport.calls.get(1).resolve(0); f.clock.advance(0);
        assertEquals(4, f.transport.calls.size());
    }

    @Test public void requestsAreSpacedInsteadOfBursting() {
        Fixture f = new Fixture(350); f.enable();
        f.cache.lookup("a"); f.cache.lookup("b");
        f.clock.advance(0); assertEquals(1, f.transport.calls.size());
        f.clock.advance(349); assertEquals(1, f.transport.calls.size());
        f.clock.advance(1); assertEquals(2, f.transport.calls.size());
    }

    @Test public void timeoutReleasesSlotAndIgnoresLateResponseUntilRetry() {
        Fixture f = new Fixture(); f.enable(); f.cache.lookup("a"); f.clock.advance(0);
        Call first = f.transport.calls.get(0);
        f.clock.advance(1_000);
        assertFalse(f.cache.lookup("a").pending); assertEquals(Gender.UNKNOWN, f.cache.lookup("a").gender);
        assertTrue(first.cancelled); first.resolve(0); assertEquals(1, f.changed);
        f.clock.advance(101); assertTrue(f.cache.lookup("a").pending); f.clock.advance(0);
        assertEquals(2, f.transport.calls.size()); first.resolve(0);
        assertTrue(f.cache.lookup("a").pending);
        f.transport.calls.get(1).resolve(1); assertEquals(Gender.F, f.cache.lookup("a").gender);
    }

    @Test public void disablingClearsQueueCancelsSubscriptionsAndRejectsOldGeneration() {
        Fixture f = new Fixture(); f.enable();
        f.cache.lookup("a"); f.cache.lookup("b"); f.cache.lookup("c"); f.clock.advance(0);
        Call old = f.transport.calls.get(0);
        f.cache.setEnabled(false);
        assertTrue(f.transport.calls.get(0).cancelled); assertTrue(f.transport.calls.get(1).cancelled);
        old.resolve(0); f.clock.advance(2_000);
        assertEquals(0, f.changed); assertEquals(2, f.transport.calls.size());
        f.enable(); f.cache.lookup("a"); f.clock.advance(0);
        old.resolve(0); assertTrue(f.cache.lookup("a").pending);
        f.transport.calls.get(2).resolve(1); assertEquals(Gender.F, f.cache.lookup("a").gender);
    }

    @Test public void wrongUserAndUnrecognizedGenderRemainUnknown() {
        Fixture f = new Fixture(); f.enable(); f.cache.lookup("a"); f.clock.advance(0);
        f.transport.calls.get(0).completion.success("different-author", 0);
        assertEquals(Gender.UNKNOWN, f.cache.lookup("a").gender);
        for (int code : List.of(2, 42)) {
            String id = "author" + code; f.cache.lookup(id); f.clock.advance(0);
            f.transport.calls.get(f.transport.calls.size() - 1).resolve(code);
            assertEquals(Gender.UNKNOWN, f.cache.lookup(id).gender);
        }
    }

    @Test public void cacheUsesTtlAndEvictsLeastRecentlyUsedAuthors() {
        Fixture f = new Fixture(); f.enable();
        for (String id : List.of("a", "b", "c")) { f.cache.lookup(id); f.clock.advance(0); f.transport.calls.get(f.transport.calls.size() - 1).resolve(0); }
        f.cache.lookup("a");
        f.cache.lookup("d"); f.clock.advance(0); f.transport.calls.get(3).resolve(1);
        assertFalse(f.cache.lookup("a").pending); assertTrue(f.cache.lookup("b").pending);
        f.clock.advance(0); f.transport.calls.get(4).resolve(0);
        f.clock.advance(10_001); assertTrue(f.cache.lookup("a").pending);
    }

    @Test public void synchronousPreloadResultDisposesReturnedHandleAndDoesNotLeakSlot() {
        Fixture f = new Fixture(); f.enable(); f.transport.synchronous = true;
        f.cache.lookup("a"); f.clock.advance(0);
        assertEquals(Gender.F, f.cache.lookup("a").gender); assertTrue(f.transport.calls.get(0).cancelled);
        f.cache.lookup("b"); f.clock.advance(0);
        assertEquals(Gender.F, f.cache.lookup("b").gender); assertEquals(2, f.changed);
    }

    @Test public void closingCannotBeReenabledOrDeliverLateResults() {
        Fixture f = new Fixture(); f.enable(); f.cache.lookup("a"); f.clock.advance(0);
        f.cache.close(); f.cache.setEnabled(true); f.transport.calls.get(0).resolve(1);
        assertFalse(f.cache.lookup("a").pending); assertEquals(Gender.UNKNOWN, f.cache.lookup("a").gender);
        assertEquals(0, f.changed);
    }

    @Test public void repeatedLargeListRepaintsDoNotRestartEvictedOrOverflowAuthors() {
        Fixture f = new Fixture(); f.enable();
        for (int i = 0; i < 20; i++) f.cache.lookup("author" + i);
        f.clock.advance(0);
        int completed = 0;
        while (completed < f.transport.calls.size()) {
            f.transport.calls.get(completed++).resolve(0);
            for (int i = 0; i < 20; i++) f.cache.lookup("author" + i, false);
            f.clock.advance(0);
            assertTrue("A repaint cannot admit an unbounded second wave", f.transport.calls.size() <= 4);
        }
        assertEquals(4, completed);
        for (int pass = 0; pass < 10; pass++) for (int i = 0; i < 20; i++) f.cache.lookup("author" + i, false);
        f.clock.advance(10_000); assertEquals(4, f.transport.calls.size());
        assertTrue(f.cache.lookup("new-page-author").pending); f.clock.advance(0);
        assertEquals(5, f.transport.calls.size());
    }
}
