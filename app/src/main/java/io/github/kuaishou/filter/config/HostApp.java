package io.github.kuaishou.filter.config;

/** Stable profile identity; independent of the module application's package name. */
public enum HostApp {
    KUAISHOU("com.smile.gifmaker", "快手", "14.9.20.50799"),
    BILIBILI("tv.danmaku.bili", "哔哩哔哩", "9.17.0"),
    BILIBILI_HD("tv.danmaku.bilibilihd", "哔哩哔哩 HD", "2.5.1"),
    XIAOHONGSHU("com.xingin.xhs", "小红书", "9.52.0"),
    DOUYIN("com.ss.android.ugc.aweme", "抖音", "40.9.0");

    public final String packageName, displayName, versionName;

    HostApp(String packageName, String displayName, String versionName) {
        this.packageName = packageName;
        this.displayName = displayName;
        this.versionName = versionName;
    }

    public static HostApp fromPackage(String packageName) {
        for (HostApp app : values()) if (app.packageName.equals(packageName)) return app;
        throw new IllegalArgumentException("Unsupported application: " + packageName);
    }

    public boolean allowsPackages(String[] packages) {
        if (packages != null) for (String candidate : packages) if (packageName.equals(candidate)) return true;
        return false;
    }
}
