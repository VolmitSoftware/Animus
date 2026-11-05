package com.google.common.collect;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/** Test stand-in with the serialized shape of Guava's ImmutableMap. */
public final class ImmutableMap<K, V> extends LinkedHashMap<K, V> {
    private static final long serialVersionUID = 1L;

    public ImmutableMap(Map<K, V> values) { super(values); }

    private Object writeReplace() { return new SerializedForm(keySet().toArray(), values().toArray()); }

    static final class SerializedForm implements Serializable {
        private static final long serialVersionUID = 0L;
        private final Object keys;
        private final Object values;

        SerializedForm(Object[] keys, Object[] values) { this.keys = keys; this.values = values; }
    }
}
