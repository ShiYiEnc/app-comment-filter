package io.github.kuaishou.filter.core;

import java.util.HashSet;
import java.util.Set;

/**
 * Decides when filtering needs a bounded continuation through the host's pager.
 * It never changes cursors or host loading state and performs no I/O.
 *
 * The adapter calls onPage exactly once after each successful page completion.
 * A LOAD_NEXT consumes one automatic request from the current user-action budget.
 * The adapter remains responsible for visibility, in-flight requests and errors.
 */
public final class PaginationPolicy {
    public enum Result { NONE, LOAD_NEXT, ASK_CONTINUE, STALLED }

    private final int targetVisible;
    private final int maxAutoPages;
    private final Set<String> seenCursors = new HashSet<>();
    private boolean stopped;
    private boolean filteringSeen;
    private boolean pausedForUser;
    private int automaticRequests;

    public PaginationPolicy(int targetVisible, int maxAutoPages) {
        if (targetVisible <= 0 || maxAutoPages <= 0) {
            throw new IllegalArgumentException("targetVisible and maxAutoPages must be positive");
        }
        this.targetVisible = targetVisible;
        this.maxAutoPages = maxAutoPages;
    }

    /**
     * firstPage starts a fresh session unless this object has been stopped.
     * totalVisible and addedVisible count retained main comments, not cards/replies.
     * filteredThisPage is true only when at least one main comment was removed.
     */
    public synchronized Result onPage(boolean firstPage, String cursor, boolean hasMore,
                                      int totalVisible, int addedVisible, boolean filteredThisPage) {
        if (stopped) return Result.NONE;
        if (totalVisible < 0 || addedVisible < 0) {
            throw new IllegalArgumentException("Visible comment counts must not be negative");
        }
        if (firstPage) {
            seenCursors.clear();
            automaticRequests = 0;
            filteringSeen = false;
        }
        // Every new completion supersedes the previous manual continuation offer.
        pausedForUser = false;
        filteringSeen |= filteredThisPage;
        String nextCursor = cursor == null ? "" : cursor.trim();
        if (!hasMore || nextCursor.isEmpty() || "no_more".equalsIgnoreCase(nextCursor)) {
            return Result.NONE;
        }
        if (!seenCursors.add(nextCursor)) return Result.STALLED;
        if (!filteringSeen || (totalVisible >= targetVisible && addedVisible > 0)) {
            return Result.NONE;
        }
        if (automaticRequests >= maxAutoPages) {
            pausedForUser = true;
            return Result.ASK_CONTINUE;
        }
        automaticRequests++;
        return Result.LOAD_NEXT;
    }

    /**
     * Resume an outstanding ASK_CONTINUE without reprocessing its completed page.
     * The returned LOAD_NEXT is the first request in the renewed budget; repeated
     * taps return NONE until another page explicitly pauses the policy again.
     */
    public synchronized Result continueByUser() {
        if (stopped || !pausedForUser) return Result.NONE;
        pausedForUser = false;
        automaticRequests = 1;
        return Result.LOAD_NEXT;
    }

    /** An actual native user load starts a new budget while keeping cursor history. */
    public synchronized void onUserLoad() {
        if (stopped) return;
        automaticRequests = 0;
        pausedForUser = false;
    }

    /** Permanently cancel this object. Even a first-page callback cannot restart it. */
    public synchronized void stop() {
        stopped = true;
        seenCursors.clear();
        automaticRequests = 0;
        filteringSeen = false;
        pausedForUser = false;
    }
}
