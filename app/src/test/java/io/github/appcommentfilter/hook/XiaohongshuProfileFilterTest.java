package io.github.appcommentfilter.hook;

import io.github.appcommentfilter.core.Gender;
import io.github.appcommentfilter.core.RuleEngine;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;
import io.github.appcommentfilter.hook.XiaohongshuCommentFilterTest.*;
import io.github.appcommentfilter.hook.XiaohongshuCommentFilter.GenderState;

public final class XiaohongshuProfileFilterTest {
    private XiaohongshuCommentFilter filter(XiaohongshuCommentFilter.GenderLookup lookup) throws Exception {
        return new XiaohongshuCommentFilter(Root.class, Row.class, Child.class, Info.class, lookup);
    }
    private Main row(String id, String user, String text) {
        Main row = new Main(id, text); row.info.user = new Author(user); return row;
    }
    private RuleEngine rules(boolean on, String mode, String condition) {
        return RuleEngine.compileJson("{\"schemaVersion\":1,\"enabled\":true,\"resolveProfileGender\":" + on
                + ",\"mode\":\"" + mode + "\",\"blacklist\":[{\"id\":\"b\"," + condition
                + "}],\"whitelist\":[{\"id\":\"w\"," + condition + "}]}");
    }
    @Test public void offPausesGenderOnlyRulesAndNeverRequestsProfiles() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        XiaohongshuCommentFilter filter = filter(id -> { calls.incrementAndGet(); return new GenderState(false, Gender.F); });
        List<?> input = List.of(row("1", "author", "hello"));
        for (String mode : new String[]{"BLACKLIST", "WHITELIST_ONLY"})
            assertSame(input, filter.filter(input, rules(false, mode, "\"genders\":[\"F\"]")));
        assertEquals(0, calls.get());
    }
    @Test public void offMixedRulesUseKeywordsWithoutLoadingGender() throws Exception {
        XiaohongshuCommentFilter filter = filter(id -> { fail("Switch off must not load profiles"); return null; });
        List<?> input = List.of(row("1", "author", "spam"));
        assertTrue(filter.filter(input, rules(false, "BLACKLIST", "\"keywords\":[\"spam\"],\"genders\":[\"F\"]")).isEmpty());
    }
    @Test public void resolvedAuthorsFilterByIdAndPendingIsNotUnknown() throws Exception {
        Map<String, GenderState> profiles = new HashMap<>();
        profiles.put("a", new GenderState(false, Gender.M));
        profiles.put("b", new GenderState(false, Gender.F));
        profiles.put("c", new GenderState(true, Gender.UNKNOWN));
        XiaohongshuCommentFilter filter = filter(profiles::get);
        Main a = row("1", "a", "hello"), b = row("2", "b", "hello"), c = row("3", "c", "hello");
        List<?> input = List.of(a, b, c);
        assertEquals(List.of(a, c), filter.filter(input, rules(true, "BLACKLIST", "\"genders\":[\"F\"]")));
        assertSame(input, filter.filter(input, rules(true, "BLACKLIST", "\"genders\":[\"UNKNOWN\"]")));
        profiles.put("c", new GenderState(false, Gender.UNKNOWN));
        assertEquals(List.of(a, b), filter.filter(input, rules(true, "BLACKLIST", "\"genders\":[\"UNKNOWN\"]")));
        assertEquals(3, input.size());
    }
    @Test public void pendingWhitelistRetainsUntilResolvedAndRepliesDoNotRequest() throws Exception {
        Map<String, GenderState> profiles = new HashMap<>();
        profiles.put("a", new GenderState(true, Gender.UNKNOWN));
        AtomicInteger calls = new AtomicInteger();
        XiaohongshuCommentFilter filter = filter(id -> { calls.incrementAndGet(); return profiles.get(id); });
        Main root = row("1", "a", "hello"); Child reply = new Child("2", "hello", "other");
        List<?> input = List.of(root, reply);
        RuleEngine rules = rules(true, "WHITELIST_ONLY", "\"genders\":[\"F\"]");
        assertSame(input, filter.filter(input, rules));
        profiles.put("a", new GenderState(false, Gender.M));
        assertEquals(List.of(reply), filter.filter(input, rules));
        assertEquals(2, calls.get());
    }
    @Test public void genderFreeRulesAndIndependentKeywordMatchesDoNotFetch() throws Exception {
        XiaohongshuCommentFilter filter = filter(id -> { fail("No profile needed"); return null; });
        List<?> input = List.of(row("1", "a", "spam"));
        assertTrue(filter.filter(input, rules(true, "BLACKLIST", "\"keywords\":[\"spam\"]")).isEmpty());
        RuleEngine both = RuleEngine.compileJson("{\"schemaVersion\":1,\"enabled\":true,\"resolveProfileGender\":true,"
                + "\"blacklist\":[{\"id\":\"text\",\"keywords\":[\"spam\"]},{\"id\":\"gender\",\"genders\":[\"F\"]}]}");
        assertTrue(filter.filter(input, both).isEmpty());
    }
    @Test public void pendingProjectionRetainsOriginalForReapplyAndDisable() throws Exception {
        Map<String, GenderState> states = new HashMap<>(); states.put("a", new GenderState(true, Gender.UNKNOWN));
        XiaohongshuProjection projection = new XiaohongshuProjection(filter(states::get));
        Object adapter = new Object(); List<?> input = List.of(row("1", "a", "hello"));
        RuleEngine active = rules(true, "BLACKLIST", "\"genders\":[\"F\"]");
        assertSame(input, projection.project(adapter, input, active));
        List<?> backing = projection.sources().get(adapter);
        assertNotNull(backing); assertTrue(projection.isCurrent(adapter, backing));
        states.put("a", new GenderState(false, Gender.F));
        assertTrue(projection.project(adapter, backing, active).isEmpty());
        assertFalse(projection.isCurrent(adapter, backing));
        List<?> latest = projection.sources().get(adapter);
        assertEquals(input, projection.project(adapter, latest, rules(false, "BLACKLIST", "\"genders\":[\"F\"]")));
    }
    @Test public void savingOnCanResolveAnAlreadyVisiblePreviouslyUnfilteredList() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        XiaohongshuProjection projection = new XiaohongshuProjection(filter(id -> {
            requests.incrementAndGet(); return new GenderState(false, Gender.F);
        }));
        Object adapter = new Object(); List<?> original = List.of(row("1", "a", "hello"));
        assertSame(original, projection.project(adapter, original, rules(false, "BLACKLIST", "\"genders\":[\"F\"]")));
        assertEquals(0, requests.get()); assertFalse(projection.requiresFullRefresh(adapter));
        List<?> source = projection.sources().get(adapter); assertNotNull(source);
        assertTrue(projection.project(adapter, source, rules(true, "BLACKLIST", "\"genders\":[\"F\"]")).isEmpty());
        assertEquals(1, requests.get()); assertTrue(projection.requiresFullRefresh(adapter));
    }
}
