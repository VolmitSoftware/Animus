package art.arcane.animus.core.mount;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;

/**
 * Turns persisted relations into an acyclic, bounded forest. Definitions are never changed: a relation
 * that cannot be honoured is left unattached, preserved in its file and reported with its exact field.
 */
public final class MountRelations {
    public static final int MAX_DEPTH = 8, MAX_PASSENGERS = 16;
    public enum Code {
        UNRESOLVED_IMPORT(false), UNKNOWN_VEHICLE(false), DORMANT_VEHICLE(false), IDENTITY_CHANGED(false),
        SELF(true), CYCLE(true), DEPTH(true), CAPACITY(true);
        private final boolean blocks;
        Code(boolean blocks) { this.blocks = blocks; }
        /** A structural problem an editor can fix now. Missing or dormant vehicles may legitimately resolve later. */
        public boolean blocksActivation() { return blocks; }
    }
    public record Node(String id, UUID uuid, MountReference reference) {
        public Node { Objects.requireNonNull(id, "id"); Objects.requireNonNull(uuid, "uuid"); }
    }
    public record Diagnostic(String id, String field, Code code, String message) {
        @Override public String toString() { return id + " " + field + ": " + message; }
    }
    /** {@code order} lists passengers with every vehicle before anything it carries. */
    public record Resolution(Map<String, String> vehicleOf, Map<String, List<String>> passengersOf, List<String> order, List<Diagnostic> diagnostics) {
        public static final Resolution EMPTY = new Resolution(Map.of(), Map.of(), List.of(), List.of());
    }
    private MountRelations() {}

    /** @param known every definition's UUID by id, including dormant ones; null when the id does not exist */
    public static Resolution resolve(Collection<Node> active, Function<String, UUID> known) {
        Map<String, Node> nodes = new TreeMap<>();
        for (Node node : active) nodes.put(node.id(), node);
        List<Diagnostic> diagnostics = new ArrayList<>();
        Map<String, String> edges = new TreeMap<>();
        for (Node node : nodes.values()) {
            MountReference reference = node.reference();
            if (reference == null) continue;
            if (reference.status() != MountReference.Status.DECLARED) {
                report(diagnostics, node, Code.UNRESOLVED_IMPORT, "imported mount reference " + reference.original() + " is " + reference.status().name().toLowerCase(java.util.Locale.ROOT));
                continue;
            }
            String vehicle = reference.vehicleId();
            if (vehicle.equals(node.id())) { report(diagnostics, node, Code.SELF, "an NPC cannot ride itself"); continue; }
            UUID existing = known.apply(vehicle);
            if (existing == null) { report(diagnostics, node, Code.UNKNOWN_VEHICLE, "vehicle " + vehicle + " does not exist"); continue; }
            if (reference.vehicleUuid() != null && !reference.vehicleUuid().equals(existing)) {
                report(diagnostics, node, Code.IDENTITY_CHANGED, "vehicle " + vehicle + " is now a different NPC than the one imported"); continue;
            }
            if (!nodes.containsKey(vehicle)) { report(diagnostics, node, Code.DORMANT_VEHICLE, "vehicle " + vehicle + " is not active"); continue; }
            edges.put(node.id(), vehicle);
        }
        removeCycles(edges, nodes, diagnostics);
        Map<String, List<String>> children = new TreeMap<>();
        for (Map.Entry<String, String> edge : edges.entrySet()) children.computeIfAbsent(edge.getValue(), ignored -> new ArrayList<>()).add(edge.getKey());
        Map<String, String> vehicleOf = new LinkedHashMap<>();
        Map<String, List<String>> passengersOf = new LinkedHashMap<>();
        List<String> order = new ArrayList<>();
        Deque<Level> queue = new ArrayDeque<>();
        for (String id : nodes.keySet()) if (!edges.containsKey(id)) queue.addLast(new Level(id, 0));
        while (!queue.isEmpty()) {
            Level at = queue.removeFirst();
            List<String> kept = new ArrayList<>();
            for (String child : children.getOrDefault(at.id(), List.of())) {
                if (at.depth() + 1 > MAX_DEPTH) { report(diagnostics, nodes.get(child), Code.DEPTH, "mount chain is deeper than " + MAX_DEPTH); queue.addLast(new Level(child, 0)); continue; }
                if (kept.size() >= MAX_PASSENGERS) { report(diagnostics, nodes.get(child), Code.CAPACITY, "vehicle " + at.id() + " already carries " + MAX_PASSENGERS + " passengers"); queue.addLast(new Level(child, 0)); continue; }
                kept.add(child); vehicleOf.put(child, at.id()); order.add(child);
                queue.addLast(new Level(child, at.depth() + 1));
            }
            if (!kept.isEmpty()) passengersOf.put(at.id(), List.copyOf(kept));
        }
        diagnostics.sort(Comparator.comparing(Diagnostic::id));
        return new Resolution(Map.copyOf(vehicleOf), Map.copyOf(passengersOf), List.copyOf(order), List.copyOf(diagnostics));
    }

    private record Level(String id, int depth) {}

    private static void removeCycles(Map<String, String> edges, Map<String, Node> nodes, List<Diagnostic> diagnostics) {
        Set<String> settled = new HashSet<>();
        for (String start : List.copyOf(edges.keySet())) {
            if (settled.contains(start)) continue;
            Map<String, Integer> path = new HashMap<>();
            List<String> walk = new ArrayList<>();
            String at = start;
            while (at != null && !settled.contains(at) && !path.containsKey(at)) {
                path.put(at, walk.size()); walk.add(at); at = edges.get(at);
            }
            if (at != null && path.containsKey(at)) {
                List<String> cycle = rotateToSmallest(walk.subList(path.get(at), walk.size()));
                String chain = String.join(" -> ", cycle) + " -> " + cycle.getFirst();
                for (String member : cycle) {
                    report(diagnostics, nodes.get(member), Code.CYCLE, "mount relation forms a cycle " + chain);
                    edges.remove(member);
                }
            }
            settled.addAll(walk);
        }
    }
    private static List<String> rotateToSmallest(List<String> cycle) {
        int smallest = 0;
        for (int i = 1; i < cycle.size(); i++) if (cycle.get(i).compareTo(cycle.get(smallest)) < 0) smallest = i;
        List<String> rotated = new ArrayList<>(cycle.size());
        for (int i = 0; i < cycle.size(); i++) rotated.add(cycle.get((smallest + i) % cycle.size()));
        return rotated;
    }
    private static void report(List<Diagnostic> diagnostics, Node node, Code code, String message) {
        diagnostics.add(new Diagnostic(node.id(), node.reference().field(), code, message));
    }
}
