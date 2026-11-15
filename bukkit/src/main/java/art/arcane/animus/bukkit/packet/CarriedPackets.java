package art.arcane.animus.bukkit.packet;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntFunction;

/**
 * Keeps viewer-local passengers on real server entities. The server sends an entity's whole passenger list whenever it
 * changes and again when a viewer starts tracking the entity, inside the bundle that spawns it; a client replaces the
 * roster each time, so every such list this viewer receives gets the carried IDs added after the server's own.
 */
final class CarriedPackets {
    /** Builds a passenger packet for a vehicle and its whole roster. */
    @FunctionalInterface interface Passengers { Object packet(int vehicle, int[] passengers) throws ReflectiveOperationException; }

    private final Passengers passengers;
    private final Class<?> setPassengers, bundle, addEntity;
    private final Method vehicle, riders, subPackets, spawned;
    private final Constructor<?> bundled;

    CarriedPackets(Passengers passengers) throws ReflectiveOperationException {
        this.passengers = passengers;
        setPassengers = Class.forName("net.minecraft.network.protocol.game.ClientboundSetPassengersPacket");
        vehicle = setPassengers.getMethod("getVehicle"); riders = setPassengers.getMethod("getPassengers");
        bundle = Class.forName("net.minecraft.network.protocol.game.ClientboundBundlePacket");
        subPackets = Class.forName("net.minecraft.network.protocol.BundlePacket").getMethod("subPackets");
        bundled = bundle.getConstructor(Iterable.class);
        addEntity = Class.forName("net.minecraft.network.protocol.game.ClientboundAddEntityPacket");
        spawned = addEntity.getMethod("getId");
    }

    /** The message to send instead: itself when it names no carrying entity. Runs on the viewer's channel. */
    Object rewrite(Object message, IntFunction<int[]> carried) throws ReflectiveOperationException {
        if (setPassengers.isInstance(message)) return rewritten(message, carried);
        if (!bundle.isInstance(message)) return message;
        List<Object> parts = new ArrayList<>();
        Set<Integer> spawnedHere = new LinkedHashSet<>(), listed = new LinkedHashSet<>();
        boolean changed = false;
        for (Object part : (Iterable<?>) subPackets.invoke(message)) {
            Object next = part;
            if (setPassengers.isInstance(part)) {
                listed.add(((Number) vehicle.invoke(part)).intValue());
                next = rewritten(part, carried);
                changed |= next != part;
            } else if (addEntity.isInstance(part)) {
                int id = ((Number) spawned.invoke(part)).intValue();
                if (carried.apply(id) != null) spawnedHere.add(id);
            }
            parts.add(next);
        }
        // An entity the viewer starts tracking with no passengers of its own spawns with no list at all.
        for (int id : spawnedHere) {
            if (listed.contains(id)) continue;
            int[] extras = carried.apply(id);
            if (extras != null) { parts.add(passengers.packet(id, extras)); changed = true; }
        }
        return changed ? bundled.newInstance(parts) : message;
    }

    private Object rewritten(Object packet, IntFunction<int[]> carried) throws ReflectiveOperationException {
        int id = ((Number) vehicle.invoke(packet)).intValue();
        int[] extras = carried.apply(id);
        if (extras == null) return packet;
        int[] own = (int[]) riders.invoke(packet);
        int[] all = union(own, extras);
        return all.length == own.length ? packet : passengers.packet(id, all);
    }

    /** {@code own} in order, then every extra it does not already hold. */
    static int[] union(int[] own, int[] extras) {
        Set<Integer> ids = new LinkedHashSet<>();
        for (int id : own) ids.add(id);
        for (int id : extras) ids.add(id);
        return ids.stream().mapToInt(Integer::intValue).toArray();
    }
}
