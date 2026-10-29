package io.github.appcommentfilter.core;

import org.junit.Test;
import static io.github.appcommentfilter.core.PaginationPolicy.Result.*;
import static org.junit.Assert.*;

public class PaginationPolicyTest {
    @Test public void constructorRejectsNonpositiveLimits() {
        assertThrows(IllegalArgumentException.class, () -> new PaginationPolicy(0, 2));
        assertThrows(IllegalArgumentException.class, () -> new PaginationPolicy(-1, 2));
        assertThrows(IllegalArgumentException.class, () -> new PaginationPolicy(10, 0));
        assertThrows(IllegalArgumentException.class, () -> new PaginationPolicy(10, -1));
    }

    @Test public void whollyFilteredPageRequestsAnotherPage() {
        PaginationPolicy policy = new PaginationPolicy(12, 3);
        assertEquals(LOAD_NEXT, policy.onPage(true, "a", true, 0, 0, true));
    }

    @Test public void fewerThanTargetRetainedCommentsNeedsMore() {
        PaginationPolicy policy = new PaginationPolicy(12, 3);
        assertEquals(LOAD_NEXT, policy.onPage(true, "a", true, 4, 4, true));
        assertEquals(LOAD_NEXT, policy.onPage(false, "b", true, 8, 4, false));
        assertEquals(NONE, policy.onPage(false, "c", true, 12, 4, false));
    }

    @Test public void entirelyFilteredLaterPageContinuesEvenWithManyExistingComments() {
        PaginationPolicy policy = new PaginationPolicy(12, 3);
        assertEquals(NONE, policy.onPage(true, "a", true, 30, 30, false));
        assertEquals(LOAD_NEXT, policy.onPage(false, "b", true, 30, 0, true));
        assertEquals(NONE, policy.onPage(false, "c", true, 31, 1, false));
    }

    @Test public void fullPageWithRetainedAdditionsDoesNotRequestMore() {
        PaginationPolicy policy = new PaginationPolicy(12, 3);
        assertEquals(NONE, policy.onPage(true, "a", true, 12, 12, true));
    }

    @Test public void neverInterferesWithUnfilteredNativePages() {
        PaginationPolicy policy = new PaginationPolicy(12, 3);
        assertEquals(NONE, policy.onPage(true, "a", true, 0, 0, false));
        assertEquals(NONE, policy.onPage(false, "b", true, 2, 2, false));
    }

    @Test public void remembersFilteringAcrossLaterEmptyPages() {
        PaginationPolicy policy = new PaginationPolicy(12, 3);
        assertEquals(LOAD_NEXT, policy.onPage(true, "a", true, 0, 0, true));
        assertEquals(LOAD_NEXT, policy.onPage(false, "b", true, 0, 0, false));
    }

    @Test public void noMoreOverridesShortOrEmptyFilteredResults() {
        PaginationPolicy policy = new PaginationPolicy(12, 3);
        assertEquals(NONE, policy.onPage(true, "a", false, 0, 0, true));
        assertEquals(NONE, policy.continueByUser());
    }

    @Test public void nullBlankAndTerminalCursorsDoNotLoad() {
        for (String cursor : new String[]{null, "", "  \t", "no_more", " NO_MORE "}) {
            PaginationPolicy policy = new PaginationPolicy(12, 3);
            assertEquals(NONE, policy.onPage(true, cursor, true, 0, 0, true));
        }
    }

    @Test public void repeatedCursorStallsAndCannotOfferManualContinuation() {
        PaginationPolicy policy = new PaginationPolicy(12, 3);
        assertEquals(LOAD_NEXT, policy.onPage(true, " a ", true, 0, 0, true));
        assertEquals(STALLED, policy.onPage(false, "a", true, 0, 0, true));
        assertEquals(NONE, policy.continueByUser());
    }

    @Test public void cursorCycleIsDetectedBeyondImmediatePreviousPage() {
        PaginationPolicy policy = new PaginationPolicy(12, 4);
        assertEquals(LOAD_NEXT, policy.onPage(true, "a", true, 0, 0, true));
        assertEquals(LOAD_NEXT, policy.onPage(false, "b", true, 0, 0, true));
        assertEquals(STALLED, policy.onPage(false, "a", true, 0, 0, true));
    }

    @Test public void budgetPausesInsteadOfLoadingForever() {
        PaginationPolicy policy = new PaginationPolicy(12, 2);
        assertEquals(LOAD_NEXT, policy.onPage(true, "a", true, 0, 0, true));
        assertEquals(LOAD_NEXT, policy.onPage(false, "b", true, 0, 0, true));
        assertEquals(ASK_CONTINUE, policy.onPage(false, "c", true, 0, 0, true));
    }

    @Test public void manualContinuationDoesNotConsumeThePausedPageAgain() {
        PaginationPolicy policy = new PaginationPolicy(12, 2);
        policy.onPage(true, "a", true, 0, 0, true);
        policy.onPage(false, "b", true, 0, 0, true);
        assertEquals(ASK_CONTINUE, policy.onPage(false, "c", true, 0, 0, true));
        assertEquals(LOAD_NEXT, policy.continueByUser());
        assertEquals(NONE, policy.continueByUser());
        assertEquals(LOAD_NEXT, policy.onPage(false, "d", true, 0, 0, true));
        assertEquals(ASK_CONTINUE, policy.onPage(false, "e", true, 0, 0, true));
    }

    @Test public void manualContinuationPreservesCursorHistory() {
        PaginationPolicy policy = new PaginationPolicy(12, 1);
        policy.onPage(true, "a", true, 0, 0, true);
        assertEquals(ASK_CONTINUE, policy.onPage(false, "b", true, 0, 0, true));
        assertEquals(LOAD_NEXT, policy.continueByUser());
        assertEquals(STALLED, policy.onPage(false, "a", true, 0, 0, true));
    }

    @Test public void nativeUserScrollRenewsAutomaticBudget() {
        PaginationPolicy policy = new PaginationPolicy(12, 1);
        policy.onPage(true, "a", true, 0, 0, true);
        assertEquals(ASK_CONTINUE, policy.onPage(false, "b", true, 0, 0, true));
        policy.onUserLoad();
        assertEquals(NONE, policy.continueByUser());
        assertEquals(LOAD_NEXT, policy.onPage(false, "c", true, 0, 0, false));
    }

    @Test public void nativeUserScrollDoesNotEraseLoopProtection() {
        PaginationPolicy policy = new PaginationPolicy(12, 1);
        policy.onPage(true, "a", true, 0, 0, true);
        policy.onUserLoad();
        assertEquals(STALLED, policy.onPage(false, "a", true, 0, 0, true));
    }

    @Test public void stopCannotBeRevivedByRefreshOrUserActions() {
        PaginationPolicy policy = new PaginationPolicy(12, 1);
        policy.onPage(true, "a", true, 0, 0, true);
        policy.onPage(false, "b", true, 0, 0, true);
        policy.stop();
        assertEquals(NONE, policy.continueByUser());
        policy.onUserLoad();
        assertEquals(NONE, policy.onPage(false, "c", true, 0, 0, true));
        assertEquals(NONE, policy.onPage(true, "a", true, 0, 0, true));
    }

    @Test public void refreshClearsBudgetAndCursorHistoryForActiveObject() {
        PaginationPolicy policy = new PaginationPolicy(12, 1);
        policy.onPage(true, "a", true, 0, 0, true);
        assertEquals(ASK_CONTINUE, policy.onPage(false, "b", true, 0, 0, true));
        assertEquals(LOAD_NEXT, policy.onPage(true, "a", true, 0, 0, true));
    }

    @Test public void refreshAlsoClearsPreviousFilteringEvidence() {
        PaginationPolicy policy = new PaginationPolicy(12, 1);
        policy.onPage(true, "a", true, 0, 0, true);
        assertEquals(NONE, policy.onPage(true, "a", true, 2, 2, false));
        assertEquals(NONE, policy.onPage(false, "b", true, 2, 0, false));
    }

    @Test public void newCompletionCancelsAnOldContinuationOffer() {
        PaginationPolicy policy = new PaginationPolicy(12, 1);
        policy.onPage(true, "a", true, 0, 0, true);
        assertEquals(ASK_CONTINUE, policy.onPage(false, "b", true, 0, 0, true));
        assertEquals(NONE, policy.onPage(false, "c", false, 0, 0, true));
        assertEquals(NONE, policy.continueByUser());
    }

    @Test public void negativeCommentCountsAreRejected() {
        PaginationPolicy policy = new PaginationPolicy(12, 1);
        assertThrows(IllegalArgumentException.class, () -> policy.onPage(true, "a", true, -1, 0, true));
        assertThrows(IllegalArgumentException.class, () -> policy.onPage(true, "a", true, 0, -1, true));
    }
}
