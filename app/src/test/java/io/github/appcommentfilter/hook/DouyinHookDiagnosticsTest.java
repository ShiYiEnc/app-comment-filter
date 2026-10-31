package io.github.appcommentfilter.hook;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class DouyinHookDiagnosticsTest {
    @Test public void tracesBeforeEnabledGateWithoutReadingContentOrReportingFilterSuccess() throws Exception {
        List<String> logs = new ArrayList<>(); int[] batches = {0};
        DouyinCommentReader reader = new DouyinCommentReader(DouyinCommentReaderTest.Row.class, DouyinCommentReaderTest.User.class,
                (read, hidden, error) -> batches[0]++);
        DouyinHookDiagnostics probe = new DouyinHookDiagnostics("consumer exact-member", logs::add);
        DouyinCommentReaderTest.Row main = new DouyinCommentReaderTest.Row();
        main.text = "PRIVATE_COMMENT_DO_NOT_LOG"; main.ipLabel = "PRIVATE_LOCATION_DO_NOT_LOG";
        DouyinCommentReaderTest.Row reply = new DouyinCommentReaderTest.Row(); reply.replyId = "PRIVATE_PARENT_DO_NOT_LOG";
        DouyinCommentReaderTest.Row other = new DouyinCommentReaderTest.Row(); other.commentType = 305;
        List<?> input = Arrays.asList(main, reply, other, "PRIVATE_STRING_OBJECT_DO_NOT_LOG", null);
        probe.report(probe.enter(), false, new Object(), new Object(), input, reader);
        String log = logs.get(0);
        assertTrue(log.contains("enabled=false")); assertTrue(log.contains("items=5"));
        assertTrue(log.contains("main=1 reply=1 otherType=1 foreign=1 null=1"));
        assertTrue(log.contains("parentNonempty=1")); assertTrue(log.contains("commentTypes={1=2, 305=1}"));
        assertFalse(log.contains("PRIVATE_")); assertEquals(0, batches[0]); assertEquals(5, input.size());
        assertEquals("PRIVATE_COMMENT_DO_NOT_LOG", main.text);
    }
    @Test public void hitCountersAreBoundedAndNeverLogObjectToString() throws Exception {
        List<String> logs = new ArrayList<>();
        DouyinHookDiagnostics probe = new DouyinHookDiagnostics("getter", logs::add);
        DouyinCommentReader reader = new DouyinCommentReader(DouyinCommentReaderTest.Row.class, DouyinCommentReaderTest.User.class);
        Object hostile = new Object() { @Override public String toString() { throw new AssertionError("No toString"); } };
        for (int i = 0; i < 10000; i++) probe.report(probe.enter(), true, hostile, hostile, null, reader);
        assertEquals(9, logs.size()); assertTrue(logs.get(8).contains("hit=256"));
        assertTrue(logs.get(0).contains("items=null"));
    }
    @Test public void longListsAreSampledWithinBudgetAndKeptUnchanged() throws Exception {
        DouyinCommentReader reader = new DouyinCommentReader(DouyinCommentReaderTest.Row.class, DouyinCommentReaderTest.User.class);
        List<Object> items = new ArrayList<>();
        for (int i = 0; i < 1000; i++) items.add(new DouyinCommentReaderTest.Row());
        String shape = reader.describe(items);
        assertTrue(shape.contains("items=1000 sampled=256 main=256")); assertEquals(1000, items.size());
    }
}
