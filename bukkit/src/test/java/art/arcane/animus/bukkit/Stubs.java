package art.arcane.animus.bukkit;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Interface stubs for Bukkit types: the answer for each method by name, and zero, false or null for the rest. */
public final class Stubs {
    @FunctionalInterface public interface Answer { Object answer(String method, Object[] arguments); }

    private Stubs() {}

    public static <T> T stub(Class<T> type, Answer answer) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (Object proxy, Method method, Object[] arguments) -> {
            Object value = switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                case "toString" -> type.getSimpleName();
                default -> answer.answer(method.getName(), arguments == null ? new Object[0] : arguments);
            };
            return value != null || !method.getReturnType().isPrimitive() ? value : Array.get(Array.newInstance(method.getReturnType(), 1), 0);
        }));
    }
}
