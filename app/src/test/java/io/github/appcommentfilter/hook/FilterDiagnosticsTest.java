package io.github.appcommentfilter.hook;

import io.github.appcommentfilter.core.RuleEngine;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class FilterDiagnosticsTest {
    private static final class Capture implements FilterDiagnostics {
        int read, hidden, calls; Throwable error;
        public void onBatch(int read, int hidden, Throwable error) {
            this.read += read; this.hidden += hidden; this.error = error; calls++;
        }
    }
    private RuleEngine rules() {
        return RuleEngine.compileJson("{\"schemaVersion\":1,\"enabled\":true,\"blacklist\":[{\"id\":\"r\",\"keywords\":[\"spam\"]}]}");
    }
    @Test public void bilibiliReportsUnreadableRowsAndKeepsThem() throws Exception {
        Capture capture = new Capture();
        BilibiliCommentFilterTest.Reply broken = new BilibiliCommentFilterTest.Reply(); broken.broken = true;
        List<?> input = Arrays.asList(broken, new Object());
        assertSame(input, new BilibiliCommentFilter(BilibiliCommentFilterTest.Reply.class, capture).filter(input, rules()));
        assertEquals(0, capture.read); assertNotNull(capture.error);
    }
    @Test public void kuaishouCountsOnlyReadableMainComments() throws Exception {
        Capture capture = new Capture(); CommentListFilterTest.Fixture item = new CommentListFilterTest.Fixture();
        item.text = "spam";
        CommentListFilter filter = new CommentListFilter(new CommentSnapshotReader(CommentListFilterTest.Item.class), capture);
        filter.filter(Arrays.asList(item, new Object()), rules());
        assertEquals(1, capture.read); assertEquals(1, capture.hidden); assertEquals(1, capture.calls);
        assertNull(capture.error);
    }
    @Test public void douyinNoMatchesStillProvesSuccessfulRead() throws Exception {
        Capture capture = new Capture();
        new DouyinCommentReader(DouyinCommentReaderTest.Row.class, DouyinCommentReaderTest.User.class, capture)
                .filter(Arrays.asList(new DouyinCommentReaderTest.Row(), new Object()), rules());
        assertEquals(1, capture.read); assertEquals(0, capture.hidden); assertNull(capture.error);
    }
    @Test public void xhsKeepsBrokenRootAndReportsFailureAlongsideSuccess() throws Exception {
        Capture capture = new Capture();
        XiaohongshuCommentFilter filter = new XiaohongshuCommentFilter(XiaohongshuCommentFilterTest.Root.class,
                XiaohongshuCommentFilterTest.Row.class, XiaohongshuCommentFilterTest.Child.class,
                XiaohongshuCommentFilterTest.Info.class, capture);
        XiaohongshuCommentFilterTest.Root broken = () -> { throw new LinkageError(); };
        List<?> result = filter.filter(Arrays.asList(broken, new XiaohongshuCommentFilterTest.Main("1", "spam")), rules());
        assertEquals(List.of(broken), result); assertEquals(1, capture.read); assertEquals(1, capture.hidden);
        assertNotNull(capture.error);
    }
    @Test public void disabledFilterDoesNotReportEvidence() throws Exception {
        Capture capture = new Capture();
        BilibiliCommentFilter filter = new BilibiliCommentFilter(BilibiliCommentFilterTest.Reply.class, capture);
        filter.filter(List.of(new BilibiliCommentFilterTest.Reply()), RuleEngine.compileJson(RuleEngine.defaultJson()));
        assertEquals(0, capture.calls);
        filter.filter(Arrays.asList(null, new Object()), rules());
        assertEquals(0, capture.read); assertNull(capture.error);
    }
}
