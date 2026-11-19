package art.arcane.animus.bukkit.navigation;

import static art.arcane.animus.bukkit.Stubs.stub;
import static org.junit.jupiter.api.Assertions.assertEquals;

import art.arcane.animus.bukkit.EventChain;
import art.arcane.animus.bukkit.ForeignProbes;
import art.arcane.animus.core.navigation.BlockPos;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.junit.jupiter.api.Test;

class TerrainChangesTest {
    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    @Test void onlyARealBreakMarksTheGround() {
        List<BlockPos> marked = new ArrayList<>();
        TerrainChanges changes = new TerrainChanges((String world, BlockPos at) -> marked.add(at));
        World world = stub(World.class, (String method, Object[] arguments) -> method.equals("getUID") ? WORLD : null);
        Block ground = stub(Block.class, (String method, Object[] arguments) -> switch (method) {
            case "getWorld" -> world;
            case "getX" -> 3;
            case "getY" -> 63;
            case "getZ" -> -4;
            default -> null;
        });
        Player player = stub(Player.class, (String method, Object[] arguments) -> null);
        EventChain.fire(ForeignProbes.breaking(player, ground), changes);
        assertEquals(List.of(), marked, "a protection probe breaks nothing");
        EventChain.fire(new BlockBreakEvent(ground, player), changes);
        assertEquals(List.of(new BlockPos(3, 63, -4)), marked);
    }
}
