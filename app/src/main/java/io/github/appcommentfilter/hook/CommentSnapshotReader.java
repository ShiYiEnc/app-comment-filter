package io.github.appcommentfilter.hook;

import java.lang.reflect.Method;
import io.github.appcommentfilter.core.Comment;
import io.github.appcommentfilter.core.Gender;

/** One version adapter; symbols must all resolve before hooks are installed. */
public final class CommentSnapshotReader {
    private final Class<?> commentApi;
    private final Method type, reply, text, location, user, sex;

    public CommentSnapshotReader(Class<?> commentApi) throws ReflectiveOperationException {
        this.commentApi = commentApi;
        type = commentApi.getMethod("getKType");
        reply = commentApi.getMethod("getKIsSub");
        text = commentApi.getMethod("getKComment");
        location = commentApi.getMethod("getKAuthorArea");
        user = commentApi.getMethod("getKUser");
        sex = user.getReturnType().getMethod("getKUserSex");
    }

    /** null means this item is outside the main-comment scope. */
    public Comment read(Object item) throws ReflectiveOperationException {
        if (!isMainComment(item)) return null;
        Object author = user.invoke(item);
        Object value = author == null ? null : sex.invoke(author);
        Gender gender = "M".equals(value) ? Gender.M : "F".equals(value) ? Gender.F : Gender.UNKNOWN;
        return new Comment((String) text.invoke(item), (String) location.invoke(item), gender, false);
    }

    public boolean isMainComment(Object item) throws ReflectiveOperationException {
        return commentApi.isInstance(item) && ((Number) type.invoke(item)).intValue() == 1
                && !Boolean.TRUE.equals(reply.invoke(item));
    }
}
