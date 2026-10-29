package io.github.kuaishou.filter.hook;

import io.github.kuaishou.filter.core.Gender;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded, process-local profile queue. No platform/network code is needed to test its policy. */
final class XiaohongshuGenderCache {
    interface Cancel { void cancel(); }
    interface Scheduler {
        long now();
        /** Enqueue, never run inline. */
        Cancel schedule(Runnable command, long delayMillis);
    }
    interface Completion {
        void success(String returnedUserId, int gender);
        void failure();
    }
    interface Transport { Cancel request(String userId, Completion completion) throws Exception; }

    public static final class Result {
        private static final Result PENDING = new Result(true, Gender.UNKNOWN);
        public final boolean pending;
        public final Gender gender;
        private Result(boolean pending, Gender gender) { this.pending = pending; this.gender = gender; }
        public static Result pending() { return PENDING; }
        public static Result resolved(Gender gender) { return new Result(false, gender == null ? Gender.UNKNOWN : gender); }
    }

    private static final class Entry {
        final Gender gender;
        final long expires;
        Entry(Gender gender, long expires) { this.gender = gender; this.expires = expires; }
    }
    private static final class Request {
        final String userId;
        final long epoch;
        boolean active;
        Cancel task, timeout;
        Request(String userId, long epoch) { this.userId = userId; this.epoch = epoch; }
    }

    private final Scheduler scheduler;
    private final Transport transport;
    private final Runnable changed;
    private final int maxConcurrent, maxQueued, maxCached;
    private final long knownTtl, unknownTtl, errorTtl, timeoutMillis, startGap;
    private final Map<String, Entry> cached = new LinkedHashMap<>(32, .75f, true);
    private final Map<String, Request> requests = new LinkedHashMap<>();
    private boolean enabled, closed;
    private int active;
    private long epoch, nextStart;
    private Cancel pump;

    XiaohongshuGenderCache(Scheduler scheduler, Transport transport, Runnable changed) {
        this(scheduler, transport, changed, 2, 128, 512,
                60 * 60_000L, 5 * 60_000L, 60_000L, 10_000L, 350L);
    }

    XiaohongshuGenderCache(Scheduler scheduler, Transport transport, Runnable changed,
                          int maxConcurrent, int maxQueued, int maxCached, long knownTtl,
                          long unknownTtl, long errorTtl, long timeoutMillis, long startGap) {
        this.scheduler = scheduler; this.transport = transport; this.changed = changed;
        this.maxConcurrent = maxConcurrent; this.maxQueued = maxQueued; this.maxCached = maxCached;
        this.knownTtl = knownTtl; this.unknownTtl = unknownTtl; this.errorTtl = errorTtl;
        this.timeoutMillis = timeoutMillis; this.startGap = startGap;
    }

    synchronized Result lookup(String rawUserId) {
        return lookup(rawUserId, true);
    }

    synchronized Result lookup(String rawUserId, boolean mayRequest) {
        String userId = rawUserId == null ? "" : rawUserId.trim();
        if (!enabled || closed || userId.isEmpty() || userId.length() > 256) return Result.resolved(Gender.UNKNOWN);
        Entry hit = cached.get(userId);
        if (hit != null && hit.expires > scheduler.now()) return Result.resolved(hit.gender);
        if (hit != null) cached.remove(userId);
        if (requests.containsKey(userId)) return Result.pending();
        // Repainting after a result must never restart evicted authors in a large list.
        // Only a real host list submission or saved rule change admits new requests.
        if (!mayRequest) return Result.resolved(Gender.UNKNOWN);
        if (requests.size() >= maxConcurrent + maxQueued) {
            // Do not leave overflow authors in a permanently pending state. A later list
            // submission can try again after the short negative cache expires.
            put(userId, Gender.UNKNOWN, Math.min(errorTtl, 5_000L));
            return Result.resolved(Gender.UNKNOWN);
        }
        requests.put(userId, new Request(userId, epoch));
        arm();
        return Result.pending();
    }

    void setEnabled(boolean value) {
        List<Cancel> cancellations = new ArrayList<>();
        synchronized (this) {
            if (closed || enabled == value) return;
            enabled = value;
            if (!value) {
                epoch++;
                if (pump != null) cancellations.add(pump);
                pump = null;
                for (Request request : requests.values()) {
                    if (request.task != null) cancellations.add(request.task);
                    if (request.timeout != null) cancellations.add(request.timeout);
                }
                requests.clear(); cached.clear(); active = 0; nextStart = 0;
            }
        }
        for (Cancel cancellation : cancellations) cancel(cancellation);
    }

    void close() {
        setEnabled(false);
        synchronized (this) { closed = true; }
    }

    private synchronized void arm() {
        if (!enabled || closed || pump != null || active >= maxConcurrent || requests.size() == active) return;
        pump = scheduler.schedule(this::startOne, Math.max(0, nextStart - scheduler.now()));
    }

    private void startOne() {
        Request selected = null;
        synchronized (this) {
            pump = null;
            if (!enabled || closed || active >= maxConcurrent) return;
            for (Request request : requests.values()) if (!request.active) { selected = request; break; }
            if (selected == null) return;
            selected.active = true;
            active++;
            nextStart = scheduler.now() + startGap;
            Request request = selected;
            request.timeout = scheduler.schedule(() -> complete(request, null, 2, false), timeoutMillis);
            arm();
        }
        Request request = selected;
        try {
            Cancel task = transport.request(request.userId, new Completion() {
                @Override public void success(String returnedUserId, int gender) { complete(request, returnedUserId, gender, true); }
                @Override public void failure() { complete(request, null, 2, false); }
            });
            boolean keep;
            synchronized (this) {
                keep = current(request);
                if (keep) request.task = task;
            }
            // Covers synchronous cache callbacks, timeout and disable before subscribe returns.
            if (!keep) cancel(task);
        } catch (Exception | LinkageError error) { complete(request, null, 2, false); }
    }

    private void complete(Request request, String returnedUserId, int code, boolean success) {
        Cancel timeout, task;
        synchronized (this) {
            if (!current(request)) return;
            requests.remove(request.userId);
            active--;
            boolean sameUser = success && request.userId.equals(returnedUserId);
            Gender gender = sameUser && code == 0 ? Gender.M : sameUser && code == 1 ? Gender.F : Gender.UNKNOWN;
            put(request.userId, gender, !sameUser ? errorTtl : gender == Gender.UNKNOWN ? unknownTtl : knownTtl);
            timeout = request.timeout; task = request.task;
            arm();
        }
        cancel(timeout); cancel(task);
        // Never invoke the bridge while holding the cache lock: its main-loop callback
        // may read this cache. The bridge rechecks enablement/generation before delivery.
        boolean deliver;
        synchronized (this) {
            deliver = enabled && !closed && request.epoch == epoch;
        }
        if (deliver) changed.run();
    }

    private boolean current(Request request) {
        return enabled && !closed && request.epoch == epoch && requests.get(request.userId) == request;
    }

    private void put(String userId, Gender gender, long ttl) {
        cached.put(userId, new Entry(gender, scheduler.now() + ttl));
        while (cached.size() > maxCached) cached.remove(cached.keySet().iterator().next());
    }

    private static void cancel(Cancel cancellation) {
        if (cancellation != null) try { cancellation.cancel(); } catch (RuntimeException ignored) { /* Best effort disposal. */ }
    }
}
