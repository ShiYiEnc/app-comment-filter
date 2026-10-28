package io.github.kuaishou.filter.hook;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/** Preserve the server response and every paging field when passing a filtered view to observers. */
final class DouyinResponseCopy {
    private final Class<?> responseClass;
    private final Constructor<?> constructor;
    private final List<Field> fields = new ArrayList<>();
    private final Field items;

    DouyinResponseCopy(Class<?> responseClass) throws ReflectiveOperationException {
        this.responseClass = responseClass;
        constructor = responseClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        items = responseClass.getField("items");
        if (!List.class.isAssignableFrom(items.getType())) throw new NoSuchFieldException("items List");
        for (Class<?> type = responseClass; type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                fields.add(field);
            }
        }
    }

    List<?> items(Object response) throws IllegalAccessException {
        return responseClass.isInstance(response) ? (List<?>) items.get(response) : null;
    }

    Object withItems(Object response, List<?> replacement) throws ReflectiveOperationException {
        if (response == null || response.getClass() != responseClass) return response;
        Object copy = constructor.newInstance();
        for (Field field : fields) field.set(copy, field.get(response));
        items.set(copy, replacement);
        return copy;
    }
}
