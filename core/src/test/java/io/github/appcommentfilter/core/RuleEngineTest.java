package io.github.appcommentfilter.core;

import org.junit.Test;

import static org.junit.Assert.*;

public class RuleEngineTest {
    private static final Comment AD = new Comment("优惠推广", "广东", Gender.M, false);

    private RuleEngine engine(String options) {
        return RuleEngine.compileJson("{\"schemaVersion\":1,\"enabled\":true," + options + "}");
    }

    private void rejects(String options) {
        assertThrows(IllegalArgumentException.class, () -> engine(options));
    }

    @Test public void defaultConfigIsDisabledAndPreservesComments() {
        Decision decision = RuleEngine.compileJson(RuleEngine.defaultJson()).evaluate(AD);
        assertFalse(decision.hidden());
        assertEquals("DISABLED", decision.reason());
    }

    @Test public void blacklistDoesNotConsultWhitelist() {
        RuleEngine engine = engine("\"mode\":\"BLACKLIST\","
                + "\"blacklist\":[{\"id\":\"b\",\"keywords\":[\"推广\"]}],"
                + "\"whitelist\":[{\"id\":\"w\",\"locations\":[\"广东\"]}]");
        Decision decision = engine.evaluate(AD);
        assertTrue(decision.hidden());
        assertEquals("BLACKLIST_MATCH", decision.reason());
        assertEquals("b", decision.ruleId());
    }

    @Test public void whitelistIgnoresBlacklistAndKeepsMatchedComments() {
        RuleEngine engine = engine("\"mode\":\"WHITELIST_ONLY\","
                + "\"blacklist\":[{\"id\":\"b\",\"keywords\":[\"推广\"]}],"
                + "\"whitelist\":[{\"id\":\"w\",\"locations\":[\"广东\"]}]");
        Decision decision = engine.evaluate(AD);
        assertFalse(decision.hidden());
        assertEquals("WHITELIST_MATCH", decision.reason());
        assertEquals("w", decision.ruleId());
    }

    @Test public void whitelistHidesUnmatchedComments() {
        RuleEngine engine = engine("\"mode\":\"WHITELIST_ONLY\","
                + "\"whitelist\":[{\"id\":\"w\",\"locations\":[\"江苏\"]}]");
        assertEquals("WHITELIST_MISS", engine.evaluate(AD).reason());
        assertTrue(engine.evaluate(AD).hidden());
    }

    @Test public void emptyWhitelistFailsOpenEvenWithMatchingBlacklist() {
        RuleEngine engine = engine("\"mode\":\"WHITELIST_ONLY\",\"whitelist\":[],"
                + "\"blacklist\":[{\"id\":\"b\",\"keywords\":[\"推广\"]}]");
        assertFalse(engine.evaluate(AD).hidden());
        assertEquals("EMPTY_WHITELIST", engine.evaluate(AD).reason());
    }

    @Test public void allDisabledWhitelistFailsOpen() {
        RuleEngine engine = engine("\"mode\":\"WHITELIST_ONLY\","
                + "\"whitelist\":[{\"id\":\"w\",\"enabled\":false,\"keywords\":[\"xyz\"]}]");
        assertFalse(engine.evaluate(AD).hidden());
        assertEquals("EMPTY_WHITELIST", engine.evaluate(AD).reason());
    }

    @Test public void ruleDimensionsAreAndWhileValuesAndGroupsAreOr() {
        RuleEngine engine = engine("\"blacklist\":["
                + "{\"id\":\"first\",\"locations\":[\"广东\",\"江苏\"],"
                + "\"keywords\":[\"推广\",\"广告\"],\"genders\":[\"M\"]},"
                + "{\"id\":\"second\",\"keywords\":[\"独立条件\"]}]");
        assertTrue(engine.evaluate(AD).hidden());
        assertTrue(engine.evaluate(new Comment("广告", "江苏", Gender.M, false)).hidden());
        assertFalse(engine.evaluate(new Comment("推广", "浙江", Gender.M, false)).hidden());
        assertFalse(engine.evaluate(new Comment("正常", "广东", Gender.M, false)).hidden());
        assertFalse(engine.evaluate(new Comment("推广", "广东", Gender.F, false)).hidden());
        assertEquals("second", engine.evaluate(new Comment("独立条件", null, null, false)).ruleId());
    }

    @Test public void absentFieldsDoNotMatchKnownConditions() {
        RuleEngine engine = engine("\"blacklist\":[{\"id\":\"b\",\"locations\":[\"广东\"],\"genders\":[\"M\"]}]");
        assertFalse(engine.evaluate(new Comment("推广", null, null, false)).hidden());
        assertFalse(engine.evaluate(new Comment("推广", "广东", null, false)).hidden());
        assertFalse(engine.evaluate(new Comment("推广", null, Gender.M, false)).hidden());
    }

    @Test public void unknownFieldsCanBeExplicitlyMatched() {
        RuleEngine engine = engine("\"blacklist\":[{\"id\":\"unknown\",\"unknownLocation\":true,\"genders\":[\"UNKNOWN\"]}]");
        assertTrue(engine.evaluate(new Comment(null, null, null, false)).hidden());
        assertTrue(engine.evaluate(new Comment(null, "IP属地： ", Gender.UNKNOWN, false)).hidden());
        assertFalse(engine.evaluate(new Comment(null, "广东", Gender.UNKNOWN, false)).hidden());
        assertFalse(engine.evaluate(new Comment(null, null, Gender.F, false)).hidden());
    }

    @Test public void knownAndUnknownLocationValuesAreOr() {
        RuleEngine engine = engine("\"blacklist\":[{\"id\":\"b\",\"locations\":[\"广东\"],\"unknownLocation\":true}]");
        assertTrue(engine.evaluate(AD).hidden());
        assertTrue(engine.evaluate(new Comment("", null, null, false)).hidden());
        assertFalse(engine.evaluate(new Comment("", "广西", null, false)).hidden());
    }

    @Test public void repliesAreAlwaysExcludedInBothModes() {
        for (String mode : new String[] {"BLACKLIST", "WHITELIST_ONLY"}) {
            RuleEngine engine = engine("\"mode\":\"" + mode + "\","
                    + "\"blacklist\":[{\"id\":\"b\",\"keywords\":[\"推广\"]}],"
                    + "\"whitelist\":[{\"id\":\"w\",\"keywords\":[\"xyz\"]}]");
            Decision decision = engine.evaluate(new Comment("推广", "广东", Gender.M, true));
            assertFalse(decision.hidden());
            assertEquals("REPLY_EXCLUDED", decision.reason());
        }
    }

    @Test public void keywordsNormalizeWidthAndCase() {
        RuleEngine engine = engine("\"blacklist\":[{\"id\":\"b\",\"keywords\":[\"ＡＤ\"]}]");
        assertTrue(engine.evaluate(new Comment("some ad text", null, null, false)).hidden());
        RuleEngine caseSensitive = engine("\"ignoreKeywordCase\":false,"
                + "\"blacklist\":[{\"id\":\"b\",\"keywords\":[\"ＡＤ\"]}]");
        assertFalse(caseSensitive.evaluate(new Comment("some ad text", null, null, false)).hidden());
        assertTrue(caseSensitive.evaluate(new Comment("some AD text", null, null, false)).hidden());
    }

    @Test public void keywordsAreLiteralAndNotRegularExpressions() {
        RuleEngine engine = engine("\"blacklist\":[{\"id\":\"b\",\"keywords\":[\".*\"]}]");
        assertFalse(engine.evaluate(AD).hidden());
        assertTrue(engine.evaluate(new Comment("literal .*", null, null, false)).hidden());
    }

    @Test public void locationsUseExactMatchingAfterDisplayPrefixNormalization() {
        RuleEngine engine = engine("\"blacklist\":[{\"id\":\"b\",\"locations\":[\"广东\"]}]");
        for (String location : new String[] {"广东", "IP属地：广东", "IP 属地: 广东", "发布于 广东", "发表于广东"}) {
            assertTrue(location, engine.evaluate(new Comment("", location, null, false)).hidden());
        }
        assertFalse(engine.evaluate(new Comment("", "广东省", null, false)).hidden());
        assertFalse(engine.evaluate(new Comment("", "广东广州", null, false)).hidden());
    }

    @Test public void disabledRulesNeverMatch() {
        RuleEngine engine = engine("\"blacklist\":[{\"id\":\"b\",\"enabled\":false,\"keywords\":[\"推广\"]}]");
        assertFalse(engine.evaluate(AD).hidden());
    }

    @Test public void emptyConditionsAreRejectedEvenForDisabledRules() {
        rejects("\"blacklist\":[{\"id\":\"b\"}]");
        rejects("\"blacklist\":[{\"id\":\"b\",\"enabled\":false}]");
        rejects("\"blacklist\":[{\"id\":\"b\",\"keywords\":[],\"locations\":[],\"genders\":[]}]");
    }

    @Test public void invalidTypesAndUnknownKeysAreRejected() {
        rejects("\"blacklist\":null");
        rejects("\"blacklist\":[{\"id\":\"b\",\"keywords\":[42]}]");
        rejects("\"blacklist\":[{\"id\":\"b\",\"keywords\":[\"推广\"],\"enabled\":\"true\"}]");
        rejects("\"blacklist\":[{\"id\":\"b\",\"keywords\":[\"推广\"],\"typo\":true}]");
        rejects("\"filterReplies\":true");
        rejects("\"mode\":\"both\"");
        rejects("\"blacklist\":[{\"id\":\"b\",\"genders\":[\"male\"]}]");
    }

    @Test public void blankValuesAndDuplicateIdsAreRejected() {
        rejects("\"blacklist\":[{\"id\":\"b\",\"keywords\":[\"　\"]}]");
        rejects("\"blacklist\":[{\"id\":\"b\",\"locations\":[\"IP属地：\"]}]");
        rejects("\"blacklist\":[{\"id\":\"b\",\"keywords\":[\"推广\"]}],"
                + "\"whitelist\":[{\"id\":\"b\",\"keywords\":[\"正常\"]}]");
    }

    @Test public void malformedOversizedOrUnsupportedJsonIsRejected() {
        for (String json : new String[] {null, "{}", "[]", "{bad}", "{\"schemaVersion\":2}",
                "{\"schemaVersion\":\"1\"}", "{\"schemaVersion\":1} trailing", "{\"schemaVersion\":1,}",
                "{\"schemaVersion\":1,//comment\n\"enabled\":true}"}) {
            assertThrows(IllegalArgumentException.class, () -> RuleEngine.compileJson(json));
        }
        assertThrows(IllegalArgumentException.class,
                () -> RuleEngine.compileJson(new String(new char[RuleEngine.MAX_JSON_CHARS + 1])));
    }

    @Test public void commentSnapshotHandlesNullsAndNoMatch() {
        Comment comment = new Comment(null, null, null, false);
        assertEquals("", comment.text());
        assertEquals(Gender.UNKNOWN, comment.gender());
        assertEquals("NO_MATCH", engine("\"blacklist\":[]").evaluate(comment).reason());
    }

    @Test public void profileGenderResolutionIsOptInAndStrictlyBoolean() {
        assertFalse(RuleEngine.compileJson(RuleEngine.defaultJson()).resolveProfileGender());
        assertFalse(engine("\"blacklist\":[]").resolveProfileGender());
        assertTrue(engine("\"resolveProfileGender\":true").resolveProfileGender());
        assertFalse(engine("\"resolveProfileGender\":false").resolveProfileGender());
        for (String value : new String[]{"\"true\"", "1", "null", "[]", "{}"}) {
            rejects("\"resolveProfileGender\":" + value);
        }
    }

    @Test public void defaultEvaluationStillUsesGenderRegardlessOfProfileOptIn() {
        RuleEngine engine = engine("\"blacklist\":[{\"id\":\"female\",\"genders\":[\"F\"]}]");
        assertFalse(engine.resolveProfileGender());
        assertFalse(engine.evaluate(AD).hidden());
        assertTrue(engine.evaluate(new Comment("normal", "广东", Gender.F, false)).hidden());
    }

    @Test public void suspendedGenderOnlyBlacklistCannotHideEverything() {
        RuleEngine engine = engine("\"blacklist\":[{\"id\":\"male\",\"genders\":[\"M\"]}]");
        assertTrue(engine.evaluate(AD).hidden());
        assertFalse(engine.evaluate(AD, false).hidden());
        assertEquals("NO_MATCH", engine.evaluate(new Comment("hello", null, Gender.UNKNOWN, false), false).reason());
    }

    @Test public void suspendedGenderOnlyWhitelistBecomesEmptyEffectiveWhitelist() {
        RuleEngine engine = engine("\"mode\":\"WHITELIST_ONLY\",\"whitelist\":[{\"id\":\"female\",\"genders\":[\"F\"]}]");
        assertTrue(engine.evaluate(AD).hidden());
        assertFalse(engine.evaluate(AD, false).hidden());
        assertEquals("EMPTY_WHITELIST", engine.evaluate(AD, false).reason());
    }

    @Test public void suspendingGenderKeepsOtherRuleDimensionsAndTheirAndSemantics() {
        RuleEngine engine = engine("\"blacklist\":[{\"id\":\"mixed\",\"locations\":[\"广东\"],\"keywords\":[\"推广\"],\"genders\":[\"F\"]}]");
        assertFalse(engine.evaluate(AD).hidden());
        assertTrue(engine.evaluate(AD, false).hidden());
        assertFalse(engine.evaluate(new Comment("推广", "北京", Gender.F, false), false).hidden());
        assertFalse(engine.evaluate(new Comment("正常", "广东", Gender.F, false), false).hidden());
    }

    @Test public void suspendedGenderWhitelistStillUsesRemainingRules() {
        RuleEngine engine = engine("\"mode\":\"WHITELIST_ONLY\",\"whitelist\":["
                + "{\"id\":\"genderOnly\",\"genders\":[\"M\"]},"
                + "{\"id\":\"mixed\",\"unknownLocation\":true,\"genders\":[\"F\"]}]");
        assertTrue(engine.evaluate(AD, false).hidden());
        Decision unknownRegion = engine.evaluate(new Comment("hello", null, Gender.M, false), false);
        assertFalse(unknownRegion.hidden());
        assertEquals("mixed", unknownRegion.ruleId());
    }

    @Test public void genderResolutionDemandOnlyIncludesEnabledCurrentModeRules() {
        assertFalse(engine("\"blacklist\":[{\"id\":\"off\",\"enabled\":false,\"genders\":[\"M\"]}]").usesGenderRules());
        assertFalse(engine("\"blacklist\":[{\"id\":\"words\",\"keywords\":[\"推广\"]}],"
                + "\"whitelist\":[{\"id\":\"otherMode\",\"genders\":[\"M\"]}]").usesGenderRules());
        assertTrue(engine("\"blacklist\":[{\"id\":\"active\",\"genders\":[\"UNKNOWN\"]}]").usesGenderRules());
        assertTrue(engine("\"mode\":\"WHITELIST_ONLY\",\"whitelist\":[{\"id\":\"active\",\"genders\":[\"F\"]}]").usesGenderRules());
        RuleEngine disabled = RuleEngine.compileJson("{\"schemaVersion\":1,\"enabled\":false,"
                + "\"blacklist\":[{\"id\":\"active\",\"genders\":[\"M\"]}]}");
        assertFalse(disabled.usesGenderRules());
    }

    @Test public void suspendedGenderStillExcludesRepliesAndHonorsMasterSwitch() {
        RuleEngine engine = engine("\"blacklist\":[{\"id\":\"words\",\"keywords\":[\"推广\"],\"genders\":[\"F\"]}]");
        assertEquals("REPLY_EXCLUDED", engine.evaluate(new Comment("推广", "广东", Gender.M, true), false).reason());
        assertEquals("DISABLED", RuleEngine.compileJson(RuleEngine.defaultJson()).evaluate(AD, false).reason());
    }

    @Test public void pendingProfileIsNotAResolvedUnknownGender() {
        RuleEngine engine = engine("\"blacklist\":[{\"id\":\"unknown\",\"genders\":[\"UNKNOWN\"]}]");
        Comment unknown = new Comment("hello", "广东", Gender.UNKNOWN, false);
        assertTrue(engine.evaluate(unknown).hidden());
        Decision pending = engine.evaluatePendingGender(unknown);
        assertFalse(pending.hidden());
        assertEquals("GENDER_PENDING", pending.reason());
        assertNull(pending.ruleId());
    }

    @Test public void pendingBlacklistSkipsEntireMixedGenderRuleInsteadOfItsGenderDimension() {
        RuleEngine engine = engine("\"blacklist\":[{\"id\":\"mixed\",\"keywords\":[\"推广\"],\"genders\":[\"F\"]}]");
        assertTrue(engine.evaluate(AD, false).hidden());
        assertFalse(engine.evaluatePendingGender(AD).hidden());
        assertEquals("GENDER_PENDING", engine.evaluatePendingGender(AD).reason());
    }

    @Test public void pendingBlacklistStillHidesIndependentGenderlessMatches() {
        RuleEngine engine = engine("\"blacklist\":["
                + "{\"id\":\"mixed\",\"keywords\":[\"推广\"],\"genders\":[\"F\"]},"
                + "{\"id\":\"spam\",\"keywords\":[\"推广\"]}]");
        Decision pending = engine.evaluatePendingGender(AD);
        assertTrue(pending.hidden());
        assertEquals("BLACKLIST_MATCH", pending.reason());
        assertEquals("spam", pending.ruleId());
    }

    @Test public void pendingWhitelistRetainsCommentUntilProfileCanResolveItsGenderRule() {
        RuleEngine engine = engine("\"mode\":\"WHITELIST_ONLY\",\"whitelist\":["
                + "{\"id\":\"words\",\"keywords\":[\"not-present\"]},"
                + "{\"id\":\"female\",\"genders\":[\"F\"]}]");
        assertTrue(engine.evaluate(AD).hidden());
        assertEquals("GENDER_PENDING", engine.evaluatePendingGender(AD).reason());
        assertFalse(engine.evaluatePendingGender(AD).hidden());
        assertFalse(engine.evaluate(new Comment("hello", null, Gender.F, false)).hidden());
    }

    @Test public void pendingEvaluationKeepsOriginalSafetyAndNoGenderRuleBehavior() {
        RuleEngine engine = engine("\"mode\":\"WHITELIST_ONLY\",\"whitelist\":["
                + "{\"id\":\"off\",\"enabled\":false,\"genders\":[\"F\"]},"
                + "{\"id\":\"words\",\"keywords\":[\"not-present\"]}]");
        assertTrue(engine.evaluatePendingGender(AD).hidden());
        assertEquals("WHITELIST_MISS", engine.evaluatePendingGender(AD).reason());
        assertEquals("DISABLED", RuleEngine.compileJson(RuleEngine.defaultJson()).evaluatePendingGender(AD).reason());
        RuleEngine withGender = engine("\"blacklist\":[{\"id\":\"mixed\",\"genders\":[\"M\"]}]");
        assertEquals("REPLY_EXCLUDED", withGender.evaluatePendingGender(new Comment("推广", "广东", Gender.M, true)).reason());
    }
}
