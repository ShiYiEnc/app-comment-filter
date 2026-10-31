package io.github.appcommentfilter.config;

/** Process-local evidence. Reading a hook or an empty list is not evidence of readable comments. */
public final class HookHealth {
    public static final class Snapshot {
        public String session = "", versionName = "", stage = "STARTING", moduleVersion = "";
        public long versionCode, startedAt, updatedAt, lastCommentAt, read, hidden;
        public String installError = "", runtimeError = "", configError = "", paginationError = "";
        public boolean ready() { return "READY".equals(stage); }
        public boolean hasIssue() {
            return !(installError + runtimeError + configError + paginationError).isEmpty();
        }
    }

    private final Snapshot state = new Snapshot();
    public HookHealth(String session, String version, long code, String moduleVersion, long now) {
        state.session = session; state.versionName = version; state.versionCode = code;
        state.moduleVersion = moduleVersion; state.startedAt = now; state.updatedAt = now;
    }
    public synchronized void installed() { if (!"FAILED".equals(state.stage)) state.stage = "READY"; }
    public synchronized void installFailed(String error) { state.stage = "FAILED"; state.installError = error; }
    public synchronized boolean batch(int read, int hidden, Throwable error, long now) {
        state.read += Math.max(0, read); state.hidden += Math.max(0, hidden);
        if (read > 0) state.lastCommentAt = now;
        if (error == null) return false;
        String name = errorName(error);
        boolean changed = !state.runtimeError.equals(name); state.runtimeError = name; return changed;
    }
    public static String errorName(Throwable error) {
        if (error instanceof java.lang.reflect.InvocationTargetException && error.getCause() != null) error = error.getCause();
        String name = error.getClass().getSimpleName(); return name.isEmpty() ? "Error" : name;
    }
    public synchronized boolean issue(String feature, String error) {
        String previous;
        switch (feature) {
            case "config" -> { previous = state.configError; state.configError = error; }
            case "pagination" -> { previous = state.paginationError; state.paginationError = error; }
            default -> { previous = state.runtimeError; state.runtimeError = error; }
        }
        return !previous.equals(error);
    }
    public synchronized Snapshot snapshot(long now) {
        Snapshot copy = new Snapshot();
        copy.session = state.session; copy.versionName = state.versionName; copy.versionCode = state.versionCode;
        copy.moduleVersion = state.moduleVersion; copy.stage = state.stage;
        copy.startedAt = state.startedAt; copy.updatedAt = now; copy.lastCommentAt = state.lastCommentAt;
        copy.read = state.read; copy.hidden = state.hidden;
        copy.installError = state.installError; copy.runtimeError = state.runtimeError;
        copy.configError = state.configError; copy.paginationError = state.paginationError;
        return copy;
    }
}
