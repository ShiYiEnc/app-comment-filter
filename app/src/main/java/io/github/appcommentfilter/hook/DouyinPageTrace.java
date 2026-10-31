package io.github.appcommentfilter.hook;

import java.lang.reflect.Field;
import java.util.List;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/** Bounded pagination metadata only; never logs cursor values or comment contents. */
final class DouyinPageTrace {
    private final Field more, cursor;
    private final Consumer<String> log;
    private final WeakHashMap<Object, State> states = new WeakHashMap<>();
    private int nextSource;
    private static final class State {
        int source, events;
        Long cursor;
        boolean tracing;
    }
    DouyinPageTrace(Class<?> response, Consumer<String> log) throws NoSuchFieldException {
        more = response.getField("hasMore"); cursor = response.getField("cursor");
        if (more.getType() != boolean.class || cursor.getType() != long.class)
            throw new NoSuchFieldException("Unexpected pagination metadata");
        this.log = log;
    }
    synchronized void consumed(Object source, Object kind, Object response, List<?> original, List<?> filtered)
            throws IllegalAccessException {
        State state = states.computeIfAbsent(source, key -> { State s = new State(); s.source = ++nextSource; return s; });
        int event = ++state.events;
        state.tracing = event <= 24 || DouyinHookDiagnostics.sample(event);
        long next = cursor.getLong(response);
        boolean advanced = state.cursor == null || next != state.cursor;
        state.cursor = next;
        if (state.tracing) log.accept("[CommentFilter][DYPage] source=" + state.source + " event=" + event
                + " kind=" + (kind instanceof Enum<?> e ? e.name() : "unknown")
                + " input=" + size(original) + " visible=" + size(filtered)
                + " hasMore=" + more.getBoolean(response) + " cursorChanged=" + advanced);
    }
    synchronized void completed(Object source, List<?> rows) {
        State state = states.get(source);
        if (state != null && state.tracing) log.accept("[CommentFilter][DYPage] source=" + state.source
                + " event=" + state.events + " completedRows=" + size(rows));
    }
    private static int size(List<?> rows) { return rows == null ? -1 : rows.size(); }
}
