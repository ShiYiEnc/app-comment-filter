package io.github.appcommentfilter.hook;

import android.app.Application;
import android.content.Context;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import io.github.appcommentfilter.config.RemoteConfig;
import io.github.appcommentfilter.config.HostApp;
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
                HookStatus status = new HookStatus(context, host);
                try {
                    ClassLoader loader = context.getClassLoader();
                    RemoteConfig config = new RemoteConfig(context, host, status);
                    switch (host) {
                        case KUAISHOU -> new Kuaishou14920Adapter().install(loader, config, (Application) attach.thisObject, status);
                        case BILIBILI -> new BilibiliAdapter(false).install(loader, config, status);
                        case BILIBILI_HD -> new BilibiliAdapter(true).install(loader, config, status);
                        case XIAOHONGSHU -> new XiaohongshuAdapter().install(loader, config, status);
                        case DOUYIN -> new Douyin4090Adapter().install(loader, config, status);
                    }
                    status.installed();
                } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                    status.installFailed(e);
                    XposedBridge.log("[CommentFilter] " + host.packageName + " adapter unavailable; filtering disabled (" + e.getClass().getSimpleName() + ")");
                }
            }
        });
    }

}
