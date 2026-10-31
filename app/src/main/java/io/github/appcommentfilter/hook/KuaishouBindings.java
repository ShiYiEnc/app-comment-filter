package io.github.appcommentfilter.hook;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Resolves only the stable named KMP anchor and verified readable model contracts. */
final class KuaishouBindings {
    final Class<?> pageList, responseApi, commentApi;
    final Method parser, localComments, fullList, cursor;
    final List<DexMethodIndex.Member> pageMembers;
    private final ClassLoader loader;
    final File[] apks;

    KuaishouBindings(ClassLoader loader, File[] apks) throws ReflectiveOperationException, IOException {
        this.loader = loader; this.apks = apks;
        pageList = Class.forName(binaryName(KuaishouSharedSymbols.PAGE), false, loader);
        pageMembers = index(apks, KuaishouSharedSymbols.PAGE);
        DexMethodIndex.Member symbol = KuaishouSharedSymbols.parser(pageMembers);
        responseApi = Class.forName(binaryName(KuaishouSharedSymbols.unaryObjectParameter(symbol.descriptor)), false, loader);
        localComments = responseApi.getMethod("getKLocalComments");
        fullList = responseApi.getMethod("getKLocalFullList"); cursor = responseApi.getMethod("getKPcursor");
        if (!List.class.isAssignableFrom(localComments.getReturnType()) || !List.class.isAssignableFrom(fullList.getReturnType())
                || cursor.getReturnType() != String.class) throw new NoSuchMethodException("Comment response contract changed");
        parser = pageList.getDeclaredMethod(symbol.name, responseApi);
        if (parser.getReturnType() != void.class || Modifier.isStatic(parser.getModifiers())) throw new NoSuchMethodException("Comment parser signature changed");
        parser.setAccessible(true);
        commentApi = commentType(pageList, responseApi);
    }

    Paging paging() throws ReflectiveOperationException, IOException { return new Paging(); }
    final class Paging {
        final Class<?> base = Class.forName(binaryName(KuaishouSharedSymbols.BASE), false, loader);
        final Class<?> core = Class.forName(binaryName(KuaishouSharedSymbols.CORE), false, loader);
        final KuaishouSharedSymbols.Profile symbols = KuaishouSharedSymbols.resolve(pageMembers, index(apks, KuaishouSharedSymbols.BASE));
        final Class<?> completionType = Class.forName(binaryName(KuaishouSharedSymbols.unaryObjectParameter(symbols.completion.descriptor)), false, loader);
        final Field coreField = onlyField(pageList, f -> !Modifier.isStatic(f.getModifiers()) && f.getType() == core, "comment pager");
        final Method first = base.getDeclaredMethod(symbols.first.name), hasMore = base.getMethod("hasMore"), nativeLoad = base.getDeclaredMethod("load");
        final Method loading = base.getDeclaredMethod(symbols.loading.name), latest = base.getDeclaredMethod(symbols.latest.name);
        final Method completion = base.getDeclaredMethod(symbols.completion.name, completionType);
        final Method failure = base.getDeclaredMethod(symbols.failure.name, Throwable.class), cancel = base.getDeclaredMethod(symbols.cancel.name);
        final Method load = pageList.getDeclaredMethod(symbols.ownerLoad.name), locked = pageList.getDeclaredMethod(symbols.locked.name);
        Paging() throws ReflectiveOperationException, IOException {
            if (first.getReturnType() != boolean.class || hasMore.getReturnType() != boolean.class
                    || loading.getReturnType() != boolean.class || latest.getReturnType() != Object.class
                    || nativeLoad.getReturnType() != void.class || load.getReturnType() != void.class || locked.getReturnType() != boolean.class)
                throw new NoSuchMethodException("Pager contract changed");
        }
    }

    static Class<?> commentType(Class<?> page, Class<?> response) throws ReflectiveOperationException {
        Set<Class<?>> candidates = new LinkedHashSet<>();
        // Generic signatures are useful when retained, but the model's readable getters
        // are verified again. Missing generic metadata falls back to typed page fields.
        try {
            Type type = response.getMethod("getKRootComments").getGenericReturnType();
            if (type instanceof ParameterizedType) for (Type item : ((ParameterizedType) type).getActualTypeArguments())
                if (item instanceof Class<?> && readable((Class<?>) item)) candidates.add((Class<?>) item);
        } catch (NoSuchMethodException | TypeNotPresentException | java.lang.reflect.MalformedParameterizedTypeException | LinkageError ignored) { }
        if (candidates.isEmpty()) for (Field field : page.getDeclaredFields())
            if (!Modifier.isStatic(field.getModifiers()) && readable(field.getType())) candidates.add(field.getType());
        if (candidates.size() != 1) throw new NoSuchMethodException("Expected unique readable comment model, found " + candidates.size());
        return candidates.iterator().next();
    }
    private static boolean readable(Class<?> type) {
        if (type.isPrimitive() || type.getName().startsWith("java.")) return false;
        try { new CommentSnapshotReader(type); return true; }
        catch (ReflectiveOperationException | LinkageError ignored) { return false; }
    }
    static Field onlyField(Class<?> type, java.util.function.Predicate<Field> accept, String label) throws NoSuchFieldException {
        Field result = null;
        for (Field field : type.getDeclaredFields()) if (accept.test(field)) {
            if (result != null) throw new NoSuchFieldException("Ambiguous " + label);
            result = field;
        }
        if (result == null) throw new NoSuchFieldException("Missing " + label);
        result.setAccessible(true); return result;
    }
    static List<DexMethodIndex.Member> index(File[] apks, String owner) throws IOException {
        for (File apk : apks) {
            List<DexMethodIndex.Member> members = DexMethodIndex.read(apk, owner);
            if (!members.isEmpty()) return members;
        }
        throw new IOException("Missing DEX definition: " + owner);
    }
    private static String binaryName(String descriptor) { return descriptor.substring(1, descriptor.length() - 1).replace('/', '.'); }
}
