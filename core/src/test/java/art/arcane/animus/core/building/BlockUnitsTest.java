package art.arcane.animus.core.building;

import static art.arcane.animus.core.building.BuildFixtures.WORLD;
import static art.arcane.animus.core.building.BuildFixtures.plan;
import static art.arcane.animus.core.building.BuildFixtures.run;
import static art.arcane.animus.core.building.BuildFixtures.schematic;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.navigation.BlockPos;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BlockUnitsTest {
    private static final BlockPos ORIGIN = new BlockPos(0, 64, 0);
    private static final String LOWER = "oak_door[facing=north,half=lower,hinge=left,open=false,powered=false]";
    private static final String UPPER = "oak_door[facing=north,half=upper,hinge=left,open=false,powered=false]";
    private BuildFixtures.World world;
    private BuildFixtures.Crew crew;
    private BuildFixtures.Events events;
    private BuildProtection.Gate protection;
    private BuildProtection.Decision decision = BuildProtection.Decision.ALLOW;
    private final List<BuildProtection.Request> requests = new ArrayList<>();

    @BeforeEach void setUp() {
        world = new BuildFixtures.World();
        crew = new BuildFixtures.Crew();
        events = new BuildFixtures.Events();
        protection = new BuildProtection.Gate(request -> { requests.add(request); return decision; }, message -> { });
        crew.add("builder", .5, 64, -2.5);
        world.items.put("minecraft:oak_door", "minecraft:oak_door");
    }

    private static BuildOptions corner(String... extra) {
        Map<String, String> words = new HashMap<>(Map.of("anchor", "corner", "pattern", "linear", "interval", "1"));
        for (int index = 0; index + 1 < extra.length; index += 2) words.put(extra[index], extra[index + 1]);
        return BuildOptions.DEFAULT.with(words);
    }
    private BuildSession session(Schematic schematic, BuildOptions options) {
        BuildSession session = new BuildSession("builder", "builder", WORLD, ORIGIN, "test.schem", "hash", options, Optional.of(UUID.randomUUID()),
                "2026-11-05T00:00:00Z", 1000, events);
        session.attach(plan(schematic, options, ORIGIN));
        return session;
    }
    private BuildSession.Context context() { return new BuildSession.Context(world, crew, protection, new BuildSession.Budget(64, 512, 64)); }
    private static String block(String text) { return BlockState.parse(text).toString(); }

    @Test void partnersFollowHalfPartTypeAndPistonFacing() {
        assertEquals(new BlockUnits.Partner(0, 1, 0, BlockState.parse(UPPER)), BlockUnits.partner(BlockState.parse(LOWER)).orElseThrow());
        assertEquals(new BlockUnits.Partner(0, -1, 0, BlockState.parse(LOWER)), BlockUnits.partner(BlockState.parse(UPPER)).orElseThrow());
        BlockUnits.Partner head = BlockUnits.partner(BlockState.parse("red_bed[facing=east,occupied=true,part=foot]")).orElseThrow();
        assertEquals(1, head.dx()); assertEquals(0, head.dz());
        assertEquals(BlockState.parse("red_bed[facing=east,occupied=false,part=head]"), head.state());
        BlockUnits.Partner right = BlockUnits.partner(BlockState.parse("chest[facing=north,type=left,waterlogged=false]")).orElseThrow();
        assertEquals(1, right.dx());
        assertEquals("right", right.state().property("type").orElseThrow());
        BlockUnits.Partner piston = BlockUnits.partner(BlockState.parse("sticky_piston[extended=true,facing=up]")).orElseThrow();
        assertEquals(1, piston.dy());
        assertEquals("minecraft:piston_head", piston.state().name());
        assertEquals("sticky", piston.state().property("type").orElseThrow());
        assertTrue(BlockUnits.partner(BlockState.parse("oak_stairs[facing=north,half=top,shape=straight]")).isEmpty());
        assertTrue(BlockUnits.partner(BlockState.parse("oak_slab[type=double]")).isEmpty());
        assertTrue(BlockUnits.partner(BlockState.parse("chest[facing=north,type=single]")).isEmpty());
        assertTrue(BlockUnits.partner(BlockState.parse("piston[extended=false,facing=up]")).isEmpty());
        assertTrue(BlockUnits.leads(BlockState.parse(LOWER)));
        assertFalse(BlockUnits.leads(BlockState.parse(UPPER)));
    }

    @Test void plannerMakesOneStepForEachTwoCellObject() {
        Map<Character, String> legend = Map.of('d', LOWER, 'D', UPPER, 'f', "sunflower[half=lower]", 'F', "sunflower[half=upper]",
                'b', "white_bed[facing=east,occupied=false,part=foot]", 'B', "white_bed[facing=east,occupied=false,part=head]",
                'l', "chest[facing=north,type=left,waterlogged=false]", 'r', "chest[facing=north,type=right,waterlogged=false]");
        BuildPlan plan = plan(schematic(legend, new String[]{"dfbBlr"}, new String[]{"DF...."}), corner(), ORIGIN);
        assertEquals(4, plan.size());
        int units = 0;
        for (int step = 0; step < plan.size(); step++) {
            BuildPlan.Unit unit = plan.unit(step).orElseThrow();
            assertEquals(2, unit.parts().size());
            assertEquals(plan.stepPosition(step), unit.parts().getFirst().at());
            units++;
        }
        assertEquals(4, units);
        int doorStep = 0;
        while (!plan.target(doorStep).path().equals("oak_door")) doorStep++;
        BuildPlan.Unit door = plan.unit(doorStep).orElseThrow();
        assertEquals(new BlockPos(0, 65, 0), door.parts().get(1).at());
        assertEquals(BlockState.parse(UPPER), door.parts().get(1).state());
    }

    @Test void aLoneHalfGetsItsPartnerAndAnOccupiedBedIsPlacedEmpty() {
        Map<Character, String> legend = Map.of('D', UPPER, 'b', "white_bed[facing=south,occupied=true,part=foot]");
        BuildPlan plan = plan(schematic(legend, new String[]{"b."}, new String[]{".D"}), corner(), ORIGIN);
        assertEquals(2, plan.size());
        BuildPlan.Unit bed = plan.unit(0).orElseThrow();
        assertEquals(BlockState.parse("white_bed[facing=south,occupied=false,part=foot]"), bed.parts().getFirst().state());
        assertEquals(new BlockPos(0, 64, 1), bed.parts().get(1).at());
        BuildPlan.Unit door = plan.unit(1).orElseThrow();
        assertEquals(BlockState.parse(LOWER), door.parts().getFirst().state());
        assertEquals(new BlockPos(1, 64, 0), door.parts().getFirst().at());
        assertEquals(new BlockPos(1, 65, 0), plan.stepPosition(1));
    }

    @Test void aLoneHalfWhosePartnerCellHoldsAnotherBlockIsLeftOut() {
        Map<Character, String> legend = Map.of('d', LOWER, 's', "stone");
        BuildPlan plan = plan(schematic(legend, new String[]{"d"}, new String[]{"s"}), corner(), ORIGIN);
        assertEquals(1, plan.size());
        assertEquals(BlockState.parse("stone"), plan.target(0));
    }

    @Test void rotationTurnsTheOffsetsOfEveryPart() {
        Map<Character, String> legend = Map.of('b', "white_bed[facing=east,occupied=false,part=foot]", 'B', "white_bed[facing=east,occupied=false,part=head]");
        BuildPlan plan = plan(schematic(legend, new String[]{"bB"}), corner("rotation", "90"), ORIGIN);
        BuildPlan.Unit bed = plan.unit(0).orElseThrow();
        assertEquals("south", bed.parts().getFirst().state().property("facing").orElseThrow());
        BlockPos foot = bed.parts().getFirst().at(), head = bed.parts().get(1).at();
        assertEquals(foot.x(), head.x());
        assertEquals(foot.z() + 1, head.z());
    }

    @Test void dripleafStemsAndTheirHeadAreOneUnitPlacedBottomUp() {
        Map<Character, String> legend = Map.of('s', "big_dripleaf_stem[facing=north,waterlogged=false]", 'h', "big_dripleaf[facing=north,tilt=none,waterlogged=false]");
        BuildPlan plan = plan(schematic(legend, new String[]{"s"}, new String[]{"s"}, new String[]{"h"}), corner(), ORIGIN);
        assertEquals(1, plan.size());
        List<BuildPlan.Part> parts = plan.unit(0).orElseThrow().parts();
        assertEquals(3, parts.size());
        assertEquals(List.of(64, 65, 66), parts.stream().map((BuildPlan.Part part) -> part.at().y()).toList());
        BuildPlan headless = plan(schematic(legend, new String[]{"s"}), corner(), ORIGIN);
        assertEquals("minecraft:big_dripleaf", headless.unit(0).orElseThrow().parts().get(1).state().name());
    }

    @Test void bothHalvesArriveInTheSameTickAndCostOneItem() {
        Map<Character, String> legend = Map.of('d', LOWER, 'D', UPPER);
        BuildOptions options = corner("materials", "donate");
        BuildSession session = session(schematic(legend, new String[]{"d"}, new String[]{"D"}), options);
        session.bind(world);
        assertEquals(Map.of("minecraft:oak_door", 1L), session.remaining());
        assertEquals(1, session.donate("minecraft:oak_door", 5));
        BuildSession.Context context = context();
        int ticks = 0;
        while (!world.blocks.containsKey(new BlockPos(0, 64, 0)) && ticks++ < 100) {
            session.tick(context);
            assertEquals(world.blocks.containsKey(new BlockPos(0, 64, 0)), world.blocks.containsKey(new BlockPos(0, 65, 0)), "never a lone half");
        }
        assertEquals(block(LOWER), world.blocks.get(new BlockPos(0, 64, 0)));
        assertEquals(block(UPPER), world.blocks.get(new BlockPos(0, 65, 0)));
        run(session, context, 100);
        assertEquals(BuildSession.State.FINISHED, session.state());
        assertTrue(session.ledger().isEmpty());
        assertEquals(2, session.placed());
    }

    @Test void aDoubleChestCostsTwoChestsAndWaitsForBoth() {
        Map<Character, String> legend = Map.of('l', "chest[facing=north,type=left,waterlogged=false]", 'r', "chest[facing=north,type=right,waterlogged=false]");
        BuildSession session = session(schematic(legend, new String[]{"lr"}), corner("materials", "donate"));
        session.bind(world);
        assertEquals(Map.of("minecraft:chest", 2L), session.remaining());
        session.donate("minecraft:chest", 1);
        run(session, context(), 40);
        assertEquals(BuildSession.State.WAITING, session.state());
        assertTrue(world.blocks.isEmpty(), "half a double chest is never placed");
        session.donate("minecraft:chest", 1);
        for (int tick = 0; tick < 60 && session.state() != BuildSession.State.FINISHED; tick++) session.tick(context());
        assertEquals(BuildSession.State.FINISHED, session.state());
        assertEquals(2, world.blocks.size());
    }

    @Test void protectionDenyingOnePartKeepsTheWholeUnitOut() {
        Map<Character, String> legend = Map.of('d', LOWER, 'D', UPPER);
        BuildSession session = session(schematic(legend, new String[]{"d"}, new String[]{"D"}), corner());
        protection = new BuildProtection.Gate(request -> request.at().y() == 65 ? BuildProtection.Decision.DENY : BuildProtection.Decision.ALLOW, message -> { });
        run(session, context(), 100);
        assertTrue(world.blocks.isEmpty());
        assertEquals(1, session.denied());
    }

    @Test void undoRemovesAUnitInOneGoAndKeepsItWhenAPartChanged() {
        Map<Character, String> legend = Map.of('d', LOWER, 'D', UPPER, 's', "stone");
        BuildSession session = session(schematic(legend, new String[]{"ds"}, new String[]{"D."}), corner());
        run(session, context(), 100);
        assertEquals(3, world.blocks.size());
        session.undo();
        for (int tick = 0; tick < 10 && session.state() == BuildSession.State.UNDOING; tick++)
            session.tick(new BuildSession.Context(world, crew, protection, new BuildSession.Budget(64, 512, 1)));
        assertTrue(world.blocks.isEmpty());

        BuildSession again = session(schematic(legend, new String[]{"ds"}, new String[]{"D."}), corner());
        run(again, context(), 100);
        world.blocks.put(new BlockPos(0, 65, 0), "minecraft:glass");
        again.undo();
        run(again, context(), 100);
        assertEquals(block(LOWER), world.blocks.get(new BlockPos(0, 64, 0)), "the door is kept whole when one half changed");
        assertNull(world.blocks.get(new BlockPos(1, 64, 0)));
    }

    @Test void clearingOneHalfOfADoorInTheWorldClearsBothAndUndoBringsBothBack() {
        world.blocks.put(new BlockPos(0, 64, 0), block(LOWER));
        world.blocks.put(new BlockPos(0, 65, 0), block(UPPER));
        Map<Character, String> legend = Map.of('s', "stone");
        BuildSession session = session(schematic(legend, new String[]{"s"}), corner("excavate", "true"));
        run(session, context(), 100);
        assertEquals("minecraft:stone", world.blocks.get(new BlockPos(0, 64, 0)));
        assertNull(world.blocks.get(new BlockPos(0, 65, 0)), "the upper half outside the box goes with its lower half");
        session.undo();
        run(session, context(), 100);
        assertEquals(block(LOWER), world.blocks.get(new BlockPos(0, 64, 0)));
        assertEquals(block(UPPER), world.blocks.get(new BlockPos(0, 65, 0)));
    }
}
