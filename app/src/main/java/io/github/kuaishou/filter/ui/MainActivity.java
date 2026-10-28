package io.github.kuaishou.filter.ui;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.*;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;
import io.github.kuaishou.filter.BuildConfig;
import io.github.kuaishou.filter.R;
import io.github.kuaishou.filter.config.SettingsRepository;
import io.github.kuaishou.filter.config.HostApp;
import io.github.kuaishou.filter.core.AutoSaveQueue;
import io.github.kuaishou.filter.core.RuleEngine;

/** Edits stay in the current draft until the user explicitly saves them. */
public final class MainActivity extends Activity {
    private AutoSaveQueue repository;
    private HostApp host;
    private Bundle drafts = new Bundle(), undos = new Bundle(), saveRequests = new Bundle(), saveSnapshots = new Bundle();
    private boolean observing;
    private JSONObject settings;
    private String visibleJson;
    private LinearLayout ruleList;
    private TextView modeHint, ruleHeading;
    private Switch master;
    private Button blacklistTab, whitelistTab, saveButton, appSelector;
    private boolean saveRequested;
    private String requestedJson;
    private boolean syncing;
    private boolean firstEntry;
    private String undoJson;
    private java.util.function.Supplier<Bundle> editorSnapshot;
    private AlertDialog activeEditor;
    private final Consumer<AutoSaveQueue.State> observer = this::changed;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        firstEntry = state == null;
        host = state == null ? SettingsRepository.selected(this) : HostApp.fromPackage(state.getString("selectedApp", HostApp.KUAISHOU.packageName));
        if (state != null) {
            drafts = state.getBundle("drafts") == null ? new Bundle() : state.getBundle("drafts");
            undos = state.getBundle("undos") == null ? new Bundle() : state.getBundle("undos");
            saveRequests = state.getBundle("saveRequests") == null ? new Bundle() : state.getBundle("saveRequests");
            saveSnapshots = state.getBundle("saveSnapshots") == null ? new Bundle() : state.getBundle("saveSnapshots");
        }
        repository = SettingsRepository.get(this, host);
        visibleJson = drafts.getString(host.packageName, repository.state().json);
        settings = object(visibleJson);
        saveRequested = saveRequests.getBoolean(host.packageName);
        requestedJson = saveSnapshots.getString(host.packageName);
        undoJson = undos.getString(host.packageName);
        buildScreen(); syncScreen(false); updateSaveButton(repository.state());
        Bundle editing = state == null ? null : state.getBundle("editor");
        if (editing != null) {
            if ("json".equals(editing.getString("type"))) editJson(editing.getString("json"));
            else editRule(editing.getString("key"), editing.getInt("index"), editing);
        }
    }

    @Override protected void onStart() { super.onStart(); observing = true; repository.observe(observer); }
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) return;
        boolean night = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        if (android.os.Build.VERSION.SDK_INT >= 30 && getWindow().getInsetsController() != null) {
            int light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            getWindow().getInsetsController().setSystemBarsAppearance(night ? 0 : light, light);
        } else if (android.os.Build.VERSION.SDK_INT < 30) {
            int light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            getWindow().getDecorView().setSystemUiVisibility(night ? 0 : light);
        }
    }
    @Override protected void onStop() { observing = false; repository.removeObserver(observer); super.onStop(); }
    @Override protected void onDestroy() { if (activeEditor != null) activeEditor.dismiss(); super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle state) {
        stashCurrent();
        state.putString("selectedApp", host.packageName);
        state.putBundle("drafts", drafts);
        state.putBundle("undos", undos);
        state.putBundle("saveRequests", saveRequests);
        state.putBundle("saveSnapshots", saveSnapshots);
        if (editorSnapshot != null) state.putBundle("editor", editorSnapshot.get());
        super.onSaveInstanceState(state);
    }

    private void stashCurrent() {
        drafts.putString(host.packageName, visibleJson);
        undos.putString(host.packageName, undoJson);
        saveRequests.putBoolean(host.packageName, saveRequested);
        saveSnapshots.putString(host.packageName, requestedJson);
    }

    private void selectApp() {
        HostApp[] apps = HostApp.values();
        String[] names = new String[apps.length];
        int selected = 0;
        for (int i = 0; i < apps.length; i++) {
            names[i] = apps[i].displayName;
            if (apps[i] == host) selected = i;
        }
        new AlertDialog.Builder(this).setTitle("选择应用")
                .setSingleChoiceItems(names, selected, (dialog, which) -> {
                    if (apps[which] != host) switchApp(apps[which]);
                    dialog.dismiss();
                }).setNegativeButton("取消", null).show();
    }

    private void switchApp(HostApp next) {
        if (observing) repository.removeObserver(observer);
        stashCurrent();
        host = next;
        SettingsRepository.select(this, host);
        repository = SettingsRepository.get(this, host);
        visibleJson = drafts.getString(host.packageName, repository.state().json);
        settings = object(visibleJson);
        undoJson = undos.getString(host.packageName);
        saveRequested = saveRequests.getBoolean(host.packageName);
        requestedJson = saveSnapshots.getString(host.packageName);
        syncScreen(true);
        updateSaveButton(repository.state());
        if (observing) repository.observe(observer);
    }

    private int dp(int size) { return Math.round(size * getResources().getDisplayMetrics().density); }
    private int color(int id) { return getResources().getColor(id, getTheme()); }
    private LinearLayout column() {
        LinearLayout result = new LinearLayout(this); result.setOrientation(LinearLayout.VERTICAL); return result;
    }

    private void buildScreen() {
        LinearLayout root = column(); root.setBackgroundColor(color(R.color.page));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(root);
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), dp(14), dp(12), dp(8));
        header.addView(text("评论过滤", 26, R.color.ink, true), new LinearLayout.LayoutParams(0, -2, 1));
        Button more = button("更多", false); more.setContentDescription("更多设置");
        more.setOnClickListener(v -> more(v)); header.addView(more); root.addView(header);

        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        LinearLayout body = column(); body.setPadding(dp(20), dp(8), dp(20), dp(12)); scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        appSelector = button("", false); appSelector.setId(R.id.app_selector);
        appSelector.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        appSelector.setPadding(dp(16), 0, dp(16), 0);
        appSelector.setBackground(ripple(R.color.surface, 12));
        appSelector.setOnClickListener(v -> selectApp());
        margin(body, appSelector, 0, 12);

        LinearLayout activation = new LinearLayout(this); activation.setGravity(Gravity.CENTER_VERTICAL);
        activation.setPadding(dp(16), dp(14), dp(12), dp(14)); activation.setBackground(shape(R.color.surface, 14));
        LinearLayout activationText = column();
        activationText.addView(text("启用过滤", 18, R.color.ink, true));
        TextView subtitle = text("仅处理主评论", 14, R.color.muted, false); subtitle.setPadding(0, dp(4), 0, 0);
        activationText.addView(subtitle);
        activation.addView(activationText, new LinearLayout.LayoutParams(0, -2, 1));
        master = new Switch(this); master.setId(R.id.filter_enabled); master.setSaveEnabled(false); master.setContentDescription("启用过滤");
        master.setMinHeight(dp(48)); activation.addView(master);
        activation.setOnClickListener(v -> master.setChecked(!master.isChecked()));
        master.setOnCheckedChangeListener((v, checked) -> { if (!syncing) change("enabled", checked); });
        body.addView(activation);

        margin(body, text("过滤模式", 14, R.color.muted, true), 24, 8);
        LinearLayout modes = new LinearLayout(this); modes.setPadding(dp(4), dp(4), dp(4), dp(4));
        modes.setBackground(shape(R.color.well, 12));
        blacklistTab = button("黑名单", false); blacklistTab.setId(R.id.mode_blacklist);
        whitelistTab = button("白名单", false); whitelistTab.setId(R.id.mode_whitelist);
        modes.addView(blacklistTab, new LinearLayout.LayoutParams(0, -2, 1));
        modes.addView(whitelistTab, new LinearLayout.LayoutParams(0, -2, 1));
        blacklistTab.setOnClickListener(v -> {
            if (!"BLACKLIST".equals(settings.optString("mode"))) change("mode", "BLACKLIST");
        });
        whitelistTab.setOnClickListener(v -> {
            if (!"WHITELIST_ONLY".equals(settings.optString("mode"))) change("mode", "WHITELIST_ONLY");
        });
        body.addView(modes);
        modeHint = text("", 14, R.color.muted, false); margin(body, modeHint, 8, 0);
        ruleHeading = text("", 18, R.color.ink, true); margin(body, ruleHeading, 28, 10);
        ruleList = column(); ruleList.setId(R.id.rule_list); body.addView(ruleList);

        LinearLayout bottom = column(); bottom.setPadding(dp(20), dp(8), dp(20), dp(8));
        Button add = button("添加规则", true); add.setId(R.id.add_rule);
        add.setOnClickListener(v -> editRule(activeKey(), -1));
        add.setMinHeight(dp(52)); bottom.addView(add, new LinearLayout.LayoutParams(-1, -2));
        saveButton = button("保存规则", false); saveButton.setId(R.id.save_rules);
        saveButton.setBackground(ripple(R.color.save_background, 10));
        saveButton.setOnClickListener(v -> {
            saveRequested = true;
            requestedJson = visibleJson;
            repository.saveNow(visibleJson);
            updateSaveButton(repository.state());
        });
        margin(bottom, saveButton, 8, 0);
        root.addView(bottom);
        if (firstEntry && ValueAnimator.areAnimatorsEnabled()) {
            bottom.setAlpha(0); bottom.animate().alpha(1).setDuration(180).start();
        }
    }

    private void syncScreen(boolean animate) {
        appSelector.setText(getString(R.string.app_selector_label, host.displayName));
        appSelector.setContentDescription("选择应用，当前：" + host.displayName);
        master.setContentDescription("启用" + host.displayName + "评论过滤");
        syncing = true; master.setChecked(settings.optBoolean("enabled", false)); syncing = false;
        boolean black = activeKey().equals("blacklist");
        tab(blacklistTab, black); tab(whitelistTab, !black);
        JSONArray entries = array(settings, activeKey());
        boolean hasEnabled = false;
        for (int i = 0; i < entries.length(); i++) if (entries.optJSONObject(i).optBoolean("enabled", true)) hasEnabled = true;
        modeHint.setText(black ? "隐藏命中的评论" : !hasEnabled ? "无启用规则时显示全部评论" : "只显示命中的评论");
        ruleHeading.setText(getString(R.string.rule_heading, black ? "黑名单" : "白名单", entries.length()));
        ruleList.removeAllViews();
        if (entries.length() == 0) {
            LinearLayout empty = column(); empty.setPadding(dp(4), dp(24), dp(4), dp(24));
            empty.addView(text("暂无规则", 18, R.color.ink, false));
            TextView hint = text("添加属地、关键词或性别条件", 14, R.color.muted, false);
            hint.setPadding(0, dp(8), 0, 0); empty.addView(hint); ruleList.addView(empty);
        }
        for (int i = 0; i < entries.length(); i++) renderRule(activeKey(), i, entries.optJSONObject(i));
        if (animate && ValueAnimator.areAnimatorsEnabled()) {
            ruleList.animate().cancel(); ruleList.setAlpha(.7f); ruleList.animate().alpha(1).setDuration(160).start();
        }
    }

    private void renderRule(String key, int index, JSONObject rule) {
        if (rule == null) return;
        LinearLayout row = column(); row.setPadding(dp(16), dp(10), dp(4), dp(12));
        row.setBackground(ripple(R.color.surface, 12)); row.setOnClickListener(v -> editRule(key, index));
        row.setContentDescription("编辑规则：" + rule.optString("id"));
        LinearLayout line = new LinearLayout(this); line.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(rule.optString("id"), 16, R.color.ink, true);
        line.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Switch enabled = new Switch(this); enabled.setMinHeight(dp(48));
        enabled.setChecked(rule.optBoolean("enabled", true)); enabled.setContentDescription("启用规则：" + rule.optString("id"));
        enabled.setOnCheckedChangeListener((v, checked) -> {
            JSONObject candidate = copy(); put(array(candidate, key).optJSONObject(index), "enabled", checked); apply(candidate, false);
        });
        line.addView(enabled);
        TextView actions = text("⋮", 24, R.color.muted, false); actions.setGravity(Gravity.CENTER);
        actions.setMinWidth(dp(44)); actions.setMinHeight(dp(48)); actions.setContentDescription("规则操作：" + rule.optString("id"));
        actions.setOnClickListener(v -> {
            PopupMenu menu = new PopupMenu(this, actions);
            menu.getMenu().add("编辑").setOnMenuItemClickListener(item -> { editRule(key, index); return true; });
            menu.getMenu().add("删除").setOnMenuItemClickListener(item -> {
                String previous = visibleJson; JSONObject candidate = copy(); array(candidate, key).remove(index);
                apply(candidate, true); undoJson = previous;
                Toast.makeText(this, "已删除，可在更多中撤销", Toast.LENGTH_SHORT).show(); return true;
            }); menu.show();
        });
        line.addView(actions); row.addView(line);
        TextView summary = text(describe(rule), 14, R.color.muted, false);
        summary.setPadding(0, dp(2), dp(12), 0); summary.setLineSpacing(dp(3), 1); row.addView(summary);
        if (!rule.optBoolean("enabled", true)) title.setTextColor(color(R.color.muted));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.bottomMargin = dp(8);
        ruleList.addView(row, params);
    }

    private void editRule(String key, int index) { editRule(key, index, null); }
    private void editRule(String key, int index, Bundle restored) {
        JSONObject old = index < 0 ? new JSONObject() : array(settings, key).optJSONObject(index);
        LinearLayout form = column(); form.setPadding(dp(22), dp(4), dp(22), dp(8));
        EditText name = field(form, "名称", "可选，例如：屏蔽推广", old.optString("id"), false); name.setId(R.id.rule_name);
        EditText locations = field(form, "IP 属地", "每行一个，如：广东", join(old.optJSONArray("locations")), true);
        locations.setId(R.id.rule_locations);
        CheckBox unknown = check(form, "包含未知属地", old.optBoolean("unknownLocation"));
        EditText words = field(form, "评论关键词", "每行一个", join(old.optJSONArray("keywords")), true); words.setId(R.id.rule_keywords);
        margin(form, text("用户性别", 14, R.color.muted, true), 12, 0);
        LinearLayout genders = new LinearLayout(this); form.addView(genders);
        String selected = old.optJSONArray("genders") == null ? "" : old.optJSONArray("genders").toString();
        CheckBox male = check(genders, "男", selected.contains("\"M\""));
        CheckBox female = check(genders, "女", selected.contains("\"F\""));
        CheckBox unknownGender = check(genders, "未知", selected.contains("\"UNKNOWN\""));
        if (host == HostApp.XIAOHONGSHU) {
            margin(form, text("此版本评论未提供性别，统一按“未知”匹配。", 14, R.color.muted, false), 4, 0);
        }
        if (restored != null) {
            name.setText(restored.getString("name")); locations.setText(restored.getString("locations")); words.setText(restored.getString("words"));
            unknown.setChecked(restored.getBoolean("unknown")); male.setChecked(restored.getBoolean("male"));
            female.setChecked(restored.getBoolean("female")); unknownGender.setChecked(restored.getBoolean("unknownGender"));
        }
        margin(form, text("留空不限；填写的条件需同时满足。", 14, R.color.muted, false), 8, 0);
        TextView error = text("", 14, R.color.error, false); error.setVisibility(View.GONE); form.addView(error);
        ScrollView scroll = new ScrollView(this); scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(index < 0 ? "添加规则" : "编辑规则")
                .setView(scroll).setNegativeButton("取消", null).setPositiveButton("完成", null).create();
        editorSnapshot = () -> {
            Bundle saved = new Bundle(); saved.putString("key", key); saved.putInt("index", index);
            saved.putString("name", name.getText().toString()); saved.putString("locations", locations.getText().toString());
            saved.putString("words", words.getText().toString()); saved.putBoolean("unknown", unknown.isChecked());
            saved.putBoolean("male", male.isChecked()); saved.putBoolean("female", female.isChecked()); saved.putBoolean("unknownGender", unknownGender.isChecked());
            return saved;
        };
        activeEditor = dialog;
        dialog.setOnDismissListener(d -> { if (activeEditor == dialog) { editorSnapshot = null; activeEditor = null; } });
        dialog.setOnShowListener(d -> {
            dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                try {
                    JSONObject rule = new JSONObject(); String id = name.getText().toString().trim();
                    put(rule, "id", id.isEmpty() ? nextName() : id); put(rule, "enabled", old.optBoolean("enabled", true));
                    put(rule, "locations", lines(locations)); put(rule, "unknownLocation", unknown.isChecked()); put(rule, "keywords", lines(words));
                    JSONArray sex = new JSONArray();
                    if (male.isChecked()) sex.put("M"); if (female.isChecked()) sex.put("F"); if (unknownGender.isChecked()) sex.put("UNKNOWN");
                    put(rule, "genders", sex); JSONObject candidate = copy(); JSONArray entries = array(candidate, key);
                    if (index < 0) entries.put(rule); else entries.put(index, rule);
                    apply(candidate, true); dialog.dismiss();
                } catch (Exception e) {
                    error.setVisibility(View.VISIBLE); error.setText(e.getMessage());
                    scroll.post(() -> scroll.smoothScrollTo(0, form.getHeight()));
                }
            });
        }); dialog.show();
    }

    private void more(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        if (undoJson != null) menu.getMenu().add("撤销删除").setOnMenuItemClickListener(item -> {
            String previous = undoJson; apply(object(previous), true); return true;
        });
        menu.getMenu().add("关键词忽略大小写").setCheckable(true).setChecked(settings.optBoolean("ignoreKeywordCase", true))
                .setOnMenuItemClickListener(item -> { change("ignoreKeywordCase", !settings.optBoolean("ignoreKeywordCase", true)); return true; });
        menu.getMenu().add("高级配置").setOnMenuItemClickListener(item -> { editJson(); return true; });
        menu.getMenu().add("使用说明").setOnMenuItemClickListener(item -> {
            new AlertDialog.Builder(this).setTitle("使用说明")
                    .setMessage("每个应用分别保存开关、模式和规则。切换应用保留本次未保存的编辑，强制停止模块后恢复上次保存的配置。\n\n黑名单隐藏命中评论，白名单只显示命中评论。\n\n同一规则内的条件需同时满足；多条规则命中任意一条即可。\n\n仅过滤主评论。修改后点击保存规则，再刷新对应应用评论区。首次启用需在 LSPosed 勾选对应应用并重启该应用。\n\n属地、性别以评论接口返回为准，未提供的字段按未知处理。\n\n模块 " + BuildConfig.VERSION_NAME + " · " + host.displayName + " " + host.versionName)
                    .setPositiveButton("知道了", null).show(); return true;
        }); menu.show();
    }

    private void editJson() { editJson(null); }
    private void editJson(String restored) {
        LinearLayout form = column(); form.setPadding(dp(20), 0, dp(20), 0);
        EditText editor = field(form, "JSON", "", restored == null ? pretty(settings) : restored, true); editor.setTypeface(Typeface.MONOSPACE); editor.setMinLines(8);
        TextView error = text("", 14, R.color.error, false); form.addView(error);
        ScrollView scroll = new ScrollView(this); scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(host.displayName + " · 高级配置").setView(scroll)
                .setNegativeButton("取消", null).setPositiveButton("完成", null).create();
        editorSnapshot = () -> { Bundle saved = new Bundle(); saved.putString("type", "json"); saved.putString("json", editor.getText().toString()); return saved; };
        activeEditor = dialog;
        dialog.setOnDismissListener(d -> { if (activeEditor == dialog) { editorSnapshot = null; activeEditor = null; } });
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try { String json = editor.getText().toString(); RuleEngine.compileJson(json); apply(object(json), true); dialog.dismiss(); }
            catch (Exception e) { error.setText(e.getMessage()); }
        })); dialog.show();
    }

    private void change(String key, Object value) { JSONObject candidate = copy(); put(candidate, key, value); apply(candidate, false); }
    private void apply(JSONObject candidate, boolean animate) {
        String json = candidate.toString(); RuleEngine.compileJson(json); undoJson = null;
        visibleJson = json; settings = candidate; syncScreen(animate);
    }
    private void changed(AutoSaveQueue.State state) {
        updateSaveButton(state);
        if (saveRequested && !state.saving) {
            saveRequested = false;
            // After process recreation the queue may have been lost before its write completed.
            boolean saved = state.error == null && state.json.equals(requestedJson);
            requestedJson = null;
            Toast.makeText(this, saved ? host.displayName + "规则已保存" : host.displayName + "保存未完成，请重试", Toast.LENGTH_SHORT).show();
        }
    }
    private void updateSaveButton(AutoSaveQueue.State state) {
        saveButton.setEnabled(!state.saving);
        saveButton.setText(state.saving ? "保存中…" : "保存规则");
    }
    private String activeKey() { return "WHITELIST_ONLY".equals(settings.optString("mode")) ? "whitelist" : "blacklist"; }
    private JSONObject copy() { return object(visibleJson); }
    private String nextName() {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (String key : new String[]{"blacklist", "whitelist"}) {
            JSONArray entries = array(settings, key);
            for (int i = 0; i < entries.length(); i++) ids.add(entries.optJSONObject(i).optString("id"));
        }
        for (int n = 1; ; n++) if (!ids.contains("规则 " + n)) return "规则 " + n;
    }
    private void tab(Button tab, boolean selected) {
        tab.setSelected(selected); tab.setTextColor(color(selected ? R.color.accent : R.color.muted));
        tab.setTypeface(Typeface.create(selected ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        tab.setBackground(ripple(selected ? R.color.surface : R.color.well, 8));
    }
    private TextView text(String value, int size, int tint, boolean medium) {
        TextView text = new TextView(this); text.setText(value); text.setTextSize(size); text.setTextColor(color(tint));
        if (medium) text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); return text;
    }
    private Button button(String title, boolean primary) {
        Button button = new Button(this); button.setText(title); button.setTextSize(15); button.setAllCaps(false);
        button.setStateListAnimator(null); button.setElevation(0);
        button.setTextColor(color(primary ? R.color.on_accent : R.color.accent)); button.setMinHeight(dp(48));
        button.setBackground(ripple(primary ? R.color.accent : R.color.page, 10)); return button;
    }
    private GradientDrawable shape(int tint, int radius) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(color(tint)); shape.setCornerRadius(dp(radius)); return shape;
    }
    private RippleDrawable ripple(int tint, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x22176B54), shape(tint, radius), shape(R.color.surface, radius));
    }
    private void margin(LinearLayout parent, View view, int top, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(top); params.bottomMargin = dp(bottom); parent.addView(view, params);
    }
    private CheckBox check(LinearLayout parent, String title, boolean value) {
        CheckBox box = new CheckBox(this); box.setText(title); box.setTextSize(15); box.setMinHeight(dp(48));
        box.setChecked(value); parent.addView(box); return box;
    }
    private EditText field(LinearLayout parent, String label, String hint, String value, boolean multiline) {
        margin(parent, text(label, 14, R.color.muted, true), 12, 0);
        EditText input = new EditText(this); input.setHint(hint); input.setText(value); input.setTextSize(16);
        input.setSingleLine(!multiline); input.setMaxLines(multiline ? 4 : 1); input.setMinHeight(dp(48));
        int inputType = android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | (multiline ? android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0);
        input.setInputType(inputType);
        // Literal rules must not be changed by hardware-keyboard AutoText either.
        input.setKeyListener(android.text.method.TextKeyListener.getInstance(false, android.text.method.TextKeyListener.Capitalize.NONE));
        input.setRawInputType(inputType);
        parent.addView(input, new LinearLayout.LayoutParams(-1, -2)); return input;
    }
    private static JSONObject object(String json) {
        try { return new JSONObject(json); } catch (Exception e) { throw new IllegalArgumentException(e); }
    }
    private static String pretty(JSONObject object) { try { return object.toString(2); } catch (Exception e) { return object.toString(); } }
    private static JSONArray array(JSONObject object, String key) {
        JSONArray result = object.optJSONArray(key); if (result == null) { result = new JSONArray(); put(object, key, result); } return result;
    }
    private static void put(JSONObject object, String key, Object value) {
        try { object.put(key, value); } catch (Exception e) { throw new IllegalArgumentException(e); }
    }
    private static JSONArray lines(EditText input) {
        JSONArray result = new JSONArray();
        for (String value : input.getText().toString().split("\\r?\\n")) if (!value.trim().isEmpty()) result.put(value.trim()); return result;
    }
    private static String join(JSONArray array) {
        if (array == null) return ""; StringBuilder result = new StringBuilder();
        for (int i = 0; i < array.length(); i++) { if (i > 0) result.append('\n'); result.append(array.optString(i)); } return result.toString();
    }
    private static String describe(JSONObject rule) {
        java.util.ArrayList<String> descriptions = new java.util.ArrayList<>();
        String regions = join(rule.optJSONArray("locations")).replace('\n', '、');
        if (rule.optBoolean("unknownLocation")) regions += regions.isEmpty() ? "未知" : "、未知";
        if (!regions.isEmpty()) descriptions.add("属地 · " + regions);
        String words = join(rule.optJSONArray("keywords")).replace('\n', '、');
        if (!words.isEmpty()) descriptions.add("关键词 · " + words);
        JSONArray sex = rule.optJSONArray("genders"); java.util.ArrayList<String> genders = new java.util.ArrayList<>();
        if (sex != null) for (int i = 0; i < sex.length(); i++)
            genders.add("M".equals(sex.optString(i)) ? "男" : "F".equals(sex.optString(i)) ? "女" : "未知");
        if (!genders.isEmpty()) descriptions.add("性别 · " + TextUtils.join("、", genders));
        return TextUtils.join("\n", descriptions);
    }
}
