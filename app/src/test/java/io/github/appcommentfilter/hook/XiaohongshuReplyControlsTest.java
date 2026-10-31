package io.github.appcommentfilter.hook;

import io.github.appcommentfilter.core.RuleEngine;
import io.github.appcommentfilter.hook.XiaohongshuCommentFilterTest.*;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class XiaohongshuReplyControlsTest {
    public static final class ReplyControl {
        private final String commentId;
        final boolean loading, showCollapseBtn;
        ReplyControl(String owner, boolean loading, boolean collapse) {
            this.commentId = owner; this.loading = loading; this.showCollapseBtn = collapse;
        }
    }
    public static class Container {
        final Row row;
        Container(Row row) { this.row = row; }
        public Row a() { return row; }
    }
    public static final class Downvote {
        final Row row;
        Downvote(Row row) { this.row = row; }
        public Row b() { return row; }
    }
    public static final class WrongControl { int commentId; }

    private XiaohongshuCommentFilter filter() throws Exception { return filter(FilterDiagnostics.NONE); }
    private XiaohongshuCommentFilter filter(FilterDiagnostics diagnostics) throws Exception {
        return new XiaohongshuCommentFilter(Root.class, Row.class, Child.class, Info.class, diagnostics,
                new XiaohongshuRowRelations(Row.class, ReplyControl.class, Container.class, Downvote.class));
    }
    private RuleEngine rules() {
        return RuleEngine.compileJson("{\"schemaVersion\":1,\"enabled\":true,\"blacklist\":[{\"id\":\"r\",\"keywords\":[\"spam\"]}]}");
    }

    @Test public void removesExpandLoadingAndCollapseRowsOnlyForHiddenRoot() throws Exception {
        Main hidden = new Main("1", "spam"), kept = new Main("2", "hello");
        ReplyControl expand = new ReplyControl("1", false, false);
        ReplyControl loading = new ReplyControl("1", true, false);
        ReplyControl collapse = new ReplyControl("1", false, true);
        ReplyControl keptControl = new ReplyControl("2", false, false);
        List<?> input = Arrays.asList(expand, hidden, loading, kept, collapse, keptControl);
        assertEquals(Arrays.asList(kept, keptControl), filter().filter(input, rules()));
        assertEquals(6, input.size());
        assertEquals("1", expand.commentId);
    }

    @Test public void unknownRowsAndUnknownOrEmptyOwnersAreKeptRegardlessOfAdjacency() throws Exception {
        Main hidden = new Main("1", "spam"); Object header = new Object();
        ReplyControl unrelated = new ReplyControl("other", false, false);
        ReplyControl noOwner = new ReplyControl("", false, false);
        ReplyControl missingOwner = new ReplyControl(null, false, false);
        List<?> input = Arrays.asList(hidden, unrelated, header, noOwner, missingOwner, null);
        assertEquals(Arrays.asList(unrelated, header, noOwner, missingOwner, null), filter().filter(input, rules()));
    }

    @Test public void wrappedRootsAndRepliesUseTheSameVerifiedOwnership() throws Exception {
        Container hidden = new Container(new Main("1", "spam"));
        Downvote hiddenToo = new Downvote(new Main("2", "spam"));
        Container child = new Container(new Child("3", "hello", "1"));
        Downvote childToo = new Downvote(new Child("4", "hello", "2"));
        Container kept = new Container(new Main("5", "hello"));
        Downvote keptChild = new Downvote(new Child("6", "spam", "5"));
        ReplyControl keptControl = new ReplyControl("5", false, true);
        List<?> input = Arrays.asList(hidden, child, new ReplyControl("1", false, false), hiddenToo,
                childToo, new ReplyControl("2", true, false), kept, keptChild, keptControl);
        assertEquals(Arrays.asList(kept, keptChild, keptControl), filter().filter(input, rules()));
    }

    @Test public void wrappedReplyFallsBackToItsStoredParentId() throws Exception {
        Main hidden = new Main("1", "spam"); Child child = new Child("2", "hello", "1");
        child.info.rootCommentId = "";
        assertTrue(filter().filter(Arrays.asList(hidden, new Container(child)), rules()).isEmpty());
    }

    @Test public void brokenWrapperIsRetainedAndReportedAlongsideHealthyComments() throws Exception {
        Container broken = new Container(null) {
            @Override public Row a() { throw new IllegalStateException("unreadable"); }
        };
        int[] reads = {0}; Throwable[] failure = {null};
        XiaohongshuCommentFilter filter = filter((read, hidden, error) -> { reads[0] += read; failure[0] = error; });
        assertEquals(List.of(broken), filter.filter(Arrays.asList(broken, new Main("1", "spam")), rules()));
        assertEquals(1, reads[0]); assertNotNull(failure[0]);
    }

    @Test public void disablingRestoresOriginalControlsAndWrappedRowsFromProjection() throws Exception {
        Container root = new Container(new Main("1", "spam"));
        ReplyControl control = new ReplyControl("1", false, false);
        List<?> input = Arrays.asList(root, control);
        XiaohongshuProjection projection = new XiaohongshuProjection(filter()); Object adapter = new Object();
        assertTrue(projection.project(adapter, input, rules()).isEmpty());
        List<?> source = projection.sources().get(adapter);
        List<?> restored = projection.project(adapter, source, RuleEngine.compileJson(RuleEngine.defaultJson()));
        assertEquals(input, restored); assertSame(root, restored.get(0)); assertSame(control, restored.get(1));
    }

    @Test public void incompatibleControlOwnerIsRejectedBeforeFiltering() throws Exception {
        try {
            new XiaohongshuRowRelations(Row.class, WrongControl.class, Container.class, Downvote.class);
            fail("wrong owner type accepted");
        } catch (NoSuchFieldException expected) { }
    }
}
