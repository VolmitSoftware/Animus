package art.arcane.animus.core.mount;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MountRelationsTest {
    private static UUID uuid(String id) { return UUID.nameUUIDFromBytes(id.getBytes()); }
    private static MountRelations.Node node(String id, String vehicle) {
        return new MountRelations.Node(id, uuid(id), vehicle == null ? null
                : new MountReference("$.modules.animus:mount.vehicle", vehicle, null, MountReference.Status.DECLARED, vehicle));
    }
    private static MountRelations.Resolution resolve(MountRelations.Node... nodes) {
        Map<String, UUID> known = new HashMap<>();
        for (MountRelations.Node node : nodes) known.put(node.id(), node.uuid());
        return MountRelations.resolve(List.of(nodes), known::get);
    }
    private static MountRelations.Diagnostic only(MountRelations.Resolution resolution, String id) {
        List<MountRelations.Diagnostic> found = resolution.diagnostics().stream().filter(value -> value.id().equals(id)).toList();
        assertEquals(1, found.size(), resolution.diagnostics().toString());
        return found.getFirst();
    }

    @Test void chainResolvesRootsFirstAndKeepsDeterministicPassengerOrder() {
        MountRelations.Resolution result = resolve(node("c", "b"), node("b", "a"), node("a", null), node("d", "a"));
        assertEquals("a", result.vehicleOf().get("b"));
        assertEquals(List.of("b", "d"), result.passengersOf().get("a"));
        assertEquals(List.of("c"), result.passengersOf().get("b"));
        assertEquals(List.of("b", "d", "c"), result.order());
        assertEquals(List.of(), result.diagnostics());
    }

    @Test void selfReferenceIsRefusedWithItsField() {
        MountRelations.Diagnostic problem = only(resolve(node("a", "a")), "a");
        assertEquals(MountRelations.Code.SELF, problem.code());
        assertEquals("$.modules.animus:mount.vehicle", problem.field());
    }

    @Test void everyCycleMemberIsDetachedAndReportedWhileATailStillRides() {
        MountRelations.Resolution result = resolve(node("a", "b"), node("b", "c"), node("c", "a"), node("tail", "a"));
        for (String id : List.of("a", "b", "c")) {
            MountRelations.Diagnostic problem = only(result, id);
            assertEquals(MountRelations.Code.CYCLE, problem.code());
            assertTrue(problem.message().contains("a -> b -> c -> a"), problem.message());
            assertFalse(result.vehicleOf().containsKey(id));
        }
        assertEquals("a", result.vehicleOf().get("tail"));
    }

    @Test void missingDormantAndChangedVehiclesStayUnattachedAndReported() {
        MountRelations.Node rider = node("rider", "gone");
        assertEquals(MountRelations.Code.UNKNOWN_VEHICLE, only(MountRelations.resolve(List.of(rider), id -> null), "rider").code());
        assertEquals(MountRelations.Code.DORMANT_VEHICLE, only(MountRelations.resolve(List.of(rider), MountRelationsTest::uuid), "rider").code());
        MountRelations.Node imported = new MountRelations.Node("rider", uuid("rider"),
                new MountReference("$.migration.references[0]", "gone", UUID.randomUUID(), MountReference.Status.DECLARED, "x"));
        MountRelations.Diagnostic changed = only(MountRelations.resolve(List.of(imported), MountRelationsTest::uuid), "rider");
        assertEquals(MountRelations.Code.IDENTITY_CHANGED, changed.code());
        assertEquals("$.migration.references[0]", changed.field());
    }

    @Test void importedUnresolvedStatusIsReportedWithItsOriginalValue() {
        MountRelations.Node missing = new MountRelations.Node("rider", uuid("rider"),
                new MountReference("$.migration.references[0]", null, null, MountReference.Status.MISSING, "abc"));
        MountRelations.Diagnostic problem = only(MountRelations.resolve(List.of(missing), id -> null), "rider");
        assertEquals(MountRelations.Code.UNRESOLVED_IMPORT, problem.code());
        assertTrue(problem.message().contains("abc"));
    }

    @Test void depthAndPassengerCapacityAreBoundedAndReported() {
        List<MountRelations.Node> chain = new ArrayList<>();
        chain.add(node("n00", null));
        for (int i = 1; i <= MountRelations.MAX_DEPTH + 1; i++) chain.add(node("n%02d".formatted(i), "n%02d".formatted(i - 1)));
        MountRelations.Resolution deep = resolve(chain.toArray(MountRelations.Node[]::new));
        assertEquals(MountRelations.Code.DEPTH, only(deep, "n%02d".formatted(MountRelations.MAX_DEPTH + 1)).code());
        assertEquals(MountRelations.MAX_DEPTH, deep.vehicleOf().size());

        List<MountRelations.Node> wide = new ArrayList<>();
        wide.add(node("v", null));
        for (int i = 0; i <= MountRelations.MAX_PASSENGERS; i++) wide.add(node("p%02d".formatted(i), "v"));
        MountRelations.Resolution full = resolve(wide.toArray(MountRelations.Node[]::new));
        assertEquals(MountRelations.MAX_PASSENGERS, full.passengersOf().get("v").size());
        assertEquals(MountRelations.Code.CAPACITY, only(full, "p%02d".formatted(MountRelations.MAX_PASSENGERS)).code());
    }

    @Test void anyDeclaredGraphResolvesToAnAcyclicBoundedForest() {
        Random random = new Random(26);
        for (int round = 0; round < 200; round++) {
            int size = 1 + random.nextInt(24);
            List<MountRelations.Node> nodes = new ArrayList<>();
            for (int i = 0; i < size; i++) nodes.add(node("n" + i, random.nextInt(4) == 0 ? null : "n" + random.nextInt(size)));
            MountRelations.Resolution result = resolve(nodes.toArray(MountRelations.Node[]::new));
            for (String start : result.vehicleOf().keySet()) {
                int depth = 0;
                for (String at = start; at != null; at = result.vehicleOf().get(at)) assertTrue(++depth <= MountRelations.MAX_DEPTH + 1, "cycle or excess depth");
            }
            result.passengersOf().values().forEach(list -> assertTrue(list.size() <= MountRelations.MAX_PASSENGERS));
            assertEquals(result.vehicleOf().size(), result.order().size());
        }
    }

    @Test void structuralProblemsBlockActivationWhilePreservedMissingReferencesDoNot() {
        assertTrue(MountRelations.Code.CYCLE.blocksActivation());
        assertTrue(MountRelations.Code.SELF.blocksActivation());
        assertTrue(MountRelations.Code.DEPTH.blocksActivation());
        assertFalse(MountRelations.Code.UNKNOWN_VEHICLE.blocksActivation());
        assertFalse(MountRelations.Code.UNRESOLVED_IMPORT.blocksActivation());
        assertFalse(MountRelations.Code.DORMANT_VEHICLE.blocksActivation());
    }

    @Test void ridersLeavingInTheSameChangeAsTheirVehicleAreNotReported() {
        MountRelations.Node rider = node("rider", "horse"), other = node("other", "horse");
        Map<String, UUID> known = new HashMap<>();
        known.put("other", other.uuid());
        MountRelations.Resolution result = MountRelations.resolve(List.of(rider, other), known::get,
                (MountRelations.Node node) -> !node.uuid().equals(known.get(node.id())));
        assertFalse(result.vehicleOf().containsKey("rider"));
        assertEquals(List.of("other"), result.diagnostics().stream().map(MountRelations.Diagnostic::id).toList());
        assertEquals(MountRelations.Code.UNKNOWN_VEHICLE, only(result, "other").code());
    }
}
