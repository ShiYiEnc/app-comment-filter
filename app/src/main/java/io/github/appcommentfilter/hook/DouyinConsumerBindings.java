package io.github.appcommentfilter.hook;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Locate the response-to-UI boundary by types, without binding an X.* or generated lambda name. */
final class DouyinConsumerBindings {
    private DouyinConsumerBindings() { }

    static final class KmpConsumer {
        final Method method;
        final int responseArgument = 1;
        KmpConsumer(Method method) { this.method = method; }
    }

    /** KMP has its own data source, separate from CommentListPageNetRequest consumers. */
    static KmpConsumer kmp(Class<?> viewModel, Class<?> response) throws NoSuchMethodException {
        Set<Class<?>> sources = new LinkedHashSet<>();
        for (Method getter : viewModel.getDeclaredMethods()) {
            if (Modifier.isStatic(getter.getModifiers()) || getter.isBridge() || getter.getParameterCount() != 0) continue;
            Class<?> type = getter.getReturnType();
            if (type.isPrimitive() || type.isArray() || type.getName().startsWith("java.")
                    || type.getName().startsWith("android.")) continue;
            sources.add(type);
        }
        Method result = null;
        for (Class<?> source : sources) for (Method method : source.getDeclaredMethods()) {
            int modifiers = method.getModifiers();
            if (Modifier.isStatic(modifiers) || Modifier.isAbstract(modifiers) || method.isBridge()
                    || method.getReturnType() != void.class) continue;
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length != 2 || !parameters[0].isEnum() || parameters[1] != response) continue;
            if (result != null && !result.equals(method)) throw new NoSuchMethodException("Ambiguous KMP main response consumer");
            result = method;
        }
        if (result == null) throw new NoSuchMethodException("No KMP main response consumer");
        return new KmpConsumer(result);
    }

    static List<Method> modern(Class<?> helper, Class<?> request, Class<?> response) throws NoSuchMethodException {
        Set<Class<?>> candidates = new LinkedHashSet<>();
        for (Method method : helper.getDeclaredMethods()) {
            if (method.getParameterCount() == 0 && !Modifier.isStatic(method.getModifiers())) candidates.add(method.getReturnType());
        }
        for (Field field : helper.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) candidates.add(field.getType());
        }
        List<Method> result = null;
        for (Class<?> type : candidates) {
            if (type.isPrimitive() || type.isArray() || type == Object.class) continue;
            boolean ownsRequest = false;
            for (Field field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && field.getType() == request) { ownsRequest = true; break; }
            }
            if (!ownsRequest) continue;
            List<Method> methods = consumers(type, response, true);
            if (methods.isEmpty()) continue;
            if (result != null) throw new NoSuchMethodException("Ambiguous main comment response consumer");
            result = methods;
        }
        if (result == null) throw new NoSuchMethodException("No main comment response consumer");
        return result;
    }

    static List<Method> legacy(Class<?> model, Class<?> response) throws NoSuchMethodException {
        List<Method> methods = consumers(model, response, false);
        if (methods.isEmpty()) throw new NoSuchMethodException("No legacy main comment consumer");
        return methods;
    }

    private static List<Method> consumers(Class<?> type, Class<?> response, boolean flag) {
        List<Method> result = new ArrayList<>();
        for (Method method : type.getDeclaredMethods()) {
            int modifiers = method.getModifiers();
            if (Modifier.isStatic(modifiers) || Modifier.isAbstract(modifiers) || method.isBridge()
                    || method.getReturnType() != void.class) continue;
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length != (flag ? 2 : 1) || parameters[0] != response
                    || (flag && parameters[1] != boolean.class)) continue;
            result.add(method);
        }
        return result;
    }
}
