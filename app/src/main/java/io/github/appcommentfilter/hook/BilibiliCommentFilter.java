package io.github.appcommentfilter.hook;

import io.github.appcommentfilter.core.Comment;
import io.github.appcommentfilter.core.Gender;
import io.github.appcommentfilter.core.RuleEngine;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The same verified reply schema is shipped in both supported Bilibili APKs. */
public final class BilibiliCommentFilter {
    private final Class<?> type;
    private final Method root, parent, content, message, member, sex, control, location;
    private final FilterDiagnostics diagnostics;

    public BilibiliCommentFilter(Class<?> type) throws ReflectiveOperationException {
        this(type, FilterDiagnostics.NONE);
    }
    public BilibiliCommentFilter(Class<?> type, FilterDiagnostics diagnostics) throws ReflectiveOperationException {
        this.diagnostics = diagnostics;
        this.type = type;
        root = type.getMethod("getRoot"); parent = type.getMethod("getParent");
        content = type.getMethod("getContent");
        message = content.getReturnType().getMethod("getMessage");
        member = type.getMethod("getMember"); sex = member.getReturnType().getMethod("getSex");
        control = type.getMethod("getReplyControl");
        location = control.getReturnType().getMethod("getLocation");
        if (root.getReturnType() != long.class || parent.getReturnType() != long.class
                || message.getReturnType() != String.class || sex.getReturnType() != String.class
                || location.getReturnType() != String.class) throw new NoSuchMethodException("Reply schema changed");
    }

    public List<?> filter(List<?> original, RuleEngine rules) {
        if (!rules.enabled()) return original;
        List<Object> result = null;
        int read = 0, removed = 0; Throwable failure = null;
        for (int i = 0; i < original.size(); i++) {
            Object item = original.get(i);
            boolean hide = false;
            try {
                if (type.isInstance(item) && ((Number) root.invoke(item)).longValue() == 0
                        && ((Number) parent.invoke(item)).longValue() == 0) {
                    Object body = content.invoke(item);
                    if (body != null) {
                        Object author = member.invoke(item), attr = control.invoke(item);
                        String gender = author == null ? null : (String) sex.invoke(author);
                        Gender value = "男".equals(gender) || "M".equals(gender) ? Gender.M
                                : "女".equals(gender) || "F".equals(gender) ? Gender.F : Gender.UNKNOWN;
                        hide = rules.evaluate(new Comment((String) message.invoke(body),
                                attr == null ? null : (String) location.invoke(attr), value, false)).hidden();
                        read++;
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                failure = error;
                // A malformed item is retained even under a whitelist.
            }
            if (hide && result == null) result = new ArrayList<>(original.subList(0, i));
            if (!hide && result != null) result.add(item);
            if (hide) removed++;
        }
        diagnostics.onBatch(read, removed, failure);
        return result == null ? original : Collections.unmodifiableList(result);
    }
}
