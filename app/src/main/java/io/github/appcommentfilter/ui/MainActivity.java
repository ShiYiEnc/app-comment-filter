package io.github.appcommentfilter.ui;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.ContextThemeWrapper;
import android.widget.*;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;
import io.github.appcommentfilter.BuildConfig;
import io.github.appcommentfilter.R;
import io.github.appcommentfilter.config.SettingsRepository;
import io.github.appcommentfilter.config.HostApp;
import io.github.appcommentfilter.config.ConfigStore;
import io.github.appcommentfilter.config.HookHealth;
import io.github.appcommentfilter.config.RuntimeStatusStore;
import io.github.appcommentfilter.core.AutoSaveQueue;
import io.github.appcommentfilter.core.RuleEngine;

/** Edits stay in the current draft until the user explicitly saves them. */
public final class MainActivity extends Activity {
    private AutoSaveQueue repository;
    private HostApp host;
    private Bundle drafts = new Bundle(), undos = new Bundle(), saveRequests = new Bundle(), saveSnapshots = new Bundle();
    private boolean observing;
    private JSONObject settings;
    private String visibleJson;
    private LinearLayout ruleList, genderSettings;
    private TextView modeHint, ruleHeading, runtimeTitle, runtimeSummary;
    private final android.os.Handler statusHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable statusRefresh = new Runnable() {
        @Override public void run() { updateRuntimeStatus(); statusHandler.postDelayed(this, 3000); }
    };
    private Switch master, profileGender;
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

    @Override protected void onStart() {
        super.onStart(); observing = true; repository.observe(observer); statusHandler.post(statusRefresh);
    }
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
    @Override protected void onStop() {
        observing = false; repository.removeObserver(observer); statusHandler.removeCallbacks(statusRefresh); super.onStop();
    }
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
        LinearLayout options = dialogForm();
        AlertDialog dialog = dialogBuilder("选择应用").setView(dialogScroll(options))
                .setNegativeButton("取消", null).create();
        for (HostApp app : apps) {
            RadioButton option = new RadioButton(this); option.setText(app.displayName);
            option.setTextSize(16); option.setTextColor(color(R.color.ink));
            option.setButtonTintList(controlTint()); option.setChecked(app == host);
            option.setMinHeight(dp(54)); option.setPadding(dp(12), dp(6), dp(12), dp(6));
            option.setBackground(ripple(app == host ? R.color.well : R.color.surface, 10));
            option.setOnClickListener(v -> { if (app != host) switchApp(app); dialog.dismiss(); });
            margin(options, option, 3, 3);
        }
        dialog.setOnShowListener(d -> styleDialog(dialog)); dialog.show();
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

        LinearLayout runtime = column(); runtime.setId(R.id.runtime_status);
        runtime.setPadding(dp(16), dp(12), dp(16), dp(12)); runtime.setBackground(ripple(R.color.surface, 14));
        runtimeTitle = text("", 16, R.color.ink, true); runtimeTitle.setId(R.id.runtime_status_title);
        runtimeSummary = text("", 13, R.color.muted, false); runtimeSummary.setId(R.id.runtime_status_summary);
        runtimeSummary.setPadding(0, dp(4), 0, 0); runtimeSummary.setLineSpacing(dp(2), 1);
        runtime.addView(runtimeTitle); runtime.addView(runtimeSummary);
        runtime.setOnClickListener(v -> showRuntimeStatus());
        margin(body, runtime, 0, 12);

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

        genderSettings = column(); genderSettings.setId(R.id.xhs_gender_settings);
        genderSettings.setPadding(dp(16), dp(12), dp(12), dp(14));
        genderSettings.setBackground(shape(R.color.surface, 14));
        LinearLayout genderRow = new LinearLayout(this); genderRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView genderTitle = text("启用性别过滤", 17, R.color.ink, true);
        genderRow.addView(genderTitle, new LinearLayout.LayoutParams(0, -2, 1));
        profileGender = new Switch(this); profileGender.setId(R.id.xhs_gender_filter_enabled);
        profileGender.setSaveEnabled(false); profileGender.setMinHeight(dp(48));
        profileGender.setContentDescription("启用小红书性别过滤");
        profileGender.setOnCheckedChangeListener((view, checked) -> { if (!syncing) change("resolveProfileGender", checked); });
        genderRow.addView(profileGender); genderSettings.addView(genderRow);
        TextView genderNote = text("原生评论不提供性别，开启后需额外加载作者资料。加载期间先保留评论，失败或未填写按未知处理。", 14, R.color.muted, false);
        genderNote.setId(R.id.xhs_gender_filter_note); genderNote.setLineSpacing(dp(3), 1);
        genderNote.setPadding(0, dp(2), dp(4), 0); genderSettings.addView(genderNote);
        genderRow.setOnClickListener(v -> profileGender.setChecked(!profileGender.isChecked()));
        margin(body, genderSettings, 12, 0);

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
        updateRuntimeStatus();
        appSelector.setText(getString(R.string.app_selector_label, host.displayName));
        appSelector.setContentDescription("选择应用，当前：" + host.displayName);
        master.setContentDescription("启用" + host.displayName + "评论过滤");
        syncing = true;
        master.setChecked(settings.optBoolean("enabled", false));
        profileGender.setChecked(settings.optBoolean("resolveProfileGender", false));
        syncing = false;
        genderSettings.setVisibility(host == HostApp.XIAOHONGSHU ? View.VISIBLE : View.GONE);
        boolean black = activeKey().equals("blacklist");
        tab(blacklistTab, black); tab(whitelistTab, !black);
        JSONArray entries = array(settings, activeKey());
        boolean hasEnabled = false;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject rule = entries.optJSONObject(i);
            if (rule != null && rule.optBoolean("enabled", true) && hasConditions(rule, genderControlsAvailable())) hasEnabled = true;
        }
        modeHint.setText(!hasEnabled ? "无启用条件时显示全部评论" : black ? "隐藏命中的评论" : "只显示命中的评论");
        ruleHeading.setText(getString(R.string.rule_heading, black ? "黑名单" : "白名单", entries.length()));
        ruleList.removeAllViews();
        if (entries.length() == 0) {
            LinearLayout empty = column(); empty.setPadding(dp(4), dp(24), dp(4), dp(24));
            empty.addView(text("暂无规则", 18, R.color.ink, false));
            TextView hint = text(genderControlsAvailable() ? "添加属地、关键词或性别条件" : "添加属地或关键词条件", 14, R.color.muted, false);
            hint.setPadding(0, dp(8), 0, 0); empty.addView(hint); ruleList.addView(empty);
        }
        for (int i = 0; i < entries.length(); i++) renderRule(activeKey(), i, entries.optJSONObject(i));
        if (animate && ValueAnimator.areAnimatorsEnabled()) {
            ruleList.animate().cancel(); ruleList.setAlpha(.7f); ruleList.animate().alpha(1).setDuration(160).start();
        }
    }

    private android.content.pm.PackageInfo installedHost() {
        try { return getPackageManager().getPackageInfo(host.packageName, 0); }
        catch (android.content.pm.PackageManager.NameNotFoundException | RuntimeException error) { return null; }
    }
    private boolean currentStatus(HookHealth.Snapshot state, android.content.pm.PackageInfo info) {
        return state != null && info != null && BuildConfig.VERSION_NAME.equals(state.moduleVersion)
                && java.util.Objects.equals(info.versionName, state.versionName) && info.getLongVersionCode() == state.versionCode;
    }
    private String runtimeLabel(HookHealth.Snapshot state, android.content.pm.PackageInfo info) {
        if (info == null) return "未安装应用";
        if (state == null) return "尚无运行记录";
        if (!currentStatus(state, info)) return "更新后等待验证";
        if ("FAILED".equals(state.stage)) return "适配失败";
        if (!state.configError.isEmpty()) return "配置读取异常";
        if (state.hasIssue()) return "部分功能异常";
        return state.lastCommentAt > 0 ? "已读取评论" : state.ready() ? "已加载，等待评论" : "正在检查适配";
    }
    private String statusTime(long time) {
        if (time <= 0) return "暂无";
        return new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.CHINA).format(new java.util.Date(time));
    }
    private void updateRuntimeStatus() {
        if (runtimeTitle == null) return;
        HookHealth.Snapshot state = RuntimeStatusStore.load(this, host);
        android.content.pm.PackageInfo info = installedHost();
        runtimeTitle.setText(getString(R.string.runtime_status_label, runtimeLabel(state, info)));
        boolean issue = currentStatus(state, info) && state.hasIssue();
        runtimeTitle.setTextColor(color(issue ? R.color.error : R.color.ink));
        String summary;
        if (info == null) summary = "安装后，在 LSPosed 勾选此应用并重新启动。";
        else if (state == null) summary = "请启用模块、勾选作用域，重启应用并打开评论区。";
        else if (!currentStatus(state, info)) summary = "应用或模块已更新，请重启应用并打开评论区。";
        else if (issue) summary = "检测到异常，点击查看原因和处理建议。";
        else if (state.lastCommentAt > 0) summary = "最近读取 " + statusTime(state.lastCommentAt) + " · 点击查看诊断";
        else summary = "尚未成功读取主评论，不代表失效。点击查看诊断。";
        runtimeSummary.setText(summary);
    }
    private void showRuntimeStatus() {
        HookHealth.Snapshot state = RuntimeStatusStore.load(this, host);
        android.content.pm.PackageInfo info = installedHost();
        StringBuilder detail = new StringBuilder(host.displayName).append(" · ").append(runtimeLabel(state, info));
        detail.append("\n当前应用：").append(info == null ? "未安装" : info.versionName + " / " + info.getLongVersionCode());
        detail.append("\n静态适配基准：").append(host.versionName).append("（其他版本也尝试加载）");
        boolean enabled = object(ConfigStore.load(this, host)).optBoolean("enabled", false);
        detail.append("\n已保存过滤开关：").append(enabled ? "开启" : "关闭");
        if (state != null) {
            detail.append("\n\n以下为最近一次进程的运行记录，不表示应用当前仍在运行。");
            if (!currentStatus(state, info)) detail.append("\n此记录属于旧版本，请重启应用重新验证。");
            detail.append("\n记录应用：").append(state.versionName).append(" / ").append(state.versionCode)
                    .append("\n记录模块：").append(state.moduleVersion)
                    .append("\n进程开始：").append(statusTime(state.startedAt))
                    .append("\n最近报告：").append(statusTime(state.updatedAt))
                    .append("\n最近读取：").append(statusTime(state.lastCommentAt))
                    .append("\n主评论读取次数：").append(state.read)
                    .append("\n隐藏条目次数：").append(state.hidden).append("（计数均含重复处理）");
            appendIssue(detail, "适配安装失败", state.installError);
            appendIssue(detail, "评论读取或处理异常", state.runtimeError);
            appendIssue(detail, "规则读取失败，沿用最后有效配置", state.configError);
            appendIssue(detail, "小红书性别资料不可用或请求失败，可能与网络有关", state.profileError);
            appendIssue(detail, "快手自动补页异常", state.paginationError);
        }
        detail.append("\n\n验证方法：保存并开启过滤，重启对应应用，打开有主评论的评论区，再返回此处查看读取时间。规则未命中、过滤关闭或未打开评论区，都不能据此判定适配失效。")
                .append("\n\n若始终没有读取记录，请检查 LSPosed 作用域；更新可能改变评论调用路径，需要重新适配。检测无法保证识别所有结构含义变化。")
                .append("\n\n明确异常会尝试在对应应用中提示；同一应用版本、模块版本的同类异常只提醒一次，系统可能限制提示显示。");
        LinearLayout content = dialogForm();
        TextView body = text(detail.toString(), 14, R.color.muted, false); body.setLineSpacing(dp(4), 1); content.addView(body);
        AlertDialog dialog = dialogBuilder("运行状态").setView(dialogScroll(content)).setPositiveButton("知道了", null).create();
        dialog.setOnShowListener(d -> styleDialog(dialog)); dialog.show();
    }
    private void appendIssue(StringBuilder detail, String label, String error) {
        if (!error.isEmpty()) detail.append("\n\n").append(label).append("：").append(error);
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
        LinearLayout form = dialogForm();
        EditText name = field(form, "名称", "可选，例如：屏蔽推广", old.optString("id"), false); name.setId(R.id.rule_name);
        EditText locations = field(form, "IP 属地", "每行一个，如：广东", join(old.optJSONArray("locations")), true);
        locations.setId(R.id.rule_locations);
        CheckBox unknown = check(form, "包含未知属地", old.optBoolean("unknownLocation"));
        EditText words = field(form, "评论关键词", "每行一个", join(old.optJSONArray("keywords")), true); words.setId(R.id.rule_keywords);
        LinearLayout genderFields = column(); genderFields.setId(R.id.rule_gender_section);
        genderFields.addView(text("用户性别", 14, R.color.muted, true));
        LinearLayout genders = new LinearLayout(this); margin(genderFields, genders, 8, 0);
        String selected = old.optJSONArray("genders") == null ? "" : old.optJSONArray("genders").toString();
        CheckBox male = genderChoice(genders, "男", selected.contains("\"M\"")); male.setId(R.id.rule_gender_male);
        CheckBox female = genderChoice(genders, "女", selected.contains("\"F\"")); female.setId(R.id.rule_gender_female);
        CheckBox unknownGender = genderChoice(genders, "未知", selected.contains("\"UNKNOWN\"")); unknownGender.setId(R.id.rule_gender_unknown);
        if (genderControlsAvailable()) {
            margin(form, genderFields, 16, 0);
            if (host == HostApp.XIAOHONGSHU)
                margin(genderFields, text("按作者资料中的性别匹配；资料加载失败或未填写时按未知处理。", 14, R.color.muted, false), 8, 0);
        }
        if (restored != null) {
            name.setText(restored.getString("name")); locations.setText(restored.getString("locations")); words.setText(restored.getString("words"));
            unknown.setChecked(restored.getBoolean("unknown")); male.setChecked(restored.getBoolean("male"));
            female.setChecked(restored.getBoolean("female")); unknownGender.setChecked(restored.getBoolean("unknownGender"));
        }
        margin(form, text("留空不限；填写的条件需同时满足。", 14, R.color.muted, false), 8, 0);
        TextView error = text("", 14, R.color.error, false); error.setVisibility(View.GONE); form.addView(error);
        ScrollView scroll = dialogScroll(form);
        AlertDialog dialog = dialogBuilder(index < 0 ? "添加规则" : "编辑规则")
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
            styleDialog(dialog);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                try {
                    JSONObject rule = new JSONObject(); String id = name.getText().toString().trim();
                    put(rule, "id", id.isEmpty() ? nextName() : id); put(rule, "enabled", old.optBoolean("enabled", true));
                    put(rule, "locations", lines(locations)); put(rule, "unknownLocation", unknown.isChecked()); put(rule, "keywords", lines(words));
                    JSONArray sex = old.optJSONArray("genders");
                    if (genderControlsAvailable()) {
                        sex = new JSONArray();
                        if (male.isChecked()) sex.put("M"); if (female.isChecked()) sex.put("F"); if (unknownGender.isChecked()) sex.put("UNKNOWN");
                    }
                    if (sex == null) sex = new JSONArray();
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
            LinearLayout help = dialogForm();
            TextView body = text("每个应用分别保存开关、模式和规则。切换应用保留本次未保存的编辑，强制停止模块后恢复上次保存的配置。\n\n黑名单隐藏命中评论，白名单只显示命中评论。同一规则内的条件需同时满足；多条规则命中任意一条即可。\n\n仅过滤主评论。修改后点击保存规则，再刷新对应应用评论区。首次启用需在 LSPosed 勾选对应应用并重启该应用。\n\n小红书原生评论不提供性别；开启性别过滤后，需要额外加载作者资料。加载期间先保留评论，失败或未填写按未知处理。关闭后，已有性别条件暂停，其余条件继续生效。\n\n模块 " + BuildConfig.VERSION_NAME + " · " + host.displayName + " (适配基准 " + host.versionName + ")", 15, R.color.muted, false);
            body.setLineSpacing(dp(4), 1); help.addView(body);
            AlertDialog dialog = dialogBuilder("使用说明").setView(dialogScroll(help)).setPositiveButton("知道了", null).create();
            dialog.setOnShowListener(d -> styleDialog(dialog)); dialog.show(); return true;
        }); menu.show();
    }

    private void editJson() { editJson(null); }
    private void editJson(String restored) {
        LinearLayout form = dialogForm();
        EditText editor = field(form, "JSON", "", restored == null ? pretty(settings) : restored, true); editor.setTypeface(Typeface.MONOSPACE); editor.setMinLines(8);
        TextView error = text("", 14, R.color.error, false); form.addView(error);
        ScrollView scroll = dialogScroll(form);
        AlertDialog dialog = dialogBuilder("高级配置").setView(scroll)
                .setNegativeButton("取消", null).setPositiveButton("完成", null).create();
        editorSnapshot = () -> { Bundle saved = new Bundle(); saved.putString("type", "json"); saved.putString("json", editor.getText().toString()); return saved; };
        activeEditor = dialog;
        dialog.setOnDismissListener(d -> { if (activeEditor == dialog) { editorSnapshot = null; activeEditor = null; } });
        dialog.setOnShowListener(d -> { styleDialog(dialog); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try { String json = editor.getText().toString(); RuleEngine.compileJson(json); apply(object(json), true); dialog.dismiss(); }
            catch (Exception e) { error.setText(e.getMessage()); }
        }); }); dialog.show();
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
            Toast.makeText(this, saved ? host.displayName + "规则已保存，请刷新评论区" : host.displayName + "保存未完成，请重试", Toast.LENGTH_SHORT).show();
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
        box.setTextColor(color(R.color.ink)); box.setButtonTintList(controlTint());
        box.setChecked(value); parent.addView(box); return box;
    }
    private EditText field(LinearLayout parent, String label, String hint, String value, boolean multiline) {
        margin(parent, text(label, 14, R.color.muted, true), 12, 0);
        EditText input = new EditText(this); input.setHint(hint); input.setText(value); input.setTextSize(16);
        input.setSingleLine(!multiline); input.setMaxLines(multiline ? 4 : 1); input.setMinHeight(dp(48));
        input.setTextColor(color(R.color.ink)); input.setHintTextColor(color(R.color.muted));
        input.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable idle = shape(R.color.page, 10); idle.setStroke(dp(1), color(R.color.input_border));
        GradientDrawable focused = shape(R.color.page, 10); focused.setStroke(dp(2), color(R.color.accent));
        StateListDrawable fieldBackground = new StateListDrawable();
        fieldBackground.addState(new int[]{android.R.attr.state_focused}, focused); fieldBackground.addState(new int[]{}, idle);
        input.setBackground(fieldBackground);
        int inputType = android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | (multiline ? android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0);
        input.setInputType(inputType);
        // Literal rules must not be changed by hardware-keyboard AutoText either.
        input.setKeyListener(android.text.method.TextKeyListener.getInstance(false, android.text.method.TextKeyListener.Capitalize.NONE));
        input.setRawInputType(inputType);
        margin(parent, input, 6, 0); return input;
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
    private boolean genderControlsAvailable() {
        return host != HostApp.XIAOHONGSHU || settings.optBoolean("resolveProfileGender", false);
    }

    private static boolean hasConditions(JSONObject rule, boolean includeGender) {
        return rule.optBoolean("unknownLocation") || nonEmpty(rule.optJSONArray("locations"))
                || nonEmpty(rule.optJSONArray("keywords")) || (includeGender && nonEmpty(rule.optJSONArray("genders")));
    }

    private static boolean nonEmpty(JSONArray values) { return values != null && values.length() > 0; }

    private String describe(JSONObject rule) {
        java.util.ArrayList<String> descriptions = new java.util.ArrayList<>();
        String regions = join(rule.optJSONArray("locations")).replace('\n', '、');
        if (rule.optBoolean("unknownLocation")) regions += regions.isEmpty() ? "未知" : "、未知";
        if (!regions.isEmpty()) descriptions.add("属地 · " + regions);
        String words = join(rule.optJSONArray("keywords")).replace('\n', '、');
        if (!words.isEmpty()) descriptions.add("关键词 · " + words);
        JSONArray sex = rule.optJSONArray("genders"); java.util.ArrayList<String> genders = new java.util.ArrayList<>();
        if (sex != null) for (int i = 0; i < sex.length(); i++)
            genders.add("M".equals(sex.optString(i)) ? "男" : "F".equals(sex.optString(i)) ? "女" : "未知");
        if (!genders.isEmpty()) {
            if (genderControlsAvailable()) descriptions.add("性别 · " + TextUtils.join("、", genders));
            else descriptions.add(descriptions.isEmpty() ? "规则已暂停 · 性别过滤未开启" : "性别条件已暂停");
        }
        return TextUtils.join("\n", descriptions);
    }

    private LinearLayout dialogForm() {
        LinearLayout form = column(); form.setPadding(dp(24), dp(2), dp(24), dp(12)); return form;
    }

    private AlertDialog.Builder dialogBuilder(String title) {
        LinearLayout heading = column(); heading.setPadding(dp(24), dp(22), dp(24), dp(12));
        TextView context = text("选择应用".equals(title) ? "各应用独立配置" : host.displayName, 13, R.color.muted, true);
        heading.addView(context);
        TextView name = text(title, 23, R.color.ink, true); name.setPadding(0, dp(4), 0, 0);
        name.setAccessibilityHeading(true); heading.addView(name);
        return new AlertDialog.Builder(new ContextThemeWrapper(this, R.style.FilterDialogTheme)).setCustomTitle(heading);
    }

    private ScrollView dialogScroll(View content) {
        ScrollView scroll = new ScrollView(this) {
            @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int max = Math.round(getResources().getDisplayMetrics().heightPixels * .62f);
                int mode = MeasureSpec.getMode(heightMeasureSpec), size = MeasureSpec.getSize(heightMeasureSpec);
                if (mode != MeasureSpec.UNSPECIFIED) max = Math.min(max, size);
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
            }
        };
        scroll.setClipToPadding(false); scroll.setFillViewport(false); scroll.addView(content);
        return scroll;
    }

    private void styleDialog(AlertDialog dialog) {
        android.view.Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawable(shape(R.color.surface, 22));
        window.getDecorView().setPadding(0, 0, 0, 0);
        int width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(560));
        window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
        window.setDimAmount(.34f);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        ViewGroup actionRow = null;
        for (int which : new int[]{AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_POSITIVE}) {
            Button action = dialog.getButton(which);
            if (action == null) continue;
            if (actionRow == null && action.getParent() instanceof ViewGroup row) {
                actionRow = row;
                // Native alert button rows can reserve only the platform button height.
                // Put vertical spacing on the row instead of consuming a child's bounds.
                row.setPadding(row.getPaddingLeft(), dp(8), row.getPaddingRight(), dp(12));
                row.setMinimumHeight(dp(68));
                ViewGroup.LayoutParams rowParams = row.getLayoutParams();
                if (rowParams != null) { rowParams.height = ViewGroup.LayoutParams.WRAP_CONTENT; row.setLayoutParams(rowParams); }
            }
            boolean primary = which == AlertDialog.BUTTON_POSITIVE;
            action.setAllCaps(false); action.setTextSize(15); action.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            action.setMinHeight(dp(48)); action.setMinimumHeight(dp(48)); action.setMinWidth(dp(72));
            action.setPadding(dp(16), dp(9), dp(16), dp(9)); action.setStateListAnimator(null);
            action.setTextColor(color(primary ? R.color.on_accent : R.color.accent));
            action.setBackground(ripple(primary ? R.color.accent : R.color.well, 10));
            ViewGroup.LayoutParams current = action.getLayoutParams();
            if (current != null) current.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            if (current instanceof ViewGroup.MarginLayoutParams margins) {
                margins.leftMargin = dp(6); margins.rightMargin = dp(6);
                margins.topMargin = 0; margins.bottomMargin = 0;
            }
            if (current != null) action.setLayoutParams(current);
        }
        if (ValueAnimator.areAnimatorsEnabled()) {
            View panel = window.getDecorView(); panel.setAlpha(.88f); panel.setTranslationY(dp(6));
            panel.animate().alpha(1).translationY(0).setDuration(180).start();
        }
    }

    private ColorStateList controlTint() {
        return new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{color(R.color.accent), color(R.color.muted)});
    }

    private CheckBox genderChoice(LinearLayout parent, String label, boolean checked) {
        CheckBox choice = new CheckBox(this); choice.setText(label); choice.setChecked(checked);
        choice.setTextSize(15); choice.setTextColor(controlTint()); choice.setGravity(Gravity.CENTER);
        choice.setButtonDrawable(null); choice.setMinWidth(0); choice.setMinimumWidth(0);
        choice.setMinHeight(dp(46)); choice.setPadding(dp(4), dp(8), dp(4), dp(8));
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_checked}, shape(R.color.save_background, 10));
        background.addState(new int[]{}, shape(R.color.page, 10));
        choice.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22176B54), background, shape(R.color.surface, 10)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        params.setMarginEnd(dp(6)); parent.addView(choice, params); return choice;
    }
}
