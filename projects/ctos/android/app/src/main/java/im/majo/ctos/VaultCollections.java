package im.majo.ctos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable snapshots using APIs available at the project's Android 28 minimum. */
final class VaultCollections {
    private VaultCollections() { }
    @SafeVarargs static <T> List<T> list(T... values) { return copyList(Arrays.asList(values)); }
    static <T> List<T> copyList(Collection<T> values) {
        ArrayList<T> result = new ArrayList<>(values);
        for (T value : result) Objects.requireNonNull(value);
        return Collections.unmodifiableList(result);
    }
    @SafeVarargs static <T> Set<T> set(T... values) {
        return Collections.unmodifiableSet(new HashSet<>(list(values)));
    }
    static Map<String, Object> map(String key, Object value) {
        return Collections.singletonMap(key, Objects.requireNonNull(value));
    }
}
