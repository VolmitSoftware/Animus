package art.arcane.animus.bukkit;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/** Bukkit's dispatch without a server: the listeners' handlers for the event's type, by priority, honouring ignoreCancelled. */
public final class EventChain {
    private record Handler(Listener listener, Method method, EventHandler settings) {}

    private EventChain() {}

    public static <E extends Event> E fire(E event, Listener... listeners) {
        List<Handler> handlers = new ArrayList<>();
        for (Listener listener : listeners) {
            for (Method method : listener.getClass().getDeclaredMethods()) {
                EventHandler settings = method.getAnnotation(EventHandler.class);
                if (settings == null || method.getParameterCount() != 1 || !method.getParameterTypes()[0].isAssignableFrom(event.getClass())) continue;
                method.setAccessible(true);
                handlers.add(new Handler(listener, method, settings));
            }
        }
        handlers.sort(Comparator.comparingInt((Handler handler) -> handler.settings().priority().getSlot()));
        for (Handler handler : handlers) {
            if (handler.settings().ignoreCancelled() && event instanceof Cancellable cancellable && cancellable.isCancelled()) continue;
            try { handler.method().invoke(handler.listener(), event); }
            catch (IllegalAccessException failure) { throw new AssertionError(failure); }
            catch (InvocationTargetException failure) {
                if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
                if (failure.getCause() instanceof Error error) throw error;
                throw new AssertionError(failure.getCause());
            }
        }
        return event;
    }
}
