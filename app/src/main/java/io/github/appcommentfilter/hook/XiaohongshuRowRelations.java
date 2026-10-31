package io.github.appcommentfilter.hook;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Explicit ownership bindings for auxiliary rows; adjacency is never ownership evidence. */
final class XiaohongshuRowRelations {
    private final Class<?> replyControl, collapsedComment, downvotedComment;
    private final Field controlOwner;
    private final Method collapsedValue, downvotedValue;

    XiaohongshuRowRelations(Class<?> commentRow, Class<?> replyControl,
                            Class<?> collapsedComment, Class<?> downvotedComment) throws ReflectiveOperationException {
        this.replyControl = replyControl;
        this.collapsedComment = collapsedComment;
        this.downvotedComment = downvotedComment;
        controlOwner = replyControl.getDeclaredField("commentId");
        if (controlOwner.getType() != String.class) throw new NoSuchFieldException("Unexpected reply-control owner");
        controlOwner.setAccessible(true);
        collapsedValue = collapsedComment.getMethod("a");
        downvotedValue = downvotedComment.getMethod("b");
        if (collapsedValue.getReturnType() != commentRow || downvotedValue.getReturnType() != commentRow) {
            throw new NoSuchMethodException("Unexpected wrapped comment schema");
        }
    }

    Object comment(Object row) throws ReflectiveOperationException {
        if (collapsedComment.isInstance(row)) return collapsedValue.invoke(row);
        if (downvotedComment.isInstance(row)) return downvotedValue.invoke(row);
        return row;
    }

    String replyControlOwner(Object row) throws ReflectiveOperationException {
        return replyControl.isInstance(row) ? (String) controlOwner.get(row) : null;
    }
}
