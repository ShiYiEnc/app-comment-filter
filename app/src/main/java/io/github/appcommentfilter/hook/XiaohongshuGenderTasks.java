package io.github.appcommentfilter.hook;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** A blocking host subscribe cannot occupy the separate timeout scheduler. */
final class XiaohongshuGenderTasks implements AutoCloseable {
    interface Work { XiaohongshuGenderCache.Cancel run() throws Exception; }
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(2), command -> {
                Thread thread = new Thread(command, "xhs-profile-request"); thread.setDaemon(true); return thread;
            });

    XiaohongshuGenderCache.Cancel submit(Work work, Runnable failed) {
        Task task = new Task();
        try {
            Future<?> future = executor.submit(() -> {
                if (task.cancelled()) return;
                try { task.attach(work.run()); }
                catch (Exception | LinkageError error) { if (!task.cancelled()) failed.run(); }
            });
            task.attach(future);
        } catch (RejectedExecutionException error) { failed.run(); }
        return task;
    }

    @Override public void close() { executor.shutdownNow(); }

    private final class Task implements XiaohongshuGenderCache.Cancel {
        private boolean cancelled;
        private Future<?> future;
        private XiaohongshuGenderCache.Cancel subscription;
        synchronized boolean cancelled() { return cancelled; }
        void attach(Future<?> value) {
            boolean stop;
            synchronized (this) { future = value; stop = cancelled; }
            if (stop) { value.cancel(true); executor.purge(); }
        }
        void attach(XiaohongshuGenderCache.Cancel value) {
            boolean stop;
            synchronized (this) { subscription = value; stop = cancelled; }
            if (stop && value != null) value.cancel();
        }
        @Override public void cancel() {
            Future<?> job; XiaohongshuGenderCache.Cancel disposable;
            synchronized (this) { cancelled = true; job = future; disposable = subscription; }
            if (job != null) job.cancel(true);
            executor.purge();
            if (disposable != null) disposable.cancel();
        }
    }
}
