package io.github.kuaishou.filter.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Serializes/coalesces writes while retaining unsaved input after a failure. */
public final class AutoSaveQueue {
    public interface Writer { void write(String json) throws Exception; }
    public static final class State {
        public final String json;
        public final boolean saving;
        public final String error;
        private State(String json, boolean saving, String error) {
            this.json = json; this.saving = saving; this.error = error;
        }
    }
    private final Executor worker, callbacks;
    private final Writer writer;
    private final Set<Consumer<State>> observers = new LinkedHashSet<>();
    private String desired, error;
    private long generation;
    private boolean running;

    public AutoSaveQueue(String initial, Executor worker, Executor callbacks, Writer writer) {
        RuleEngine.compileJson(initial);
        this.desired = initial; this.worker = worker; this.callbacks = callbacks; this.writer = writer;
    }

    public synchronized State state() { return new State(desired, running, error); }
    public void observe(Consumer<State> observer) { synchronized (this) { observers.add(observer); } publish(); }
    public synchronized void removeObserver(Consumer<State> observer) { observers.remove(observer); }

    public void submit(String json) {
        RuleEngine.compileJson(json);
        enqueue(json, false);
    }

    /** Explicitly persists the latest configuration, even when it is unchanged. */
    public void saveNow() { enqueue(null, true); }

    /** Saves an explicit snapshot; callers may keep a separate unsaved draft. */
    public void saveNow(String json) {
        RuleEngine.compileJson(json);
        enqueue(json, true);
    }

    private void enqueue(String json, boolean force) {
        boolean start;
        synchronized (this) {
            if (json == null) json = desired;
            if (!force && json.equals(desired) && error == null) return;
            desired = json; error = null; generation++;
            start = !running; running = true;
        }
        publish();
        if (start) {
            try { worker.execute(this::drain); }
            catch (RuntimeException e) {
                synchronized (this) { running = false; error = e.getMessage() == null ? "保存任务未启动" : e.getMessage(); }
                publish();
            }
        }
    }

    public void retry() { submit(state().json); }

    private void drain() {
        while (true) {
            String json; long target;
            synchronized (this) { json = desired; target = generation; }
            Exception failure = null;
            try { writer.write(json); } catch (Exception e) { failure = e; }
            boolean finished;
            synchronized (this) {
                finished = target == generation;
                if (finished) {
                    running = false;
                    error = failure == null ? null : failure.getMessage() == null ? "保存失败" : failure.getMessage();
                }
            }
            publish();
            if (finished) return;
        }
    }

    private void publish() {
        callbacks.execute(() -> {
            ArrayList<Consumer<State>> targets;
            synchronized (this) { targets = new ArrayList<>(observers); }
            // Read current state at delivery, so an older callback cannot overwrite newer edits.
            for (Consumer<State> target : targets) target.accept(state());
        });
    }
}
