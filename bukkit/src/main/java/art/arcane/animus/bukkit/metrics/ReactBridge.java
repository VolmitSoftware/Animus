package art.arcane.animus.bukkit.metrics;

import art.arcane.animus.core.metrics.MetricsSnapshot;
import art.arcane.animus.core.metrics.ReactMetricCatalog;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Animus as a React metric source, reached only by reflection through React's class loader. Animus has no compile or
 * runtime dependency on React: the source is a {@link Proxy} of React's own {@code ReactMetricSource} interface,
 * registered under that interface, and values go through React's static {@code ReactMetrics.publish}.
 */
public final class ReactBridge implements AutoCloseable {
    static final String API = "art.arcane.react.api.metric.";

    /** Registers the provider under React's interface class; Bukkit's ServicesManager on a server. */
    public interface Registrar {
        void register(Class<?> service, Object provider);
        void unregister(Object provider);
    }

    private final Object source;
    private final Method accepting, publish;
    private final Registrar registrar;
    private volatile boolean closed;

    private ReactBridge(ClassLoader react, Registrar registrar) throws ReflectiveOperationException {
        this.registrar = Objects.requireNonNull(registrar);
        Class<?> sourceType = Class.forName(API + "ReactMetricSource", true, react);
        Class<?> metricType = Class.forName(API + "ReactMetric", true, react);
        @SuppressWarnings({"unchecked", "rawtypes"}) Class<? extends Enum> kindType = (Class<? extends Enum>) Class.forName(API + "ReactMetricKind", true, react).asSubclass(Enum.class);
        Class<?> metrics = Class.forName(API + "ReactMetrics", true, react);
        accepting = metrics.getMethod("accepting", String.class);
        publish = metrics.getMethod("publish", String.class, String.class, double.class);
        List<Object> declared = declarations(metricType, kindType);
        InvocationHandler handler = (Object proxy, Method method, Object[] arguments) -> switch (method.getName()) {
            case "sourceId" -> ReactMetricCatalog.SOURCE_ID;
            case "metrics" -> declared;
            case "equals" -> arguments != null && arguments.length == 1 && proxy == arguments[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "Animus metric source";
            default -> throw new UnsupportedOperationException(method.getName());
        };
        source = Proxy.newProxyInstance(sourceType.getClassLoader(), new Class<?>[]{sourceType}, handler);
        registrar.register(sourceType, source);
    }

    /** Binds to React's API, loaded by {@code react}, and registers Animus as a source. */
    public static ReactBridge bind(ClassLoader react, Registrar registrar) throws ReflectiveOperationException {
        return new ReactBridge(react, registrar);
    }

    /** React's {@code ReactMetric} records for {@link ReactMetricCatalog#SPECS}, built through the canonical constructor. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<Object> declarations(Class<?> metricType, Class<? extends Enum> kindType) throws ReflectiveOperationException {
        RecordComponent[] components = metricType.getRecordComponents();
        if (components == null || components.length != 6) throw new NoSuchMethodException("ReactMetric is not the expected record");
        Class<?>[] types = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) types[i] = components[i].getType();
        Constructor<?> constructor = metricType.getConstructor(types);
        Class<?> iconType = types[4];
        List<Object> declared = new ArrayList<>(ReactMetricCatalog.SPECS.size());
        for (ReactMetricCatalog.Spec spec : ReactMetricCatalog.SPECS) {
            Object kind = Enum.valueOf(kindType, spec.kind());
            declared.add(constructor.newInstance(spec.key(), kind, spec.unit(), spec.displayName(), icon(iconType, spec.key()), spec.decimals()));
        }
        return List.copyOf(declared);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object icon(Class<?> iconType, String key) {
        if (!iconType.isEnum()) return null;
        String name = key.startsWith("animus.npcs.") ? "ARMOR_STAND"
                : key.startsWith("animus.net.") ? "REPEATER"
                : key.startsWith("animus.cost.") ? "CLOCK"
                : key.startsWith("animus.pressure.") ? "COMPASS"
                : "CHEST";
        try { return Enum.valueOf((Class<? extends Enum>) iconType, name); }
        catch (IllegalArgumentException missing) { return null; }
    }

    /** Publishes every value once React has declared the source; a no-op before then and after close. */
    public void publish(MetricsSnapshot snapshot) {
        if (closed) return;
        try {
            if (!Boolean.TRUE.equals(accepting.invoke(null, ReactMetricCatalog.SOURCE_ID))) return;
            for (Map.Entry<String, Double> value : ReactMetricCatalog.values(snapshot).entrySet())
                publish.invoke(null, ReactMetricCatalog.SOURCE_ID, value.getKey(), value.getValue());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            // React went away or changed its API; stop publishing rather than fail every roll.
            close();
        }
    }

    public boolean closed() { return closed; }

    @Override public void close() {
        if (closed) return;
        closed = true;
        try { registrar.unregister(source); }
        catch (RuntimeException | LinkageError ignored) { /* React is already gone. */ }
    }
}
