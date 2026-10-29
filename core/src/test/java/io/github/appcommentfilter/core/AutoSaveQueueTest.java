package io.github.appcommentfilter.core;

import org.junit.Test;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import static org.junit.Assert.*;

public class AutoSaveQueueTest {
    static class Pending implements Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable command) { tasks.add(command); }
        void run() { while (!tasks.isEmpty()) tasks.remove().run(); }
    }
    private String config(boolean enabled) { return RuleEngine.defaultJson().replace("\"enabled\": false", "\"enabled\": " + enabled); }

    @Test public void rapidEditsWriteLatestAndCallbacksNeverRestoreOldInput() {
        Pending worker = new Pending(); Pending callbacks = new Pending(); List<String> saved = new ArrayList<>();
        AutoSaveQueue queue = new AutoSaveQueue(config(false), worker, callbacks, saved::add);
        List<String> seen = new ArrayList<>(); queue.observe(s -> seen.add(s.json));
        queue.submit(config(true)); queue.submit(config(false));
        worker.run(); callbacks.run();
        assertEquals(1, saved.size()); assertEquals(config(false), saved.get(0));
        assertFalse(queue.state().saving);
        for (String json : seen) assertEquals(config(false), json);
    }

    @Test public void editsDuringWriteAreSerializedAndReadBackAsLatest() {
        Pending worker = new Pending(); AtomicReference<String> disk = new AtomicReference<>(config(false));
        AtomicReference<AutoSaveQueue> ref = new AtomicReference<>(); List<String> writes = new ArrayList<>();
        AutoSaveQueue queue = new AutoSaveQueue(config(false), worker, Runnable::run, json -> {
            writes.add(json); disk.set(json);
            if (writes.size() == 1) ref.get().submit(config(false));
        });
        ref.set(queue); queue.submit(config(true)); worker.run();
        assertEquals(2, writes.size()); assertEquals(config(false), disk.get());
        AutoSaveQueue reopened = new AutoSaveQueue(disk.get(), worker, Runnable::run, disk::set);
        assertEquals(queue.state().json, reopened.state().json);
    }

    @Test public void failureRetainsInputAndRetryPersistsIt() {
        Pending worker = new Pending(); AtomicBoolean fail = new AtomicBoolean(true);
        AtomicReference<String> disk = new AtomicReference<>(config(false));
        AutoSaveQueue queue = new AutoSaveQueue(config(false), worker, Runnable::run, json -> {
            if (fail.get()) throw new Exception("磁盘不可写"); disk.set(json);
        });
        queue.submit(config(true)); worker.run();
        assertEquals(config(true), queue.state().json); assertEquals(config(false), disk.get());
        assertEquals("磁盘不可写", queue.state().error); assertFalse(queue.state().saving);
        fail.set(false); queue.retry(); worker.run();
        assertEquals(config(true), disk.get()); assertNull(queue.state().error);
    }

    @Test public void staleFailureCannotDiscardNewerEdit() {
        Pending worker = new Pending(); AtomicReference<AutoSaveQueue> ref = new AtomicReference<>();
        List<String> writes = new ArrayList<>();
        AutoSaveQueue queue = new AutoSaveQueue(config(false), worker, Runnable::run, json -> {
            writes.add(json);
            if (writes.size() == 1) { ref.get().submit(config(false)); throw new Exception("旧写入失败"); }
        });
        ref.set(queue); queue.submit(config(true)); worker.run();
        assertEquals(2, writes.size()); assertNull(queue.state().error); assertEquals(config(false), queue.state().json);
    }

    @Test public void invalidConfigNeverReplacesCurrentInputOrStartsWrite() {
        Pending worker = new Pending(); AutoSaveQueue queue = new AutoSaveQueue(config(false), worker, Runnable::run, json -> fail());
        try { queue.submit("{}"); fail(); } catch (IllegalArgumentException expected) { }
        assertEquals(config(false), queue.state().json); assertTrue(worker.tasks.isEmpty());
    }

    @Test public void manualSaveWritesUnchangedConfigAndKeepsNewestPendingEdit() {
        Pending worker = new Pending(); List<String> writes = new ArrayList<>();
        AutoSaveQueue queue = new AutoSaveQueue(config(false), worker, Runnable::run, writes::add);
        queue.saveNow(); worker.run();
        assertEquals(1, writes.size()); assertEquals(config(false), writes.get(0));
        queue.submit(config(true)); queue.saveNow(); worker.run();
        assertEquals(2, writes.size()); assertEquals(config(true), writes.get(1));
        assertFalse(queue.state().saving); assertNull(queue.state().error);
    }

    @Test public void removedActivityObserverReceivesNoQueuedCallbacks() {
        Pending worker = new Pending(); Pending callbacks = new Pending(); List<String> seen = new ArrayList<>();
        AutoSaveQueue queue = new AutoSaveQueue(config(false), worker, callbacks, json -> {});
        Consumer<AutoSaveQueue.State> listener = state -> seen.add(state.json);
        queue.observe(listener); queue.submit(config(true)); queue.removeObserver(listener);
        worker.run(); callbacks.run(); assertTrue(seen.isEmpty());
    }

    @Test public void manualSnapshotSaveRejectsInvalidInputAndPersistsOnlyRequestedDraft() {
        Pending worker = new Pending(); AtomicReference<String> disk = new AtomicReference<>(config(false));
        AutoSaveQueue queue = new AutoSaveQueue(config(false), worker, Runnable::run, disk::set);
        String draft = config(true);
        assertEquals(config(false), disk.get()); assertTrue(worker.tasks.isEmpty());
        try { queue.saveNow("{}"); fail(); } catch (IllegalArgumentException expected) { }
        assertEquals(config(false), queue.state().json); assertTrue(worker.tasks.isEmpty());
        queue.saveNow(draft); draft = config(false); worker.run();
        assertEquals(config(true), disk.get()); assertEquals(config(false), draft);
    }
}
