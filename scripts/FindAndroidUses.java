import jadx.api.*;
import java.io.File;
import java.util.Collections;

/** Offline selected-DEX incoming-reference helper; output is ordinary JADX analysis. */
public final class FindAndroidUses {
    public static void main(String[] args) throws Exception {
        JadxArgs config = new JadxArgs();
        config.setInputFiles(Collections.singletonList(new File(args[0])));
        config.setSkipResources(true);
        config.setThreadsCount(1);
        try (JadxDecompiler jadx = new JadxDecompiler(config)) {
            jadx.load();
            JavaClass cls = jadx.searchJavaClassByOrigFullName(args[1]);
            if (cls == null) throw new IllegalArgumentException(args[1]);
            cls.getCode();
            for (JavaNode node : cls.getUseIn()) System.out.println("CLASS " + node.getFullName());
            if (args.length > 2) for (JavaMethod method : cls.getMethods()) {
                if (method.getName().equals(args[2])) {
                    for (JavaNode node : method.getUseIn()) System.out.println("METHOD " + node.getFullName());
                }
            }
        }
    }
}
