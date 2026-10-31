package io.github.appcommentfilter.hook;

import io.github.appcommentfilter.core.RuleEngine;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class XiaohongshuCommentFilterTest {
    public interface Row { Info e(); }
    public interface Root extends Row {}
    public static final class Info {
        String id, content, ipLocation, rootCommentId;
        Info(String id, String text, String region, String root) {
            this.id = id; content = text; ipLocation = region; rootCommentId = root;
        }
    }
    public static final class Main implements Root {
        final Info info;
        Main(String id, String text) { info = new Info(id, text, "广东", null); }
        public Info e() { return info; }
    }
    public static final class Child implements Row {
        final Info info;
        String e;
        Child(String id, String text, String owner) { info = new Info(id, text, "上海", owner); e = owner; }
        public Info e() { return info; }
    }
    private XiaohongshuCommentFilter filter() throws Exception {
        return new XiaohongshuCommentFilter(Root.class, Row.class, Child.class, Info.class);
    }
    private RuleEngine rules(String conditions) {
        return RuleEngine.compileJson("{\"schemaVersion\":1,\"enabled\":true,\"resolveProfileGender\":true,\"blacklist\":[{\"id\":\"r\"," + conditions + "}]}");
    }

    @Test public void hidesRootAndItsAttachedRepliesWithoutMutatingModels() throws Exception {
        Main hidden = new Main("1", "spam"), kept = new Main("2", "hello");
        Child child = new Child("3", "hello", "1"), keptChild = new Child("4", "spam", "2");
        List<?> original = Arrays.asList("header", hidden, child, kept, keptChild);
        assertEquals(Arrays.asList("header", kept, keptChild), filter().filter(original, rules("\"keywords\":[\"spam\"]")));
        assertEquals(5, original.size());
        assertEquals("spam", hidden.info.content);
    }
    @Test public void repliesNeverIndependentlyMatchAndUnknownRowsSurvive() throws Exception {
        Child child = new Child("1", "spam", "other");
        Row broken = () -> { throw new IllegalStateException("shape changed"); };
        List<?> input = Arrays.asList(child, broken, new Object());
        assertSame(input, filter().filter(input, rules("\"keywords\":[\"spam\"]")));
    }
    @Test public void allLegacyGenderOnlyRulesArePaused() throws Exception {
        List<?> input = List.of(new Main("1", "hello"));
        assertSame(input, filter().filter(input, rules("\"genders\":[\"F\"]")));
        assertSame(input, filter().filter(input, rules("\"genders\":[\"UNKNOWN\"]")));
    }
    @Test public void honorsLocationAndDisabledState() throws Exception {
        List<?> input = List.of(new Main("1", "hello"));
        assertTrue(filter().filter(input, rules("\"locations\":[\"广东\"]")).isEmpty());
        assertSame(input, filter().filter(input, RuleEngine.compileJson(RuleEngine.defaultJson())));
    }
    @Test public void legacyGenderInMixedRulesDoesNotDisableRemainingConditions() throws Exception {
        Main hidden = new Main("1", "spam"), kept = new Main("2", "hello");
        List<?> input = List.of(hidden, kept);
        assertEquals(List.of(kept), filter().filter(input,
                rules("\"genders\":[\"F\"],\"keywords\":[\"spam\"]")));
    }
    @Test public void genderOnlyWhitelistKeepsEverythingAndMixedWhitelistUsesKeywords() throws Exception {
        Main kept = new Main("1", "hello"), hidden = new Main("2", "spam");
        List<?> input = List.of(kept, hidden);
        String prefix = "{\"schemaVersion\":1,\"enabled\":true,\"mode\":\"WHITELIST_ONLY\",\"whitelist\":[{\"id\":\"r\",\"genders\":[\"UNKNOWN\"]";
        assertSame(input, filter().filter(input, RuleEngine.compileJson(prefix + "}]}")));
        assertEquals(List.of(kept), filter().filter(input,
                RuleEngine.compileJson(prefix + ",\"keywords\":[\"hello\"]}]}")));
    }
    @Test public void unreadableRootIsPreservedInWhitelistMode() throws Exception {
        Root broken = () -> { throw new IllegalStateException("changed"); };
        List<?> input = List.of(broken);
        RuleEngine whitelist = RuleEngine.compileJson("{\"schemaVersion\":1,\"enabled\":true,\"mode\":\"WHITELIST_ONLY\",\"whitelist\":[{\"id\":\"r\",\"keywords\":[\"hello\"]}]}");
        assertSame(input, filter().filter(input, whitelist));
    }
}
