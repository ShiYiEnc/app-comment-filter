package io.github.appcommentfilter.hook;

import java.io.File;
import java.util.List;

/** Offline DEX fingerprint check. Compile with DexMethodIndex and KuaishouFooterBindings. */
public final class KuaishouFooterProbe {
    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 2) throw new IllegalArgumentException("APK exact-wrapper-descriptor");
        List<DexMethodIndex.Member> members = DexMethodIndex.read(new File(arguments[0]), arguments[1]);
        KuaishouFooterBindings.AddFooter result = KuaishouFooterBindings.addFooter(members, arguments[1],
                "Landroid/view/View;", "Landroid/view/ViewGroup$LayoutParams;");
        System.out.println("owner=" + arguments[1] + " add=" + result.method + " helper=" + result.helper
                + " group=" + result.group + " groupType=" + result.groupDescriptor);
    }
}
