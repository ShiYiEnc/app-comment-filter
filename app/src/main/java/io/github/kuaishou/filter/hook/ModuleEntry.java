package io.github.kuaishou.filter.hook;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import io.github.kuaishou.filter.config.RemoteConfig;
import io.github.kuaishou.filter.config.HostApp;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ModuleEntry implements IXposedHookLoadPackage {
    private final AtomicBoolean installed = new AtomicBoolean();

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        HostApp host;
        try { host = HostApp.fromPackage(param.packageName); }
        catch (IllegalArgumentException ignored) { return; }
        if (!host.packageName.equals(param.processName)) return;
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam attach) {
                if (!installed.compareAndSet(false, true)) return;
                Context context = (Context) attach.args[0];
                try {
                    PackageInfo info = context.getPackageManager().getPackageInfo(host.packageName, 0);
                    if (!host.versionName.equals(info.versionName) || versionCode(host) != info.getLongVersionCode()) {
                        XposedBridge.log("[CommentFilter] Unsupported " + host.packageName + " version "
                                + info.versionName + "/" + info.getLongVersionCode() + "; filtering disabled");
                        return;
                    }
                    ClassLoader loader = context.getClassLoader();
                    RemoteConfig config = new RemoteConfig(context, host);
                    switch (host) {
                        case KUAISHOU -> new Kuaishou14920Adapter().install(loader, config, (Application) attach.thisObject);
                        case BILIBILI -> new BilibiliAdapter(false).install(loader, config);
                        case BILIBILI_HD -> new BilibiliAdapter(true).install(loader, config);
                        case XIAOHONGSHU -> new XiaohongshuAdapter().install(loader, config);
                        case DOUYIN -> new Douyin4090Adapter().install(loader, config);
                    }
                } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                    XposedBridge.log("[CommentFilter] " + host.packageName + " adapter unavailable; filtering disabled (" + e.getClass().getSimpleName() + ")");
                } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                    XposedBridge.log("[CommentFilter] Host package identity unavailable; filtering disabled");
                }
            }
        });
    }

    private static long versionCode(HostApp app) {
        return switch (app) {
            case KUAISHOU -> Kuaishou14920Adapter.VERSION_CODE;
            case BILIBILI -> BilibiliAdapter.VERSION_CODE;
            case BILIBILI_HD -> BilibiliAdapter.HD_VERSION_CODE;
            case XIAOHONGSHU -> XiaohongshuAdapter.VERSION_CODE;
            case DOUYIN -> Douyin4090Adapter.VERSION_CODE;
        };
    }
}
