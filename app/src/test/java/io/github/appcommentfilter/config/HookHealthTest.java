package io.github.appcommentfilter.config;

import org.junit.Test;
import static org.junit.Assert.*;

public class HookHealthTest {
    private HookHealth health() { return new HookHealth("session", "future", 999, "0.2.4", 1000); }
    @Test public void installingAndEmptyListsNeverClaimReadableComments() {
        HookHealth health = health(); health.installed(); health.batch(0, 0, null, 1100);
        HookHealth.Snapshot state = health.snapshot(1200);
        assertTrue(state.ready()); assertEquals(0, state.lastCommentAt); assertFalse(state.hasIssue());
    }
    @Test public void SuccessfulReadDoesNotRequireARuleMatch() {
        HookHealth health = health(); health.installed(); health.batch(3, 0, null, 1100);
        HookHealth.Snapshot state = health.snapshot(1200);
        assertEquals(3, state.read); assertEquals(0, state.hidden); assertEquals(1100, state.lastCommentAt);
    }
    @Test public void laterSuccessfulItemsDoNotEraseRuntimeFailure() {
        HookHealth health = health(); health.batch(0, 0, new LinkageError(), 1100);
        health.batch(2, 1, null, 1200);
        assertTrue(health.snapshot(1300).hasIssue());
        assertEquals("LinkageError", health.snapshot(1300).runtimeError);
    }
    @Test public void configRecoveryDoesNotClearOtherFailures() {
        HookHealth health = health();
        assertTrue(health.issue("config", "SecurityException"));
        assertFalse(health.issue("config", "SecurityException"));
        health.issue("pagination", "NoSuchMethodException");
        health.issue("config", "");
        assertEquals("", health.snapshot(1200).configError);
        assertTrue(health.snapshot(1200).hasIssue());
    }
    @Test public void newProcessHasNoOldSuccessOrErrorEvidence() {
        HookHealth previous = health(); previous.installed(); previous.batch(20, 10, new RuntimeException(), 1100);
        HookHealth.Snapshot fresh = new HookHealth("new", "future", 1000, "0.2.4", 2000).snapshot(2100);
        assertEquals(0, fresh.read); assertEquals(0, fresh.lastCommentAt); assertFalse(fresh.hasIssue());
    }
    @Test public void failureCannotBeOverwrittenByLateInstallOrReadsAndSnapshotsAreIsolated() {
        HookHealth health = health(); health.installFailed("NoSuchFieldException");
        HookHealth.Snapshot first = health.snapshot(1100); first.installError = "";
        health.installed(); health.batch(1, 0, null, 1200);
        assertEquals("FAILED", health.snapshot(1300).stage);
        assertEquals("NoSuchFieldException", health.snapshot(1300).installError);
    }
}
