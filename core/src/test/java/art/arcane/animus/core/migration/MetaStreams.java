package art.arcane.animus.core.migration;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.util.io.Wrapper;

/** Builds item meta the way Citizens stores it: a base64 Java object stream of a Bukkit-wrapped Guava map. */
final class MetaStreams {
    private MetaStreams() {}

    /** A ConfigurationSerializable value: {@code ==} alias first, then its fields. */
    static Wrapper<ImmutableMap<String, Object>> serializable(String alias, Map<String, Object> fields) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("==", alias);
        values.putAll(fields);
        return new Wrapper<>(new ImmutableMap<>(values));
    }

    static ImmutableMap<String, Object> map(Map<String, Object> values) { return new ImmutableMap<>(new LinkedHashMap<>(values)); }

    static ImmutableList<Object> list(List<Object> values) { return new ImmutableList<>(values); }

    static String base64(Serializable value) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
            out.flush();
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (IOException impossible) { throw new UncheckedIOException(impossible); }
    }

    static String itemMeta(Map<String, Object> fields) { return base64(serializable("ItemMeta", fields)); }
}
