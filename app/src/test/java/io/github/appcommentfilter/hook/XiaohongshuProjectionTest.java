package io.github.appcommentfilter.hook;

import io.github.appcommentfilter.core.RuleEngine;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;
import io.github.appcommentfilter.hook.XiaohongshuCommentFilterTest.*;

public final class XiaohongshuProjectionTest {
    private XiaohongshuProjection projection() throws Exception {
        return new XiaohongshuProjection(new XiaohongshuCommentFilter(Root.class, Row.class, Child.class, Info.class));
    }
    private RuleEngine enabled() {
        return RuleEngine.compileJson("{\"schemaVersion\":1,\"enabled\":true,\"blacklist\":[{\"id\":\"r\",\"keywords\":[\"spam\"]}]}");
    }
    @Test public void deleteRebuildKeepsHiddenRowsAndDisableRestoresThem() throws Exception {
        XiaohongshuProjection projection = projection(); Object adapter = new Object();
        Main hidden = new Main("1", "spam"), deleted = new Main("2", "hello"), kept = new Main("3", "hi");
        List<?> input = Arrays.asList(hidden, deleted, kept);
        assertEquals(Arrays.asList(deleted, kept), projection.project(adapter, input, enabled()));
        assertNull(projection.backingForMutation(adapter));
        Object prior = projection.beginMutation(adapter);
        List<Object> rebuilt = new ArrayList<>(projection.backingForMutation(adapter));
        rebuilt.remove(deleted);
        projection.endMutation(prior);
        assertEquals(List.of(kept), projection.project(adapter, rebuilt, enabled()));
        assertEquals(Arrays.asList(hidden, kept), projection.project(adapter, rebuilt, RuleEngine.compileJson(RuleEngine.defaultJson())));
        assertEquals(3, input.size());
        assertTrue(projection.requiresFullRefresh(adapter));
    }
    @Test public void nestedMutationScopesAndAdaptersRemainIndependent() throws Exception {
        XiaohongshuProjection projection = projection(); Object a = new Object(), b = new Object(), untouched = new Object();
        projection.project(a, List.of(new Main("1", "spam")), enabled());
        projection.project(b, List.of(new Main("2", "spam")), enabled());
        Object outer = projection.beginMutation(a);
        assertNotNull(projection.backingForMutation(a)); assertNull(projection.backingForMutation(b));
        Object inner = projection.beginMutation(b);
        assertNull(projection.backingForMutation(a)); assertNotNull(projection.backingForMutation(b));
        projection.endMutation(inner); assertNotNull(projection.backingForMutation(a));
        projection.endMutation(outer); assertNull(projection.backingForMutation(a));
        assertFalse(projection.requiresFullRefresh(untouched));
    }
}
