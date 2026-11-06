package art.arcane.animus.core.building;

import art.arcane.animus.core.command.CommandActor;
import art.arcane.animus.core.navigation.BlockPos;
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

    public void list(CommandActor actor) { run(actor, (BuildService<?, ?> builds, CommandActor owner) -> builds.list().stream().limit(30).forEach(owner::tell)); }

    public void schematics(CommandActor actor) {
        run(actor, (BuildService<?, ?> builds, CommandActor owner) -> {
            List<String> names = builds.schematics();
            owner.tell(names.isEmpty() ? "No schematics. Put .schem or .nbt files in the " + BuildService.SCHEMATICS + " folder."
                    : "Schematics (" + names.size() + "): " + String.join(", ", names));
        });
    }

    public void inspect(CommandActor actor, String schematic) {
        run(actor, (BuildService<?, ?> builds, CommandActor owner) -> {
            builds.inspect(schematic, (List<String> lines) -> lines.forEach(reply(actor)));
            owner.tell("Reading " + schematic + "...");
        });
    }

    /**
     * Starts the NPC's saved build when no schematic is named, otherwise the schematic at {@code at} (or at the NPC) with
     * {@code options}, the {@link BuildOptions#words()} given.
     */
    public void start(CommandActor actor, String npc, Optional<String> schematic, Optional<String> at, Map<String, String> options) {
        run(actor, (BuildService<?, ?> builds, CommandActor owner) -> {
            if (schematic.isEmpty()) {
                if (at.isPresent() || !options.isEmpty()) throw new IllegalArgumentException("Name a schematic= to place it with at= or build options");
                builds.startFromDefinition(npc, actor.player(), reply(actor));
                owner.tell("Loading the saved build of " + npc + "...");
                return;
            }
            Optional<BlockPos> origin = at.map(BuildCommands::position);
            builds.start(new BuildService.Start(npc, schematic.get(), origin, BuildOptions.DEFAULT.with(options), actor.player()), reply(actor));
            owner.tell("Loading " + schematic.get() + " for " + npc + "...");
        });
    }

    public void pause(CommandActor actor, String build) { run(actor, (BuildService<?, ?> builds, CommandActor owner) -> owner.tell(builds.pause(build))); }

    public void resume(CommandActor actor, String build) { run(actor, (BuildService<?, ?> builds, CommandActor owner) -> owner.tell(builds.resume(build))); }

    public void cancel(CommandActor actor, String build) { run(actor, (BuildService<?, ?> builds, CommandActor owner) -> owner.tell(builds.cancel(build))); }

    public void undo(CommandActor actor, String build) { run(actor, (BuildService<?, ?> builds, CommandActor owner) -> owner.tell(builds.undo(build))); }

    public void survey(CommandActor actor, String build) { run(actor, (BuildService<?, ?> builds, CommandActor owner) -> builds.survey(build).stream().limit(40).forEach(owner::tell)); }

    /** The held items go to the build; only a player can donate. Runs on the player's own owner. */
    public void donate(CommandActor actor, String build) {
        Optional<String> refused = unavailable.get();
        if (refused.isPresent()) { actor.tell("Builds unavailable: " + refused.get()); return; }
        try {
            if (actor.player().isEmpty()) throw new IllegalArgumentException("Only a player can donate items");
            service.get().donateHeld(build, actor.player().get(), actor::tell);
        } catch (RuntimeException failure) { actor.tell("Animus build: " + failure.getMessage()); }
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
        if (refused.isPresent()) { actor.tell("Builds unavailable: " + refused.get()); return; }
        BuildService<?, ?> builds = service.get();
        Consumer<String> reply = reply(actor);
        CommandActor owner = builds.regionized() ? new Answering(actor, reply) : actor;
        boolean queued = builds.command(() -> {
            try { work.run(builds, owner); }
            catch (BuildService.Busy busy) { throw busy; }
            catch (IOException | RuntimeException failure) { owner.tell("Animus build: " + failure.getMessage()); }
        }, (String why) -> reply.accept("Animus build: " + why));
        if (!queued) actor.tell("Animus build: build commands are busy; try again");
    }

    /** The actor as the service's owner may use it: every answer goes through the actor's own owner. */
    private record Answering(CommandActor actor, Consumer<String> reply) implements CommandActor {
        @Override public boolean permitted(String node) { return actor.permitted(node); }
        @Override public void tell(String message) { reply.accept(message); }
        @Override public void plain(String line) { if (!actor.reply(() -> actor.plain(line))) actor.plain(line); }
        @Override public Optional<UUID> player() { return actor.player(); }
        @Override public boolean reply(Runnable action) { return actor.reply(action); }
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
