package art.arcane.animus.core.building;

import art.arcane.animus.core.command.CommandActor;
import art.arcane.animus.core.navigation.BlockPos;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * {@code /animus build ...}, the same on every platform. Runs on the actor's owner, which on every platform that builds
 * is the single server owner. {@code donate} needs only {@link #DONATE_PERMISSION}; everything else needs
 * {@code animus.admin}.
 */
public final class BuildCommands {
    public static final String DONATE_PERMISSION = "animus.build.donate";
    private final Supplier<BuildService<?, ?>> service;
    private final Supplier<Optional<String>> unavailable;

    /** {@code unavailable} explains why building cannot run on this platform; then {@code service} is never called. */
    public BuildCommands(Supplier<BuildService<?, ?>> service, Supplier<Optional<String>> unavailable) {
        this.service = service;
        this.unavailable = unavailable;
    }

    /** The builder overview, or one build by its id or its NPC. */
    public void status(CommandActor actor, Optional<String> build) {
        run(actor, (BuildService<?, ?> builds) -> {
            if (build.isPresent()) actor.tell(builds.session(build.get()).status());
            else actor.tell(builds.status());
        });
    }

    public void list(CommandActor actor) { run(actor, (BuildService<?, ?> builds) -> builds.list().stream().limit(30).forEach(actor::tell)); }

    public void schematics(CommandActor actor) {
        run(actor, (BuildService<?, ?> builds) -> {
            List<String> names = builds.schematics();
            actor.tell(names.isEmpty() ? "No schematics. Put .schem or .nbt files in the " + BuildService.SCHEMATICS + " folder."
                    : "Schematics (" + names.size() + "): " + String.join(", ", names));
        });
    }

    public void inspect(CommandActor actor, String schematic) {
        run(actor, (BuildService<?, ?> builds) -> {
            builds.inspect(schematic, (List<String> lines) -> lines.forEach(reply(actor)));
            actor.tell("Reading " + schematic + "...");
        });
    }

    /**
     * Starts the NPC's saved build when no schematic is named, otherwise the schematic at {@code at} (or at the NPC) with
     * {@code options}, the {@link BuildOptions#words()} given.
     */
    public void start(CommandActor actor, String npc, Optional<String> schematic, Optional<String> at, Map<String, String> options) {
        run(actor, (BuildService<?, ?> builds) -> {
            if (schematic.isEmpty()) {
                if (at.isPresent() || !options.isEmpty()) throw new IllegalArgumentException("Name a schematic= to place it with at= or build options");
                builds.startFromDefinition(npc, actor.player(), reply(actor));
                actor.tell("Loading the saved build of " + npc + "...");
                return;
            }
            Optional<BlockPos> origin = at.map(BuildCommands::position);
            builds.start(new BuildService.Start(npc, schematic.get(), origin, BuildOptions.DEFAULT.with(options), actor.player()), reply(actor));
            actor.tell("Loading " + schematic.get() + " for " + npc + "...");
        });
    }

    public void pause(CommandActor actor, String build) { run(actor, (BuildService<?, ?> builds) -> actor.tell(builds.pause(build))); }

    public void resume(CommandActor actor, String build) { run(actor, (BuildService<?, ?> builds) -> actor.tell(builds.resume(build))); }

    public void cancel(CommandActor actor, String build) { run(actor, (BuildService<?, ?> builds) -> actor.tell(builds.cancel(build))); }

    public void undo(CommandActor actor, String build) { run(actor, (BuildService<?, ?> builds) -> actor.tell(builds.undo(build))); }

    public void survey(CommandActor actor, String build) { run(actor, (BuildService<?, ?> builds) -> builds.survey(build).stream().limit(40).forEach(actor::tell)); }

    /** The held items go to the build; only a player can donate. */
    public void donate(CommandActor actor, String build) {
        run(actor, (BuildService<?, ?> builds) -> {
            if (actor.player().isEmpty()) throw new IllegalArgumentException("Only a player can donate items");
            actor.tell(builds.donate(build, actor.player().get()));
        });
    }

    public void team(CommandActor actor, String build, boolean add, String npc) { run(actor, (BuildService<?, ?> builds) -> actor.tell(builds.team(build, add, npc))); }

    /** Schematic names for completion; none while building is unavailable. */
    public List<String> schematicNames() {
        if (unavailable.get().isPresent()) return List.of();
        try { return service.get().schematics(); }
        catch (IOException | RuntimeException unreadable) { return List.of(); }
    }

    @FunctionalInterface private interface Work { void run(BuildService<?, ?> builds) throws IOException; }

    private void run(CommandActor actor, Work work) {
        Optional<String> refused = unavailable.get();
        if (refused.isPresent()) { actor.tell("Builds unavailable: " + refused.get()); return; }
        try { work.run(service.get()); }
        catch (IOException | RuntimeException failure) { actor.tell("Animus build: " + failure.getMessage()); }
    }

    private static Consumer<String> reply(CommandActor actor) {
        return (String line) -> { if (!actor.reply(() -> actor.tell(line))) actor.tell(line); };
    }

    private static BlockPos position(String text) {
        Function<String, Integer> whole = (String part) -> {
            try { return Integer.parseInt(part.strip()); }
            catch (NumberFormatException invalid) { throw new IllegalArgumentException("at must be whole block coordinates"); }
        };
        String[] parts = text.split(",", -1);
        if (parts.length != 3) throw new IllegalArgumentException("at must be <x>,<y>,<z>");
        return new BlockPos(whole.apply(parts[0]), whole.apply(parts[1]), whole.apply(parts[2]));
    }
}
