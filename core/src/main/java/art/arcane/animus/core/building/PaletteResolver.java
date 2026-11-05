package art.arcane.animus.core.building;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Matches a schematic palette, after rotation and mirroring, to the blocks this server has. A state the server does not
 * know is tried again under a block's newer name, then without its properties; a block that still does not match is
 * reported and its cells are left alone.
 */
public final class PaletteResolver {
    /** Block names changed between Minecraft versions, both ways where either may be current. */
    static final Map<String, String> RENAMES = Map.of(
            "minecraft:grass", "minecraft:short_grass",
            "minecraft:grass_path", "minecraft:dirt_path",
            "minecraft:sign", "minecraft:oak_sign",
            "minecraft:wall_sign", "minecraft:oak_wall_sign",
            "minecraft:chain", "minecraft:iron_chain",
            "minecraft:iron_chain", "minecraft:chain");
    private static final int MAXIMUM_REPORT = 64;

    public record Result(List<BlockState> palette, boolean[] placeable, List<String> report, int unknownCells) {
        public Result { palette = List.copyOf(palette); report = List.copyOf(report); }
    }

    private PaletteResolver() {}

    public static Result resolve(Schematic schematic, BlockTransform transform, Function<BlockState, Optional<String>> server) {
        int[] uses = new int[schematic.palette().size()];
        for (int cell = 0; cell < schematic.volume(); cell++) if (schematic.cell(cell) != Schematic.VOID) uses[schematic.cell(cell)]++;
        List<BlockState> palette = new ArrayList<>();
        boolean[] placeable = new boolean[uses.length];
        List<String> report = new ArrayList<>();
        int unknown = 0, omitted = 0;
        for (int index = 0; index < uses.length; index++) {
            BlockState original = schematic.palette().get(index);
            BlockState turned = transform.apply(original);
            Optional<String> exact = server.apply(turned);
            String note = null;
            String renamed = RENAMES.get(turned.name());
            if (exact.isEmpty() && renamed != null) {
                exact = server.apply(turned.withName(renamed));
                if (exact.isPresent()) note = "renamed " + original.name() + " to " + renamed;
            }
            if (exact.isEmpty() && !turned.properties().isEmpty()) {
                BlockState plain = renamed != null && server.apply(turned.withName(renamed).withoutProperties()).isPresent()
                        ? turned.withName(renamed).withoutProperties() : turned.withoutProperties();
                exact = server.apply(plain);
                if (exact.isPresent()) note = "placed " + original + " without the properties this server does not have (" + uses[index] + " cells)";
            }
            Optional<BlockState> parsed = exact.flatMap(PaletteResolver::parse);
            if (parsed.isPresent()) {
                palette.add(parsed.get());
                placeable[index] = true;
            } else {
                palette.add(turned);
                unknown += uses[index];
                note = "unknown block " + original + " (" + uses[index] + " cells left unchanged)";
            }
            if (note != null) {
                if (report.size() < MAXIMUM_REPORT) report.add(note); else omitted++;
            }
        }
        if (omitted > 0) report.add("and " + omitted + " more palette notes");
        return new Result(palette, placeable, report, unknown);
    }

    /** Platform text in this package's canonical form. Text that does not parse cannot be compared, so it is refused. */
    public static Optional<BlockState> parse(String text) {
        try { return Optional.of(BlockState.parse(text)); }
        catch (IllegalArgumentException unreadable) { return Optional.empty(); }
    }
}
