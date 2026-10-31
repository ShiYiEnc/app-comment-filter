package io.github.appcommentfilter.hook;

/** Aggregate one pass without retaining comments or author information. */
public interface FilterDiagnostics {
    FilterDiagnostics NONE = (read, hidden, error) -> { };
    void onBatch(int read, int hidden, Throwable error);
}
