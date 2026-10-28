package io.github.kuaishou.filter.hook;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import io.github.kuaishou.filter.core.RuleEngine;
import org.junit.Test;
import static org.junit.Assert.*;

public class CommentListFilterTest {
    public interface Author { String getKUserSex(); }
    public interface Item {
        int getKType(); boolean getKIsSub(); String getKComment();
        String getKAuthorArea(); Author getKUser();
    }
    public static class Fixture implements Item {
        int type = 1;
        boolean sub;
        String text = "推广", location = "广东", sex = "M";
        boolean fail;
        @Override public int getKType() { return type; }
        @Override public boolean getKIsSub() { return sub; }
        @Override public String getKComment() { if (fail) throw new IllegalStateException(); return text; }
        @Override public String getKAuthorArea() { return location; }
        @Override public Author getKUser() { return sex == null ? null : () -> sex; }
    }
    private CommentListFilter filter() throws Exception { return new CommentListFilter(new CommentSnapshotReader(Item.class)); }
    private RuleEngine rules(String mode, String condition) {
        return RuleEngine.compileJson("{\"schemaVersion\":1,\"enabled\":true,\"mode\":\"" + mode
            + "\",\"blacklist\":[{\"id\":\"b\"," + condition + "}],\"whitelist\":[{\"id\":\"w\"," + condition + "}]}");
    }

    @Test public void filtersMainCommentAndPreservesImmutableOriginal() throws Exception {
        Fixture root = new Fixture(); Object card = new Object();
        List<?> original = Collections.unmodifiableList(Arrays.asList(root, card));
        List<?> result = filter().filter(original, rules("BLACKLIST", "\"keywords\":[\"推广\"]"));
        assertEquals(Collections.singletonList(card), result);
        assertEquals(2, original.size());
        assertSame(root, original.get(0));
    }
    @Test public void typeTwoAndParentedRepliesAlwaysStay() throws Exception {
        Fixture reply = new Fixture(); reply.type = 2;
        Fixture parented = new Fixture(); parented.sub = true;
        List<?> original = Arrays.asList(reply, parented);
        assertSame(original, filter().filter(original, rules("BLACKLIST", "\"keywords\":[\"推广\"]")));
    }
    @Test public void whitelistNeverFiltersCardsOrNulls() throws Exception {
        Fixture miss = new Fixture(); miss.text = "普通评论";
        Object card = new Object();
        assertEquals(Arrays.asList(card, null), filter().filter(Arrays.asList(card, miss, null),
                rules("WHITELIST_ONLY", "\"keywords\":[\"保留\"]")));
    }
    @Test public void unknownServerSexAndAbsentUserMatchUnknown() throws Exception {
        Fixture missing = new Fixture(); missing.sex = null;
        Fixture u = new Fixture(); u.sex = "U";
        assertTrue(filter().filter(Arrays.asList(missing, u), rules("BLACKLIST", "\"genders\":[\"UNKNOWN\"]")).isEmpty());
    }
    @Test public void reflectionFailureIsKeptEvenInWhitelistMode() throws Exception {
        Fixture broken = new Fixture(); broken.fail = true;
        List<?> original = Collections.singletonList(broken);
        assertSame(original, filter().filter(original, rules("WHITELIST_ONLY", "\"keywords\":[\"保留\"]")));
    }
    @Test public void unsupportedItemTypesAreKept() throws Exception {
        Fixture unknown = new Fixture(); unknown.type = 0;
        List<?> original = Collections.singletonList(unknown);
        assertSame(original, filter().filter(original, rules("BLACKLIST", "\"keywords\":[\"推广\"]")));
    }

    @Test public void paginationCountsOnlyMainCommentsNotRepliesOrCards() throws Exception {
        Fixture root = new Fixture(); Fixture reply = new Fixture(); reply.type = 2;
        Fixture parented = new Fixture(); parented.sub = true;
        Fixture unsupported = new Fixture(); unsupported.type = 0;
        assertEquals(1, filter().mainCommentCount(Arrays.asList(root, reply, parented, unsupported, new Object(), null)));
    }
}
