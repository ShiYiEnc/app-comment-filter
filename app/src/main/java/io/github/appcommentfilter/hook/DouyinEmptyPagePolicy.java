package io.github.appcommentfilter.hook;

import java.util.HashSet;
import java.util.Set;

/** One source's bounded continuation across pages emptied by filtering. */
final class DouyinEmptyPagePolicy {
    static final int MAX_EXTRA_PAGES = 5;
    private final Set<Long> cursors = new HashSet<>();
    private long generation;
    private int extraPages;
    private boolean eligible, completed, notified;

    long page(String kind, long cursor, boolean more, int input, int visible, boolean enabled) {
        generation++;
        completed = false;
        if ("INITIAL".equals(kind) || "REFRESH".equals(kind)) {
            cursors.clear(); extraPages = 0; notified = false;
        }
        boolean changed = cursors.add(cursor);
        eligible = enabled && more && input > 0 && visible == 0 && changed
                && ("INITIAL".equals(kind) || "REFRESH".equals(kind) || "LOAD_MORE".equals(kind));
        if (visible > 0 || !eligible) {
            cursors.clear(); cursors.add(cursor); extraPages = 0; notified = false;
        }
        return generation;
    }
    long complete() { completed = true; return generation; }
    boolean current(long ticket) { return ticket == generation && eligible && completed; }
    boolean exhausted(long ticket) { return current(ticket) && extraPages >= MAX_EXTRA_PAGES; }
    boolean claim(long ticket) {
        if (!current(ticket) || exhausted(ticket)) return false;
        eligible = false; extraPages++; return true;
    }
    boolean notifyLimit(long ticket) {
        if (!exhausted(ticket) || notified) return false;
        notified = true; return true;
    }
    void manualRequest() { generation++; eligible = false; extraPages = 0; notified = false; }
    void cancel() { generation++; eligible = false; completed = false; }
}
