package art.arcane.animus.core.building;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import art.arcane.animus.core.building.BlockTransform.Mirror;
import art.arcane.animus.core.building.BlockTransform.Rotation;
import org.junit.jupiter.api.Test;

class BlockTransformTest {
    private static final BlockTransform QUARTER = new BlockTransform(Rotation.CLOCKWISE_90, Mirror.NONE);
    private static final BlockTransform HALF = new BlockTransform(Rotation.CLOCKWISE_180, Mirror.NONE);
    private static final BlockTransform MIRROR_X = new BlockTransform(Rotation.NONE, Mirror.X);
    private static final BlockTransform MIRROR_Z = new BlockTransform(Rotation.NONE, Mirror.Z);

    private static String apply(BlockTransform transform, String state) { return transform.apply(BlockState.parse(state)).toString(); }

    @Test void clockwiseQuarterTurnMovesNorthToEastAndEastToSouth() {
        assertArrayEquals(new int[]{1, 0}, QUARTER.apply(0, -1));
        assertArrayEquals(new int[]{0, 1}, QUARTER.apply(1, 0));
        assertArrayEquals(new int[]{-3, -2}, HALF.apply(3, 2));
        assertArrayEquals(new int[]{2, 3}, new BlockTransform(Rotation.CLOCKWISE_270, Mirror.NONE).apply(-3, 2));
    }

    @Test void mirrorNegatesItsAxisBeforeRotating() {
        assertArrayEquals(new int[]{-2, 5}, MIRROR_X.apply(2, 5));
        assertArrayEquals(new int[]{2, -5}, MIRROR_Z.apply(2, 5));
        assertArrayEquals(new int[]{-5, -2}, new BlockTransform(Rotation.CLOCKWISE_90, Mirror.X).apply(2, 5));
    }

    @Test void facingFollowsPositions() {
        assertEquals("minecraft:oak_stairs[facing=east,half=bottom]", apply(QUARTER, "oak_stairs[facing=north,half=bottom]"));
        assertEquals("minecraft:oak_stairs[facing=south,half=bottom]", apply(HALF, "oak_stairs[facing=north,half=bottom]"));
        assertEquals("minecraft:furnace[facing=west]", apply(MIRROR_X, "furnace[facing=east]"));
        assertEquals("minecraft:furnace[facing=north]", apply(MIRROR_X, "furnace[facing=north]"));
        assertEquals("minecraft:hopper[facing=down]", apply(QUARTER, "hopper[facing=down]"));
    }

    @Test void mirroringSwapsStairCornersHingesAndChestHalves() {
        assertEquals("minecraft:oak_stairs[facing=north,shape=inner_right]", apply(MIRROR_X, "oak_stairs[facing=north,shape=inner_left]"));
        assertEquals("minecraft:oak_stairs[facing=east,shape=outer_left]", apply(QUARTER, "oak_stairs[facing=north,shape=outer_left]"));
        assertEquals("minecraft:oak_door[hinge=left]", apply(MIRROR_Z, "oak_door[hinge=right]"));
        assertEquals("minecraft:chest[facing=north,type=right]", apply(MIRROR_X, "chest[facing=north,type=left]"));
        assertEquals("minecraft:oak_slab[type=top]", apply(MIRROR_X, "oak_slab[type=top]"));
    }

    @Test void sixteenStepRotationTurnsFourStepsPerQuarter() {
        assertEquals("minecraft:oak_sign[rotation=4]", apply(QUARTER, "oak_sign[rotation=0]"));
        assertEquals("minecraft:oak_sign[rotation=2]", apply(QUARTER, "oak_sign[rotation=14]"));
        assertEquals("minecraft:oak_sign[rotation=12]", apply(MIRROR_X, "oak_sign[rotation=4]"));
        assertEquals("minecraft:oak_sign[rotation=8]", apply(MIRROR_Z, "oak_sign[rotation=0]"));
        assertEquals("minecraft:oak_sign[rotation=4]", apply(MIRROR_Z, "oak_sign[rotation=4]"));
    }

    @Test void axisSwapsOnOddQuarterTurns() {
        assertEquals("minecraft:oak_log[axis=z]", apply(QUARTER, "oak_log[axis=x]"));
        assertEquals("minecraft:oak_log[axis=x]", apply(HALF, "oak_log[axis=x]"));
        assertEquals("minecraft:oak_log[axis=y]", apply(QUARTER, "oak_log[axis=y]"));
    }

    @Test void connectionsAndRailsTurnWithTheBuild() {
        assertEquals("minecraft:oak_fence[east=true,north=false,south=false,west=false]",
                apply(QUARTER, "oak_fence[north=true,east=false,south=false,west=false]"));
        assertEquals("minecraft:rail[shape=south_east]", apply(QUARTER, "rail[shape=north_east]"));
        assertEquals("minecraft:rail[shape=east_west]", apply(QUARTER, "rail[shape=north_south]"));
        assertEquals("minecraft:rail[shape=ascending_east]", apply(QUARTER, "rail[shape=ascending_north]"));
        assertEquals("minecraft:rail[shape=north_west]", apply(MIRROR_X, "rail[shape=north_east]"));
        assertEquals("minecraft:jigsaw[orientation=east_up]", apply(QUARTER, "jigsaw[orientation=north_up]"));
    }

    @Test void identityKeepsTheSameState() {
        BlockState state = BlockState.parse("oak_stairs[facing=north]");
        assertSame(state, BlockTransform.IDENTITY.apply(state));
    }

    @Test void rotationDegreesMustBeQuarterTurns() {
        assertEquals(Rotation.CLOCKWISE_270, Rotation.degrees(270));
        assertThrows(IllegalArgumentException.class, () -> Rotation.degrees(45));
        assertThrows(IllegalArgumentException.class, () -> Mirror.named("y"));
    }

    @Test void blockStateTextIsCanonical() {
        assertEquals("minecraft:oak_stairs[facing=north,half=bottom]", BlockState.parse("oak_stairs[half=bottom, facing=north]").toString());
        assertThrows(IllegalArgumentException.class, () -> BlockState.parse("oak_stairs[facing]"));
        assertThrows(IllegalArgumentException.class, () -> BlockState.parse("Oak_Stairs"));
        assertThrows(IllegalArgumentException.class, () -> BlockState.parse("oak_stairs[facing=north,facing=east]"));
    }
}
