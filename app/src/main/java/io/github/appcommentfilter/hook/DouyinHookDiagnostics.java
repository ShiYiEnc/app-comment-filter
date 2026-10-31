package io.github.appcommentfilter.hook;

import java.util.List;
import java.util.function.Consumer;

/** Temporary bounded metadata-only tracing. Never calls a row/response's toString(). */
final class DouyinHookDiagnostics {
    private final String label;
    private final Consumer<String> log;
    private long hits;
    DouyinHookDiagnostics(String label, Consumer<String> log) { this.label = label; this.log = log; }
    synchronized long enter() { return ++hits; }
    static boolean sample(long hit) { return hit > 0 && hit <= 256 && (hit & (hit - 1)) == 0; }
    void report(long hit, boolean enabled, Object owner, Object argument, List<?> items, DouyinCommentReader reader) {
        if (!sample(hit)) return;
        try {
            log.accept("[CommentFilter][DYProbe] " + label + " hit=" + hit + " enabled=" + enabled
                    + " owner=" + type(owner) + " arg=" + type(argument) + " " + reader.describe(items));
        } catch (RuntimeException | LinkageError error) {
            // Diagnostic failures must not change host data or the filtering decision.
            log.accept("[CommentFilter][DYProbe] " + label + " hit=" + hit + " diagnosticError=" + error.getClass().getSimpleName());
        }
    }
    static String type(Object value) { return value == null ? "null" : value.getClass().getName(); }
}
