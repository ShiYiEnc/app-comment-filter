package io.github.appcommentfilter.hook;

import io.github.appcommentfilter.core.Comment;
import io.github.appcommentfilter.core.Gender;
import io.github.appcommentfilter.core.RuleEngine;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/** Exact 40.9.0 field layout. Unrecognized rows and replies are retained. */
final class DouyinCommentReader {
    private final Class<?> commentClass;
    private final Field text, location, user, commentUser, gender, replyId, level, commentType;
    private final FilterDiagnostics diagnostics;

    DouyinCommentReader(Class<?> commentClass, Class<?> userClass) throws ReflectiveOperationException {
        this(commentClass, userClass, FilterDiagnostics.NONE);
    }
    DouyinCommentReader(Class<?> commentClass, Class<?> userClass, FilterDiagnostics diagnostics) throws ReflectiveOperationException {
        this.diagnostics = diagnostics;
        this.commentClass = commentClass;
        text = field(commentClass, "text", String.class);
        location = field(commentClass, "ipLabel", String.class);
        user = field(commentClass, "user", userClass);
        commentUser = field(commentClass, "commentUser", userClass);
        gender = field(userClass, "gender", int.class);
        replyId = field(commentClass, "replyId", String.class);
        level = field(commentClass, "level", Integer.class);
        commentType = field(commentClass, "commentType", int.class);
    }

    Comment read(Object item) throws ReflectiveOperationException {
        if (!commentClass.isInstance(item)) return null;
        String parent = (String) replyId.get(item);
        Integer depth = (Integer) level.get(item);
        int type = commentType.getInt(item);
        if ((parent != null && !parent.isEmpty() && !"0".equals(parent))
                || (depth != null && depth >= 2) || (type != 0 && type != 1)) return null;
        // The host's LLLLZLL() uses commentUser first, then user.
        Object author = commentUser.get(item);
        if (author == null) author = user.get(item);
        int code = author == null ? 0 : gender.getInt(author);
        Gender sex = code == 1 ? Gender.M : code == 2 ? Gender.F : Gender.UNKNOWN;
        return new Comment((String) text.get(item), (String) location.get(item), sex, false);
    }

    List<?> filter(List<?> original, RuleEngine rules) {
        if (!rules.enabled()) return original;
        List<Object> result = null;
        int read = 0, removed = 0; Throwable failure = null;
        for (int i = 0; i < original.size(); i++) {
            Object item = original.get(i);
            boolean hide = false;
            try {
                Comment comment = read(item);
                hide = comment != null && rules.evaluate(comment).hidden();
                if (comment != null) read++;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                failure = error;
                // A broken object is not equivalent to an unknown server attribute.
            }
            if (hide && result == null) result = new ArrayList<>(original.subList(0, i));
            if (!hide && result != null) result.add(item);
            if (hide) removed++;
        }
        diagnostics.onBatch(read, removed, failure);
        return result == null ? original : result;
    }

    private static Field field(Class<?> owner, String name, Class<?> type) throws NoSuchFieldException {
        Field field = owner.getField(name);
        if (field.getType() != type) throw new NoSuchFieldException(owner.getName() + "." + name);
        return field;
    }
}
