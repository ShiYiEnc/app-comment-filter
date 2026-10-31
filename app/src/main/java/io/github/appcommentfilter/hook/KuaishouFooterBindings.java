package io.github.appcommentfilter.hook;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Execution-free footer selection; header and footer groups must never be interchanged. */
final class KuaishouFooterBindings {
    private KuaishouFooterBindings() { }
    static final class AddFooter {
        final String method, helper, group, groupDescriptor;
        AddFooter(String method, String helper, DexMethodIndex.FieldRef group) {
            this.method = method; this.helper = helper; this.group = group.name; this.groupDescriptor = group.descriptor;
        }
    }

    static Class<?> wrapper(Class<?> recyclerFragment, Class<?> adapter, Class<?> view) throws ReflectiveOperationException {
        Set<Class<?>> candidates = new LinkedHashSet<>();
        for (Field field : recyclerFragment.getDeclaredFields()) {
            Class<?> type = field.getType();
            if (Modifier.isStatic(field.getModifiers()) || type == adapter || !adapter.isAssignableFrom(type)) continue;
            try {
                Field footerAdapter = type.getDeclaredField("h");
                Method contains = type.getMethod("l2", view), remove = type.getMethod("E2", view);
                if (!Modifier.isStatic(footerAdapter.getModifiers()) && adapter.isAssignableFrom(footerAdapter.getType())
                        && !Modifier.isStatic(contains.getModifiers()) && !Modifier.isStatic(remove.getModifiers())
                        && contains.getReturnType() == boolean.class && remove.getReturnType() == boolean.class) candidates.add(type);
            } catch (NoSuchFieldException | NoSuchMethodException ignored) { /* Not the verified wrapper shape. */ }
        }
        if (candidates.size() != 1) throw new NoSuchFieldException("Expected unique footer wrapper, found " + candidates.size());
        return candidates.iterator().next();
    }

    static Method noArgReturn(Class<?> owner, Class<?> result, String label) throws NoSuchMethodException {
        Method match = null;
        for (Method method : owner.getMethods()) {
            if (Modifier.isStatic(method.getModifiers()) || method.isBridge() || method.getParameterCount() != 0
                    || method.getReturnType() != result) continue;
            if (match != null && !match.equals(method)) throw new NoSuchMethodException("Ambiguous " + label);
            match = method;
        }
        if (match == null) throw new NoSuchMethodException("Missing " + label);
        return match;
    }

    static AddFooter addFooter(List<DexMethodIndex.Member> members, String owner,
                               String view, String layoutParams) throws NoSuchMethodException {
        String unary = "(" + view + ")V", predicate = "(" + view + ")Z";
        DexMethodIndex.Member contains = exact(members, owner, "l2", predicate);
        DexMethodIndex.Member remove = exact(members, owner, "E2", predicate);
        List<DexMethodIndex.FieldRef> groups = new ArrayList<>();
        for (DexMethodIndex.FieldRef field : contains.fields) if (instanceObjectRead(field, owner)) {
            for (DexMethodIndex.FieldRef other : remove.fields) {
                if (instanceObjectRead(other, owner) && sameField(field, other)) { groups.add(field); break; }
            }
        }
        if (groups.size() != 1) throw new NoSuchMethodException("Ambiguous footer group fingerprint");
        DexMethodIndex.FieldRef group = groups.get(0);
        String helperDescriptor = "(" + view + layoutParams + ")V";
        AddFooter result = null;
        for (DexMethodIndex.Member method : members) {
            if (!method.owner.equals(owner) || !method.descriptor.equals(unary) || (method.flags & 8) != 0 || method.hasDynamicCalls) continue;
            Set<String> matchingHelpers = new LinkedHashSet<>();
            for (DexMethodIndex.Call call : method.calls) {
                if (!call.owner.equals(owner) || !call.descriptor.equals(helperDescriptor)) continue;
                DexMethodIndex.Member helper = exact(members, owner, call.name, call.descriptor);
                if (helper.hasDynamicCalls) continue;
                for (DexMethodIndex.FieldRef read : helper.fields) {
                    if (instanceObjectRead(read, owner) && sameField(group, read)) matchingHelpers.add(helper.name);
                }
            }
            if (matchingHelpers.size() != 1) continue;
            if (result != null) throw new NoSuchMethodException("Ambiguous footer add fingerprint");
            result = new AddFooter(method.name, matchingHelpers.iterator().next(), group);
        }
        if (result == null) throw new NoSuchMethodException("Missing footer add fingerprint");
        return result;
    }
    private static boolean instanceObjectRead(DexMethodIndex.FieldRef field, String owner) {
        return field.owner.equals(owner) && field.opcode == 0x54 && field.isRead() && field.descriptor.startsWith("L");
    }
    private static boolean sameField(DexMethodIndex.FieldRef a, DexMethodIndex.FieldRef b) {
        return a.owner.equals(b.owner) && a.name.equals(b.name) && a.descriptor.equals(b.descriptor);
    }
    private static DexMethodIndex.Member exact(List<DexMethodIndex.Member> members, String owner, String name, String descriptor)
            throws NoSuchMethodException {
        DexMethodIndex.Member result = null;
        for (DexMethodIndex.Member member : members) {
            if (!member.owner.equals(owner) || !member.name.equals(name) || !member.descriptor.equals(descriptor) || (member.flags & 8) != 0) continue;
            if (result != null) throw new NoSuchMethodException("Duplicate footer fingerprint method");
            result = member;
        }
        if (result == null) throw new NoSuchMethodException("Missing footer fingerprint method");
        return result;
    }
    static String descriptor(Class<?> type) { return "L" + type.getName().replace('.', '/') + ";"; }
}
