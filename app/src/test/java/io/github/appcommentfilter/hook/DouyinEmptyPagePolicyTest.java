package io.github.appcommentfilter.hook;

import org.junit.Test;
import static org.junit.Assert.*;

public class DouyinEmptyPagePolicyTest {
    private long empty(DouyinEmptyPagePolicy policy, long cursor) {
        policy.page("LOAD_MORE", cursor, true, 20, 0, true);
        return policy.complete();
    }
    @Test public void skipsSeveralFilteredPagesThenStopsOnVisibleComments() {
        DouyinEmptyPagePolicy policy = new DouyinEmptyPagePolicy();
        for (int i = 1; i <= 3; i++) assertTrue(policy.claim(empty(policy, i * 20)));
        policy.page("LOAD_MORE", 80, true, 20, 2, true);
        assertFalse(policy.claim(policy.complete()));
    }
    @Test public void requiresCompletionAndNeverDuplicatesSameContinuation() {
        DouyinEmptyPagePolicy policy = new DouyinEmptyPagePolicy();
        long ticket = policy.page("INITIAL", 20, true, 20, 0, true);
        assertFalse(policy.claim(ticket));
        assertEquals(ticket, policy.complete());
        assertTrue(policy.claim(ticket));
        assertFalse(policy.claim(ticket));
    }
    @Test public void fiveExtraPagesThenOneLimitNoticeAndManualClickRestartsBudget() {
        DouyinEmptyPagePolicy policy = new DouyinEmptyPagePolicy();
        for (int i = 1; i <= 5; i++) assertTrue(policy.claim(empty(policy, 20 * i)));
        long ticket = empty(policy, 120);
        assertTrue(policy.exhausted(ticket)); assertFalse(policy.claim(ticket));
        assertTrue(policy.notifyLimit(ticket)); assertFalse(policy.notifyLimit(ticket));
        policy.manualRequest();
        assertFalse(policy.current(ticket));
        assertTrue(policy.claim(empty(policy, 140)));
    }
    @Test public void repeatedOrCyclicCursorsStopWithoutInventingEndOfList() {
        DouyinEmptyPagePolicy policy = new DouyinEmptyPagePolicy();
        assertTrue(policy.claim(empty(policy, 20)));
        assertTrue(policy.claim(empty(policy, 40)));
        assertFalse(policy.claim(empty(policy, 20)));
        assertFalse(policy.claim(empty(policy, 20)));
    }
    @Test public void serverEmptyLastPageDisabledAndUnrelatedRequestDoNotTriggerNetwork() {
        for (int scenario = 0; scenario < 5; scenario++) {
            DouyinEmptyPagePolicy policy = new DouyinEmptyPagePolicy();
            policy.page(scenario == 4 ? "LOAD_LATEST" : "LOAD_MORE", 20,
                    scenario != 0, scenario == 1 ? 0 : 20, scenario == 2 ? 1 : 0, scenario != 3);
            assertFalse(policy.claim(policy.complete()));
        }
    }
    @Test public void configurationOrOwnerCancellationInvalidatesScheduledTicket() {
        DouyinEmptyPagePolicy policy = new DouyinEmptyPagePolicy();
        long ticket = empty(policy, 20); policy.cancel();
        assertFalse(policy.claim(ticket));
    }
    @Test public void refreshDiscardsOldResponseAndCursorHistory() {
        DouyinEmptyPagePolicy policy = new DouyinEmptyPagePolicy();
        long old = empty(policy, 20); assertTrue(policy.claim(old));
        long refreshed = policy.page("REFRESH", 20, true, 20, 0, true);
        assertFalse(policy.claim(old));
        policy.complete(); assertTrue(policy.claim(refreshed));
    }
}
