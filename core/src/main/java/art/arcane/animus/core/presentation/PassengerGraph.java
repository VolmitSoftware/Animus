package art.arcane.animus.core.presentation;

import java.util.*;

/** Small viewer-local mount graph. Caller serializes access with the session lock. */
public final class PassengerGraph {
    private final Map<Integer, Integer> parent = new HashMap<>();
    private final Map<Integer, List<Integer>> children = new HashMap<>();
    public void replace(int vehicle, List<Integer> passengers) {
        if (passengers.size() > 16 || new HashSet<>(passengers).size() != passengers.size())
            throw new IllegalArgumentException("Passenger list must contain at most 16 distinct bodies");
        for (int passenger : passengers) {
            Integer existingParent = parent.get(passenger);
            if (existingParent != null && existingParent != vehicle) throw new IllegalArgumentException("Passenger must be detached from its existing vehicle first");
            for (Integer ancestor = vehicle; ancestor != null; ancestor = parent.get(ancestor))
                if (ancestor == passenger) throw new IllegalArgumentException("Passenger relationship would form a cycle");
        }
        for (int child : children.getOrDefault(vehicle, List.of())) parent.remove(child, vehicle);
        if (passengers.isEmpty()) children.remove(vehicle);
        else children.put(vehicle, List.copyOf(passengers));
        for (int passenger : passengers) parent.put(passenger, vehicle);
    }
    public void remove(int entity) {
        for (int child : children.getOrDefault(entity, List.of())) parent.remove(child, entity);
        children.remove(entity);
        Integer vehicle = parent.remove(entity);
        if (vehicle != null) {
            List<Integer> remaining = children.getOrDefault(vehicle, List.of()).stream().filter(child -> child != entity).toList();
            if (remaining.isEmpty()) children.remove(vehicle); else children.put(vehicle, remaining);
        }
    }
    public void clear() { parent.clear(); children.clear(); }
    public List<Integer> passengers(int vehicle) { return children.getOrDefault(vehicle, List.of()); }
}
