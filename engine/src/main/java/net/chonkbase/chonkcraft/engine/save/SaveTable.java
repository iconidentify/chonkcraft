package net.chonkbase.chonkcraft.engine.save;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The array and named-field parts of one table in the constrained save format. */
final class SaveTable {
    private final List<Object> array = new ArrayList<>();
    private final Map<Object, Object> fields = new LinkedHashMap<>();

    List<Object> array() {
        return array;
    }

    Object rawGet(Object key) {
        if (key instanceof Number number) {
            int index = number.intValue();
            return index > 0 && index <= array.size() ? array.get(index - 1) : null;
        }
        return fields.get(key);
    }

    void rawSet(Object key, Object value) {
        fields.put(key, value);
    }

    void add(Object value) {
        array.add(value);
    }
}
