package art.arcane.animus.core.building;

import art.arcane.animus.core.command.CommandActor;
import art.arcane.animus.core.navigation.BlockPos;
import art.arcane.animus.core.text.ChatLine;
import art.arcane.animus.core.text.Palette;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * {@code /animus build ...}, the same on every platform. Donations run on the player's own owner. Everything else runs
 * on the build service's owner: at once on a single-owner server, or queued for the global region on Folia, which
 * answers through the actor's own owner. {@code donate} needs only {@link #DONATE_PERMISSION}; everything else needs
 * {@code animus.admin}.
 */
public final class BuildCommands {
    public static final String DONATE_PERMISSION = "animus.build.donate";
    private static final int CLIPPED = 12;
    private static final String BUILD = "Build ";
    private final Supplier<BuildService<?, ?>> service;
    private final Supplier<Optional<String>> unavailable;

    /** {@code unavailable} explains why building cannot run on this platform; then {@code service} is never called. */
    public BuildCommands(Supplier<BuildService<?, ?>> service, Supplier<Optional<String>> unavailable) {
        this.service = service;
        this.unavailable = unavailable;
    }

    /** The builder overview, or one build by its id or its NPC. */
    public void status(CommandActor actor, Optional<String> build) {
        run(actor, (BuildService<?, ?> builds, CommandActor owner) -> {
            if (build.isPresent()) owner.tell(builds.session(build.get()).status());
            else owner.tell(builds.status());
        });
    }

    /** The builds as {@code id | state} rows for a paged listing, handed over on the actor's own owner. */
    public void rows(CommandActor actor, Consumer<List<String>> shown) {
        run(actor, (BuildService<?, ?> builds, CommandActor owner) -> {
            List<String> rows = builds.list().stream().filter((String line) -> line.startsWith(BUILD)).map(BuildCommands::row).toList();
            if (!actor.reply(() -> shown.accept(rows))) shown.accept(rows);
        });
    }

    /** {@code Build <id>: <state>} as {@code <id> | <state>}. */
    static String row(String line) {
        int colon = line.indexOf(": ");
        return colon < 0 ? line.substring(BUILD.length()) : line.substring(BUILD.length(), colon) + " | " + line.substring(colon + 2);
    }

    /** Schematic file names for a paged listing, handed over on the actor's own owner. */
    public void schematicRows(CommandActor actor, Consumer<List<String>> shown) {
        run(actor, (BuildService<?, ?> builds, CommandActor owner) -> {
            List<String> rows = builds.schematics();
            if (!actor.reply(() -> shown.accept(rows))) shown.accept(rows);
        });
    }

    /** Build ids for completion; none while building is unavailable. */
    public List<String> buildIds() {
        if (unavailable.get().isPresent()) return List.of();
        try {
            return service.get().list().stream().filter((String line) -> line.startsWith(BUILD)).map((String line) -> {
                String row = row(line);
                int split = row.indexOf(" | ");
                return split < 0 ? row : row.substring(0, split);
            }).toList();
        } catch (RuntimeException unreadable) { return List.of(); }
    }

    public void inspect(CommandActor actor, String schematic) {
        run(actor, (BuildService<?, ?> builds, CommandActor owner) -> {
            builds.inspect(schematic, (List<String> lines) -> lines.forEach(reply(actor)));
            owner.tell("Reading " + schematic);
        });
    }

    /**
     * Starts the NPC's saved build when no schematic is named, otherwise the schematic at {@code at} (or at the NPC) with
     * {@code options}, the {@link BuildOptions#words()} given.
     */
    public void start(CommandActor actor, String npc, Optional<String> schematic, Optional<String> at, Map<String, String> options) {
        run(actor, (BuildService<?, ?> builds, CommandActor owner) -> {
            if (schematic.isEmpty()) {
                if (at.isPresent() || !options.isEmpty()) throw new IllegalArgumentException("at= and build options need schematic=");
                builds.startFromDefinition(npc, actor.player(), reply(actor));
                owner.tell("Loading the saved build of " + npc);
                return;
            }
            Optional<BlockPos> origin = at.map(BuildCommands::position);
            builds.start(new BuildService.Start(npc, schematic.get(), origin, BuildOptions.DEFAULT.with(options), actor.player()), reply(actor));
            owner.tell("Loading " + schematic.get() + " for " + npc);
        });
    }

    public void pause(CommandActor actor, String build) { run(actor, (BuildService<?, ?> builds, CommandActor owner) -> owner.tell(builds.pause(build))); }

    public void resume(CommandActor actor, String build) { run(actor, (BuildService<?, ?> builds, CommandActor owner) -> owner.tell(builds.resume(build))); }

    public void cancel(CommandActor actor, String build) { run(actor, (BuildService<?, ?> builds, CommandActor owner) -> owner.tell(builds.cancel(build))); }

    public void undo(CommandActor actor, String build) { run(actor, (BuildService<?, ?> builds, CommandActor owner) -> owner.tell(builds.undo(build))); }

    public void survey(CommandActor actor, String build) { run(actor, (BuildService<?, ?> builds, CommandActor owner) -> clipped(owner, builds.survey(build))); }

    /** At most {@link #CLIPPED} lines, then how many there were and the next step. */
    private static void clipped(CommandActor owner, List<String> lines) {
        lines.stream().limit(CLIPPED).forEach(owner::tell);
        if (lines.size() > CLIPPED) owner.tell("Showing " + CLIPPED + " of " + lines.size() + "; place what is listed, then /animus build survey again");
    }

    /** The held items go to the build; only a player can donate. Runs on the player's own owner. */
    public void donate(CommandActor actor, String build) {
        Optional<String> refused = unavailable.get();
        if (refused.isPresent()) { actor.tell(Palette.error("Builds unavailable: " + refused.get())); return; }
        try {
            if (actor.player().isEmpty()) throw new IllegalArgumentException("Players only; run it in game holding the items");
            service.get().donateHeld(build, actor.player().get(), actor::tell);
        } catch (RuntimeException failure) { actor.tell(Palette.error("Build: " + failure.getMessage())); }
    }

    public void team(CommandActor actor, String build, boolean add, String npc) { run(actor, (BuildService<?, ?> builds, CommandActor owner) -> owner.tell(builds.team(build, add, npc))); }

    /** Schematic names for completion; none while building is unavailable. */
    public List<String> schematicNames() {
        if (unavailable.get().isPresent()) return List.of();
        try { return service.get().schematics(); }
        catch (IOException | RuntimeException unreadable) { return List.of(); }
    }

    @FunctionalInterface private interface Work { void run(BuildService<?, ?> builds, CommandActor owner) throws IOException; }

    /** Runs the work on the service's owner; a build out on a region turn ({@link BuildService.Busy}) runs it again later. */
    private void run(CommandActor actor, Work work) {
        Optional<String> refused = unavailable.get();
        if (refused.isPresent()) { actor.tell(Palette.error("Builds unavailable: " + refused.get())); return; }
        BuildService<?, ?> builds = service.get();
        Consumer<String> reply = reply(actor);
        CommandActor owner = builds.regionized() ? new Answering(actor, reply) : actor;
        boolean queued = builds.command(() -> {
            try { work.run(builds, owner); }
            catch (BuildService.Busy busy) { throw busy; }
            catch (IOException | RuntimeException failure) { owner.tell(Palette.error("Build: " + failure.getMessage())); }
        }, (String why) -> reply.accept(Palette.error("Build: " + why)));
        if (!queued) actor.tell(Palette.error("Build commands busy; try again in a moment"));
    }

    /** The actor as the service's owner may use it: every answer goes through the actor's own owner. */
    private record Answering(CommandActor actor, Consumer<String> reply) implements CommandActor {
        @Override public boolean permitted(String node) { return actor.permitted(node); }
        @Override public void tell(String message) { reply.accept(message); }
        @Override public void plain(String line) { if (!actor.reply(() -> actor.plain(line))) actor.plain(line); }
        @Override public void rich(ChatLine line) { if (!actor.reply(() -> actor.rich(line))) actor.rich(line); }
        @Override public Optional<UUID> player() { return actor.player(); }
        @Override public boolean reply(Runnable action) { return actor.reply(action); }
    }

    private static Consumer<String> reply(CommandActor actor) {
        return (String line) -> { if (!actor.reply(() -> actor.tell(line))) actor.tell(line); };
    }

    private static BlockPos position(String text) {
        Function<String, Integer> whole = (String part) -> {
            try { return Integer.parseInt(part.strip()); }
            catch (NumberFormatException invalid) { throw new IllegalArgumentException("at= takes whole block coordinates"); }
        };
        String[] parts = text.split(",", -1);
        if (parts.length != 3) throw new IllegalArgumentException("at= takes x,y,z");
        return new BlockPos(whole.apply(parts[0]), whole.apply(parts[1]), whole.apply(parts[2]));
    }
}
