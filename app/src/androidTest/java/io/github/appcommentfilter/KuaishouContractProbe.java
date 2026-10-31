package io.github.appcommentfilter;

import android.content.Context;
import android.view.View;
import dalvik.system.PathClassLoader;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Resolves real APK declarations with initialization disabled. Never invokes host methods. */
public final class KuaishouContractProbe {
    private KuaishouContractProbe() { }
    public static String inspect(Context context, String path) throws ReflectiveOperationException {
        File apk = new File(path);
        String codeCache = context.getCodeCacheDir().getAbsolutePath() + File.separator;
        if (!apk.getAbsolutePath().startsWith(codeCache) || !apk.isFile()) throw new IllegalArgumentException("Select a code-cache fixture");
        ClassLoader module = context.getClassLoader();
        // Android may verify/link the supplied DEX, but no Application/Activity, static
        // initializer or reflected host method is executed by this probe.
        ClassLoader host = new PathClassLoader(apk.getAbsolutePath(), module);
        Class<?> type = Class.forName("io.github.appcommentfilter.hook.KuaishouBindings", false, module);
        Constructor<?> constructor = type.getDeclaredConstructor(ClassLoader.class, File[].class); constructor.setAccessible(true);
        Object binding = constructor.newInstance(host, new File[]{apk});
        Method pagingMethod = type.getDeclaredMethod("paging"); pagingMethod.setAccessible(true);
        Object paging = pagingMethod.invoke(binding);
        StringBuilder result = new StringBuilder("PASS declaration binding; parser=").append(field(binding, "parser"))
                .append("; response=").append(((Class<?>) field(binding, "responseApi")).getName())
                .append("; comment=").append(((Class<?>) field(binding, "commentApi")).getName());
        for (String role : new String[]{"coreField", "first", "loading", "latest", "completion", "failure", "cancel", "load", "locked"})
            result.append("; ").append(role).append('=').append(field(paging, role));
        Class<?> base = Class.forName("com.yxcorp.gifshow.recycler.fragment.RecyclerFragment", false, host);
        Class<?> comments = Class.forName("com.yxcorp.gifshow.comment.common.fragment.AbsCommentsFragment", false, host);
        Class<?> adapter = Class.forName("androidx.recyclerview.widget.RecyclerView$Adapter", false, host);
        Class<?> footerBinding = Class.forName("io.github.appcommentfilter.hook.KuaishouFooterBindings", false, module);
        Method wrapperMethod = footerBinding.getDeclaredMethod("wrapper", Class.class, Class.class, Class.class); wrapperMethod.setAccessible(true);
        Class<?> wrapper = (Class<?>) wrapperMethod.invoke(null, base, adapter, View.class);
        Method noArg = footerBinding.getDeclaredMethod("noArgReturn", Class.class, Class.class, String.class); noArg.setAccessible(true);
        Class<?> params = Class.forName("com.yxcorp.gifshow.comment.common.model.CommentParams", false, host);
        Method getParams = (Method) noArg.invoke(null, comments, params, "comment params");
        Method latest = (Method) noArg.invoke(null, base.getMethod("n").getReturnType(), Object.class, "latest response");
        Method list = base.getMethod("j0");
        list.getReturnType().getMethod("getAdapter"); list.getReturnType().getMethod("isComputingLayout");
        if (params.getField("mQPhoto").getType().getMethod("getKId").getReturnType() != String.class) throw new NoSuchMethodException("Photo id type");
        Class<?> page = (Class<?>) field(binding, "pageList");
        if (page.getDeclaredField("b").getType().getMethod("getKId").getReturnType() != String.class) throw new NoSuchMethodException("Owner photo id type");
        for (String name : new String[]{"onPause", "onPageUnSelect", "onDestroyView", "onResume", "onPageSelect"}) base.getDeclaredMethod(name);
        return result.append("; footerWrapper=").append(wrapper.getName()).append("; params=").append(getParams)
                .append("; nativePagerLatest=").append(latest).append("; initialization=false; hostMethodsInvoked=false").toString();
    }
    private static Object field(Object object, String name) throws ReflectiveOperationException {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
}
