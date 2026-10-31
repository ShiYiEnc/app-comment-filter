package io.github.appcommentfilter.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.StringReader;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Thread-safe, immutable compiled configuration. Construct off the host's UI thread. */
public final class RuleEngine {
    public static final int MAX_JSON_CHARS = 262_144;
    private static final int MAX_RULES = 500;
    private static final int MAX_VALUES = 100;
    private static final Set<String> ROOT_KEYS = keysOf("schemaVersion", "enabled",
            "mode", "ignoreKeywordCase", "resolveProfileGender", "blacklist", "whitelist");
    private static final Set<String> RULE_KEYS = keysOf("id", "enabled", "locations", "keywords",
            "genders", "unknownLocation");

    private final boolean enabled;
    private final boolean ignoreKeywordCase;
    private final boolean whitelistOnly;
    private final boolean resolveProfileGender;
    private final List<Rule> blacklist;
    private final List<Rule> whitelist;

    private RuleEngine(boolean enabled, boolean ignoreKeywordCase,
                       boolean whitelistOnly, boolean resolveProfileGender,
                       List<Rule> blacklist, List<Rule> whitelist) {
        this.enabled = enabled;
        this.ignoreKeywordCase = ignoreKeywordCase;
        this.whitelistOnly = whitelistOnly;
        this.resolveProfileGender = resolveProfileGender;
        this.blacklist = Collections.unmodifiableList(new ArrayList<>(blacklist));
        this.whitelist = Collections.unmodifiableList(new ArrayList<>(whitelist));
    }

    public static String defaultJson() {
        return "{\n  \"schemaVersion\": 1,\n  \"enabled\": false,\n"
                + "  \"mode\": \"BLACKLIST\",\n"
                + "  \"ignoreKeywordCase\": true,\n  \"blacklist\": [],\n  \"whitelist\": []\n}";
    }

    public boolean enabled() { return enabled; }

    /** Legacy import field; profile lookup has been removed from all host adapters. */
    @Deprecated
    public boolean resolveProfileGender() { return resolveProfileGender; }

    /** Whether the enabled mode contains a gender condition worth resolving. */
    public boolean usesGenderRules() {
        if (!enabled) return false;
        for (Rule rule : whitelistOnly ? whitelist : blacklist) {
            if (!rule.genders.isEmpty()) return true;
        }
        return false;
    }

    /** Reject invalid input before replacing a running engine. Missing options use safe defaults. */
    public static RuleEngine compileJson(String json) {
        if (json == null || json.length() > MAX_JSON_CHARS) {
            throw invalid("配置为空或超过 " + MAX_JSON_CHARS + " 字符");
        }
        try {
            JsonReader reader = new JsonReader(new StringReader(json));
            reader.setStrictness(Strictness.STRICT);
            JsonElement parsed = JsonParser.parseReader(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT || !parsed.isJsonObject()) {
                throw invalid("配置必须是单个 JSON 对象");
            }
            JsonObject root = parsed.getAsJsonObject();
            keys(root, ROOT_KEYS, "配置");
            JsonElement version = root.get("schemaVersion");
            if (version == null || !version.isJsonPrimitive()
                    || !version.getAsJsonPrimitive().isNumber()
                    || !version.getAsString().equals("1")) {
                throw invalid("schemaVersion 必须为整数 1");
            }
            boolean ignoreCase = bool(root, "ignoreKeywordCase", true);
            String mode = string(root, "mode", "BLACKLIST");
            if (!mode.equals("BLACKLIST") && !mode.equals("WHITELIST_ONLY")) {
                throw invalid("mode 必须为 BLACKLIST 或 WHITELIST_ONLY");
            }
            Set<String> ids = new HashSet<>();
            List<Rule> black = rules(root, "blacklist", ids, ignoreCase);
            List<Rule> white = rules(root, "whitelist", ids, ignoreCase);
            if (ids.size() > MAX_RULES) throw invalid("规则总数最多为 " + MAX_RULES);
            return new RuleEngine(bool(root, "enabled", false), ignoreCase,
                    mode.equals("WHITELIST_ONLY"), bool(root, "resolveProfileGender", false), black, white);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("无法解析规则 JSON: " + e.getMessage(), e);
        }
    }

    public Decision evaluate(Comment comment) {
        return evaluate(comment, true);
    }

    /**
     * Keeps an unresolved profile distinct from a resolved UNKNOWN gender.
     * Genderless blacklist rules still run; a pending whitelist can only retain
     * the comment until every possibly matching gender condition is known.
     * Hosts decide whether profile resolution is enabled before calling this.
     */
    public Decision evaluatePendingGender(Comment comment) {
        Objects.requireNonNull(comment, "comment");
        if (!enabled) return keep("DISABLED");
        if (comment.reply()) return keep("REPLY_EXCLUDED");
        if (!usesGenderRules()) return evaluate(comment);
        if (whitelistOnly) return keep("GENDER_PENDING");
        String location = normalizeLocation(comment.location());
        String text = normalizeKeyword(comment.text(), ignoreKeywordCase);
        for (Rule rule : blacklist) {
            if (rule.genders.isEmpty() && rule.matches(text, location, comment.gender(), true)) {
                return new Decision(true, "BLACKLIST_MATCH", rule.id);
            }
        }
        return keep("GENDER_PENDING");
    }

    /**
     * A host can suspend the gender dimension while keeping its saved conditions.
     * Gender-only rules then become inactive, rather than matching every comment.
     */
    public Decision evaluate(Comment comment, boolean includeGender) {
        Objects.requireNonNull(comment, "comment");
        if (!enabled) return keep("DISABLED");
        if (comment.reply()) return keep("REPLY_EXCLUDED");
        String location = normalizeLocation(comment.location());
        String text = normalizeKeyword(comment.text(), ignoreKeywordCase);
        if (whitelistOnly) {
            boolean effectiveRule = false;
            for (Rule rule : whitelist) {
                if (!rule.appliesWith(includeGender)) continue;
                effectiveRule = true;
                if (rule.matches(text, location, comment.gender(), includeGender)) {
                    return new Decision(false, "WHITELIST_MATCH", rule.id);
                }
            }
            // Empty (or entirely disabled) whitelist must never blank the comment section.
            if (!effectiveRule) return keep("EMPTY_WHITELIST");
            return new Decision(true, "WHITELIST_MISS", null);
        }
        for (Rule rule : blacklist) {
            if (rule.appliesWith(includeGender) && rule.matches(text, location, comment.gender(), includeGender)) {
                return new Decision(true, "BLACKLIST_MATCH", rule.id);
            }
        }
        return keep("NO_MATCH");
    }

    private static Decision keep(String reason) { return new Decision(false, reason, null); }

    private static List<Rule> rules(JsonObject root, String name, Set<String> ids, boolean ignoreCase) {
        List<Rule> result = new ArrayList<>();
        if (!root.has(name)) return result;
        JsonArray entries = array(root.get(name), name, MAX_RULES);
        for (int i = 0; i < entries.size(); i++) {
            JsonElement entry = entries.get(i);
            if (!entry.isJsonObject()) throw invalid(name + " 的规则必须为对象");
            JsonObject object = entry.getAsJsonObject();
            keys(object, RULE_KEYS, name + " 规则");
            String id = string(object, "id", null);
            if (id == null || id.trim().isEmpty() || id.length() > 64 || !ids.add(id)) {
                throw invalid("规则 id 必须唯一且为 1–64 个非空白字符");
            }
            List<String> locations = strings(object, "locations", 128);
            for (int k = 0; k < locations.size(); k++) {
                locations.set(k, normalizeLocation(locations.get(k)));
                if (locations.get(k).isEmpty()) throw invalid("属地条件不能为空");
            }
            List<String> keywords = strings(object, "keywords", 512);
            for (int k = 0; k < keywords.size(); k++) {
                keywords.set(k, normalizeKeyword(keywords.get(k), ignoreCase));
            }
            Set<Gender> genders = new HashSet<>();
            for (String gender : strings(object, "genders", 16)) {
                try { genders.add(Gender.valueOf(gender)); }
                catch (IllegalArgumentException e) { throw invalid("性别必须为 M、F 或 UNKNOWN"); }
            }
            boolean unknownLocation = bool(object, "unknownLocation", false);
            if (locations.isEmpty() && keywords.isEmpty() && genders.isEmpty() && !unknownLocation) {
                throw invalid("规则 " + id + " 至少需要一个条件");
            }
            if (bool(object, "enabled", true)) {
                result.add(new Rule(id, locations, keywords, genders, unknownLocation));
            }
        }
        return result;
    }

    private static List<String> strings(JsonObject object, String key, int maxLength) {
        List<String> result = new ArrayList<>();
        if (!object.has(key)) return result;
        for (JsonElement value : array(object.get(key), key, MAX_VALUES)) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                throw invalid(key + " 必须只包含字符串");
            }
            String text = value.getAsString();
            if (Normalizer.normalize(text, Normalizer.Form.NFKC).trim().isEmpty()
                    || text.length() > maxLength) {
                throw invalid(key + " 条件不能为空，且最多 " + maxLength + " 字符");
            }
            result.add(text);
        }
        return result;
    }

    private static JsonArray array(JsonElement value, String key, int max) {
        if (!value.isJsonArray() || value.getAsJsonArray().size() > max) {
            throw invalid(key + " 必须为数组且最多 " + max + " 项");
        }
        return value.getAsJsonArray();
    }

    private static String string(JsonObject object, String key, String fallback) {
        if (!object.has(key)) return fallback;
        JsonElement value = object.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw invalid(key + " 必须为字符串");
        }
        return value.getAsString();
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        if (!object.has(key)) return fallback;
        JsonElement value = object.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw invalid(key + " 必须为布尔值");
        }
        return value.getAsBoolean();
    }

    private static void keys(JsonObject object, Set<String> allowed, String scope) {
        for (String key : object.keySet()) {
            if (!allowed.contains(key)) throw invalid(scope + " 包含未知字段: " + key);
        }
    }

    public static String normalizeLocation(String location) {
        if (location == null) return "";
        String normalized = Normalizer.normalize(location, Normalizer.Form.NFKC).trim();
        for (String prefix : Arrays.asList("IP属地", "IP 属地", "发布于", "发表于")) {
            if (normalized.startsWith(prefix)) {
                normalized = normalized.substring(prefix.length()).trim();
                if (normalized.startsWith(":")) normalized = normalized.substring(1).trim();
                break;
            }
        }
        return normalized;
    }

    private static String normalizeKeyword(String value, boolean ignoreCase) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC);
        return ignoreCase ? normalized.toLowerCase(Locale.ROOT) : normalized;
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    private static Set<String> keysOf(String... keys) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(keys)));
    }

    private static final class Rule {
        private final String id;
        private final List<String> locations;
        private final List<String> keywords;
        private final Set<Gender> genders;
        private final boolean unknownLocation;

        private Rule(String id, List<String> locations, List<String> keywords,
                     Set<Gender> genders, boolean unknownLocation) {
            this.id = id;
            this.locations = Collections.unmodifiableList(new ArrayList<>(locations));
            this.keywords = Collections.unmodifiableList(new ArrayList<>(keywords));
            this.genders = Collections.unmodifiableSet(new HashSet<>(genders));
            this.unknownLocation = unknownLocation;
        }

        private boolean appliesWith(boolean includeGender) {
            return includeGender || !locations.isEmpty() || unknownLocation || !keywords.isEmpty();
        }

        private boolean matches(String text, String location, Gender gender, boolean includeGender) {
            if ((!locations.isEmpty() || unknownLocation)
                    && !(location.isEmpty() ? unknownLocation : locations.contains(location))) return false;
            if (includeGender && !genders.isEmpty() && !genders.contains(gender)) return false;
            if (!keywords.isEmpty()) {
                for (String keyword : keywords) if (text.contains(keyword)) return true;
                return false;
            }
            return true;
        }
    }
}
