import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

/** Optional local JADX helper. Its output is ordinary static analysis, not REA Evidence. */
public final class Decompile {
    public static void main(String[] args) throws Exception {
        JadxArgs config = new JadxArgs();
        config.setInputFiles(Collections.singletonList(new File(args[0])));
        config.setSkipResources(true);
        config.setDisabledPlugins(Collections.singleton("java-input"));
        config.setThreadsCount(2);
        try (JadxDecompiler jadx = new JadxDecompiler(config)) {
            jadx.load();
            for (int i = 2; i < args.length; i++) {
                JavaClass cls = jadx.searchJavaClassByOrigFullName(args[i]);
                if (cls == null) throw new IllegalArgumentException("Missing class: " + args[i]);
                Path output = Path.of(args[1], args[i] + ".java.txt");
                Files.writeString(output, cls.getCode());
                System.out.println(output);
            }
        }
    }
}
