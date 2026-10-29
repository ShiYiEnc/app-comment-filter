package io.github.appcommentfilter.hook;

import io.github.appcommentfilter.core.RuleEngine;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class BilibiliCommentFilterTest {
    public static class Body { public String getMessage() { return "推广"; } }
    public static class Author { public String getSex() { return "女"; } }
    public static class Control { public String getLocation() { return "IP属地：广东"; } }
    public static class Reply {
        long root, parent;
        boolean broken, absent;
        public long getRoot() { return root; }
        public long getParent() { return parent; }
        public Body getContent() { if (broken) throw new IllegalStateException(); return new Body(); }
        public Author getMember() { return absent ? null : new Author(); }
        public Control getReplyControl() { return absent ? null : new Control(); }
    }
    private BilibiliCommentFilter filter() throws Exception { return new BilibiliCommentFilter(Reply.class); }
    private RuleEngine rules(String mode, String condition) {
        return RuleEngine.compileJson("{\"schemaVersion\":1,\"enabled\":true,\"mode\":\"" + mode
                + "\",\"blacklist\":[{\"id\":\"b\"," + condition + "}],\"whitelist\":[{\"id\":\"w\"," + condition + "}]}");
    }
    @Test public void filtersMainAndTopListsWithoutMutatingInput() throws Exception {
        Reply main = new Reply(); Object decoration = new Object();
        List<?> original = Collections.unmodifiableList(Arrays.asList(main, decoration));
        assertEquals(Collections.singletonList(decoration), filter().filter(original, rules("BLACKLIST", "\"keywords\":[\"推广\"]")));
        assertEquals(2, original.size());
    }
    @Test public void repliesAreKeptEvenIfOnlyParentIsSet() throws Exception {
        Reply nested = new Reply(); nested.root = 99;
        Reply parented = new Reply(); parented.parent = 99;
        List<?> items = Arrays.asList(nested, parented);
        assertSame(items, filter().filter(items, rules("BLACKLIST", "\"keywords\":[\"推广\"]")));
    }
    @Test public void usesChineseServerGenderAndDisplayedLocation() throws Exception {
        assertTrue(filter().filter(Collections.singletonList(new Reply()), rules("BLACKLIST",
                "\"genders\":[\"F\"],\"locations\":[\"广东\"]")).isEmpty());
    }
    @Test public void absentAttributesAreUnknown() throws Exception {
        Reply missing = new Reply(); missing.absent = true;
        assertTrue(filter().filter(Collections.singletonList(missing), rules("BLACKLIST",
                "\"genders\":[\"UNKNOWN\"],\"unknownLocation\":true")).isEmpty());
    }
    @Test public void brokenItemsAndCardsRemainUnderWhitelist() throws Exception {
        Reply broken = new Reply(); broken.broken = true;
        List<?> items = Arrays.asList(broken, new Object(), null);
        assertSame(items, filter().filter(items, rules("WHITELIST_ONLY", "\"keywords\":[\"保留\"]")));
    }
    @Test public void disabledRulesReturnOriginalList() throws Exception {
        List<?> items = Collections.singletonList(new Reply());
        assertSame(items, filter().filter(items, RuleEngine.compileJson(RuleEngine.defaultJson())));
    }
}
