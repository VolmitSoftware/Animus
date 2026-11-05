package com.google.common.collect;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/** Test stand-in with the serialized shape of Guava's ImmutableList. */
public final class ImmutableList<E> extends ArrayList<E> {
    private static final long serialVersionUID = 1L;

    public ImmutableList(List<E> values) { super(values); }

    private Object writeReplace() { return new SerializedForm(toArray()); }

    static final class SerializedForm implements Serializable {
        private static final long serialVersionUID = 0L;
        private final Object[] elements;

        SerializedForm(Object[] elements) { this.elements = elements; }
    }
}
