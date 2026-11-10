package art.arcane.animus.core.building;

import art.arcane.animus.api.Outcome;
import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.behavior.builtin.BehaviorPlatform;
import art.arcane.animus.core.extension.CapabilityRefusal;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.behavior.trigger.TriggerEvent;
import art.arcane.animus.core.behavior.trigger.TriggerSink;
import art.arcane.animus.core.building.schematic.NbtReader;
import art.arcane.animus.core.building.schematic.SchematicException;
import art.arcane.animus.core.building.schematic.SchematicReader;
import art.arcane.animus.core.config.BuildSettings;
import art.arcane.animus.core.config.PluginSettings;
import art.arcane.animus.core.navigation.BlockPos;
import art.arcane.animus.core.navigation.MovementProfile;
import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.runtime.NpcRuntime;
import art.arcane.animus.core.runtime.PersistedNpcService;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import art.arcane.animus.core.text.Palette;
import art.arcane.animus.core.virtual.Hitbox;
import art.arcane.animus.core.virtual.WorldPosition;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Builds. Schematics are read and planned on one worker thread, block palettes are matched on the service's owner, and
 * saved state is written on the shared file lane.
 * <p>
 * On a single-owner runtime (Bukkit and Paper's main thread, or a mod server's thread) the owner also runs every
 * build's tick and every command. On a regionized server ({@link Regions}, Folia) the owner is the global region: it
 * keeps the build records, runs commands and saves, and sends each build out for one turn at a time to the region that
 * owns where the build works now (see {@link BuildSession#focus}). A build out on a turn is touched only by that region
 * until the turn comes back; a command for it waits until then. Block changes, the NPCs' own movement and scaffolding all
 * happen on that region, which reads only chunks it owns.
 *
 * @param <P> the platform's online player
 * @param <I> the platform's item stack
 */
public final class BuildService<P, I> implements AutoCloseable {
    public static final String SCHEMATICS = "schematics";
    private static final String STATE = "state/builds";
    private static final Pattern FILE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}\\.(schem|nbt|schematic)");
    private static final int TICKS_PER_SECOND = 20, MAXIMUM_REPORT_LINES = 8, MAXIMUM_LISTED = 200, BATCH_CHARACTERS = 2 * 1024 * 1024;
    /** Commands waiting for the owner, and how many owner ticks one may wait for its build to come back from a turn. */
    private static final int MAXIMUM_COMMANDS = 64, COMMAND_WAIT_TICKS = 200;
    /** A turn no region started in this long is taken back; the build is sent again. */
    private static final long TURN_TIMEOUT_NANOS = 10_000_000_000L, SHUTDOWN_WAIT_NANOS = 1_000_000_000L;

    /** What a platform adds to block access for builds. Owner thread; player methods on the player's owner. */
    public interface Platform<P, I> extends BuildWorld {
        record Held(String item, int count) {}
        /** An item stack for an item id, or the platform's empty stack. */
        I stack(Optional<String> item);
        Optional<Held> held(P player);
        void takeHeld(P player, int count);
        /** Regionized servers: takes the whole held stack out of the main hand and returns it as it was, damage and components included. */
        default I removeHeld(P player) { throw new UnsupportedOperationException("Only regionized servers take the held stack whole"); }
        /** Regionized servers: hands {@code count} of that exact stack back, dropping what does not fit at the player's feet. */
        default void giveBack(P player, I stack, int count) { throw new UnsupportedOperationException("Only regionized servers hand donations back"); }
        void tell(P player, String message);
    }

    /** A regionized server's region schedulers, as builds use them. */
    public interface Regions {
        /** Runs the work on the region that owns the chunk, at that region's next tick; false when refused. */
        boolean run(String world, int chunkX, int chunkZ, Runnable work);
        /** Why a turn cannot be sent to the chunk now (for example, it is not known to be loaded), or null. */
        String unreachable(String world, int chunkX, int chunkZ);
    }

    /** A command for a build that is out on a region turn; the command runs again once the build is back. */
    public static final class Busy extends RuntimeException {
        Busy(String id) { super("Build " + id + " is busy on its region", null, false, false); }
    }

    private record Command(Runnable work, Consumer<String> refused, int waited) {}
    /** What commands and status read of a build while it is out on a turn: its state when it left. */
    private record View(BuildSession.State state, List<String> team, String status) {}

    /** A request to start a build. An empty origin means the block the leader stands in. */
    public record Start(String leader, String schematic, Optional<BlockPos> origin, BuildOptions options, Optional<UUID> startedBy) {
        public Start {
            Objects.requireNonNull(leader); Objects.requireNonNull(schematic); Objects.requireNonNull(origin);
            Objects.requireNonNull(options); Objects.requireNonNull(startedBy);
        }
    }

    private record Loaded(Schematic schematic, String hash) {}

    private final AnimusRuntime runtime;
    private final BuildNpcs<P, I> living;
    /** Regionized servers only; null where the owner runs every build tick itself. */
    private final Regions regions;
    private final BuildTurns turns;
    private final Map<String, View> views = new HashMap<>();
    private final ConcurrentLinkedQueue<Command> commands = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queuedCommands = new AtomicInteger();
    private final Executor files;
    private final Platform<P, I> platform;
    private final BuildProtection.Gate protection;
    private final String protectionName;
    private final Supplier<TriggerSink> triggers;
    private final Consumer<String> log;
    /** Why builds can never run here (Folia, no NPC runtime), or null. */
    private final String platformReason;
    /** {@code [builds]} of config.toml; a hotload replaces it whole and the next read uses it. */
    private volatile BuildSettings settings;
    private final AutoCloseable settingsListener;
    /** Saved builds are read once, the first tick builds are on. Owner thread. */
    private boolean restored, paused;
    private final Map<String, BuildSession> sessions = new LinkedHashMap<>();
    private final Set<String> loading = new HashSet<>();
    /** Concurrent: a build out on a region turn marks itself changed there. */
    private final Set<String> dirty = new ConcurrentSkipListSet<>();
    /** A build stopped, finished or paused somewhere and wants its record saved at the next owner tick. */
    private volatile boolean urgentSave;
    private final Set<String> saving = new HashSet<>();
    private final ConcurrentLinkedQueue<Runnable> completions = new ConcurrentLinkedQueue<>();
    private final Crew crew = new Crew();
    private final Listener listener = new Listener();
    /** Orders lane writes against the final save at shutdown, so an older lane write never lands after it. */
    private final Object saveLock = new Object();
    private boolean finalSaved;
    private ExecutorService worker;
    private AutoCloseable ticker;
    private long tick, lastSave, saveFailures;
    private int rotation;
    private volatile boolean closed;

    /**
     * Construct on the owner thread.
     *
     * @param regions a regionized server's region schedulers, or null where {@code ticks} runs every build itself
     * @param unavailable why builds cannot run on this platform, or null
     */
    public BuildService(AnimusRuntime runtime, BuildNpcs<P, I> living, Executor files, Platform<P, I> platform,
                        BuildProtection protection, String protectionName, Function<Runnable, AutoCloseable> ticks,
                        Supplier<TriggerSink> triggers, Consumer<String> log, Regions regions, String unavailable) {
        this.runtime = Objects.requireNonNull(runtime); this.files = Objects.requireNonNull(files);
        this.protectionName = Objects.requireNonNull(protectionName); this.triggers = Objects.requireNonNull(triggers);
        this.log = Objects.requireNonNull(log);
        this.living = living; this.platform = platform;
        this.regions = regions;
        turns = regions == null ? null : new BuildTurns(regions, TURN_TIMEOUT_NANOS);
        this.protection = new BuildProtection.Gate(Objects.requireNonNull(protection), log);
        settings = runtime.buildSettings();
        platformReason = unavailable != null ? unavailable : living == null ? "unavailable because the NPC runtime is: missing" : null;
        if (platformReason != null) { settingsListener = () -> {}; return; }
        settingsListener = runtime.onSettings((PluginSettings next) -> settings = next.builds());
        Objects.requireNonNull(platform, "platform");
        worker = Executors.newSingleThreadExecutor((Runnable work) -> {
            Thread thread = new Thread(work, "Animus build planner");
            thread.setDaemon(true);
            return thread;
        });
        ticker = ticks.apply(this::tick);
        restoreWhenReady();
    }

    public boolean available() { return unavailable().isEmpty() && !closed; }
    /** Builds take region turns: commands are queued for the global region and answer later. */
    public boolean regionized() { return regions != null; }
    /** Why builds cannot run now: the platform, {@code builds.enabled = false}, or an unavailable NPC runtime. Follows a hotload. */
    public Optional<String> unavailable() {
        if (platformReason != null) return Optional.of(platformReason);
        if (!settings.enabled()) return Optional.of("disabled in config.toml");
        if (!living.available()) return Optional.of("unavailable because the NPC runtime is: " + living.status());
        return Optional.empty();
    }
    private void requireAvailable() {
        Optional<String> reason = unavailable();
        if (reason.isPresent()) throw new IllegalStateException("Building is " + reason.get());
        if (closed) throw new IllegalStateException("Building has stopped");
    }

    public String status() {
        Optional<String> reason = unavailable();
        if (reason.isPresent()) return "Builds unavailable: " + reason.get() + (sessions.isEmpty() ? "" : " | saved=" + sessions.size());
        return "Builds ready" + (regions == null ? "" : " on Folia regions") + " | open=" + open() + "/" + settings.maximumBuilds() + " | saved=" + sessions.size()
                + " | loading=" + loading.size() + (turns == null ? "" : " | region-turns=" + turns.sent() + " out=" + turns.outstanding() + " lost=" + turns.lost())
                + " | protection=" + protectionName + " | protection-denials=" + protection.denials() + " | protection-failures=" + protection.failures()
                + (saveFailures > 0 ? " | save-failures=" + saveFailures : "");
    }

    public List<String> list() {
        requireAvailable();
        List<String> lines = new ArrayList<>();
        for (BuildSession session : sessions.values()) lines.add(statusOf(session));
        for (String id : loading) lines.add("Build " + id + ": loading");
        if (lines.isEmpty()) lines.add("No builds; start one with /animus build start <npc> schematic=<file>");
        return lines;
    }

    /** Schematic files in the data folder. Reads one directory; call from a command. */
    public List<String> schematics() throws IOException {
        requireAvailable();
        Path folder = runtime.documents().resolve(SCHEMATICS);
        Files.createDirectories(folder);
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (FILE.matcher(name).matches() && Files.isRegularFile(entry)) names.add(name);
                if (names.size() >= MAXIMUM_LISTED) break;
            }
        }
        names.sort(String::compareTo);
        return names;
    }

    /** Reads a schematic and reports its size and the blocks this server does not have. The reply runs on the owner. */
    public void inspect(String file, Consumer<List<String>> reply) {
        requireAvailable();
        String name = fileName(file);
        submit(() -> {
            Loaded loaded = read(name);
            post(() -> {
                Schematic schematic = loaded.schematic();
                PaletteResolver.Result palette = PaletteResolver.resolve(schematic, BlockTransform.IDENTITY, platform::resolve);
                List<String> lines = new ArrayList<>();
                lines.add(name + ": " + schematic.format() + " " + schematic.width() + "x" + schematic.height() + "x" + schematic.length()
                        + " | blocks=" + schematic.definedCells() + " | palette=" + schematic.palette().size() + " | unknown-cells=" + palette.unknownCells()
                        + " | block-entities=" + schematic.blockEntities() + (schematic.blockEntities() > 0 ? " (contents not copied)" : "")
                        + " | offset=" + schematic.offsetX() + "," + schematic.offsetY() + "," + schematic.offsetZ());
                schematic.warnings().stream().limit(MAXIMUM_REPORT_LINES).forEach(lines::add);
                palette.report().stream().limit(MAXIMUM_REPORT_LINES).forEach(lines::add);
                reply.accept(lines);
            });
        }, (String failure) -> reply.accept(List.of("Cannot read " + name + ": " + failure)));
    }

    /** Starts a build after its schematic is read and planned. The reply runs on the owner. */
    public void start(Start request, Consumer<String> reply) {
        requireAvailable();
        String name = fileName(request.schematic());
        String id = request.leader();
        BuildCrew.Member leader = crew.member(id).orElseThrow(() -> new IllegalStateException("NPC " + id + " must be active to build"));
        Optional<BuildSession> member = memberOf(id);
        if (member.isPresent()) throw new IllegalStateException("NPC " + id + " already works on build " + member.get().id() + "; cancel it first");
        if (loading.contains(id)) throw new IllegalStateException("Build " + id + " is already loading");
        if (open() + loading.size() >= settings.maximumBuilds()) throw new IllegalStateException("At most " + settings.maximumBuilds() + " builds may run at once (config.toml builds.maximumBuilds)");
        BlockPos origin = request.origin().orElse(BlockPos.containing(leader.position().point()));
        String world = leader.position().world();
        loading.add(id);
        Consumer<String> failed = (String failure) -> { loading.remove(id); reply.accept("Cannot start build " + id + ": " + failure); };
        submit(() -> {
            Loaded loaded = read(name);
            post(() -> {
                if (closed) return;
                PaletteResolver.Result palette;
                try { palette = PaletteResolver.resolve(loaded.schematic(), request.options().transform(), platform::resolve); }
                catch (RuntimeException failure) { failed.accept(PersistedNpcService.concise(failure)); return; }
                submit(() -> {
                    BuildPlan plan = BuildPlanner.plan(loaded.schematic(), palette.palette(), palette.placeable(), request.options(), origin);
                    post(() -> whenHome(id, () -> {
                        loading.remove(id);
                        if (closed) return;
                        if (memberOf(id).isPresent()) { reply.accept("Cannot start build " + id + ": the NPC joined another build meanwhile"); return; }
                        BuildSession previous = sessions.remove(id);
                        if (previous != null) forget(previous);
                        BuildSession session = new BuildSession(id, id, world, origin, name, loaded.hash(), request.options(), request.startedBy(),
                                Instant.now().toString(), settings.maximumUndoEntries(), listener);
                        session.attach(plan);
                        session.bind(platform);
                        sessions.put(id, session);
                        dirty.add(id);
                        StringBuilder summary = new StringBuilder("Started build " + id + ": " + name + " " + plan.size() + " steps from "
                                + plan.minimum().x() + " " + plan.minimum().y() + " " + plan.minimum().z() + " to "
                                + plan.maximum().x() + " " + plan.maximum().y() + " " + plan.maximum().z());
                        if (palette.unknownCells() > 0) summary.append("; ").append(palette.unknownCells()).append(" cells have blocks this server does not know");
                        if (loaded.schematic().blockEntities() > 0) summary.append("; container and sign contents are not copied");
                        if (previous != null) summary.append("; the NPC's previous build record and its undo were discarded");
                        reply.accept(summary.toString());
                        palette.report().stream().limit(MAXIMUM_REPORT_LINES).forEach(reply);
                    }));
                }, failed);
            });
        }, failed);
    }

    /** Starts the build saved in the NPC's {@code animus:builder} definition section. */
    public void startFromDefinition(String npc, Optional<UUID> startedBy, Consumer<String> reply) {
        requireAvailable();
        NpcDefinition definition = runtime.npcs().find(npc).orElseThrow(() -> new IllegalArgumentException("Unknown NPC: " + npc));
        BuilderModule module = BuilderModule.of(definition).orElseThrow(() -> new IllegalArgumentException(
                "NPC " + npc + " has no " + BuilderModule.MODULE + " section; name a schematic: build start <npc-id> <schematic>"));
        start(new Start(npc, module.schematic(), module.origin(), module.options(), startedBy), reply);
    }

    public BuildSession session(String reference) {
        requireAvailable();
        BuildSession session = sessions.get(reference);
        if (session == null) session = memberOf(reference).orElse(null);
        if (session == null) throw new IllegalArgumentException(loading.contains(reference) ? "Build " + reference + " is still loading" : "No build for " + reference);
        if (turns != null && turns.out(session.id())) throw new Busy(session.id());
        return session;
    }

    public String pause(String reference) { BuildSession session = session(reference); String result = session.pause(); session.stopCrew(crew); return result; }
    public String resume(String reference) { return session(reference).resume(); }
    public String cancel(String reference) { BuildSession session = session(reference); String result = session.cancel(); session.stopCrew(crew); return result; }
    public String undo(String reference) {
        BuildSession session = session(reference);
        String result = session.undo();
        session.stopCrew(crew);
        return result;
    }
    public List<String> survey(String reference) { return session(reference).survey(platform); }
    public String team(String reference, boolean add, String npc) {
        BuildSession session = session(reference);
        if (add) {
            if (runtime.npcs().find(npc).isEmpty()) throw new IllegalArgumentException("Unknown NPC: " + npc);
            Optional<BuildSession> other = memberOf(npc);
            if (other.isPresent()) throw new IllegalStateException(npc + " already works on build " + other.get().id());
            session.addMember(npc, settings.maximumTeam());
            return "Added " + npc + " to build " + session.id() + "; team=" + String.join(",", session.team());
        }
        session.removeMember(npc, crew);
        return "Removed " + npc + " from build " + session.id();
    }

    /** A player hands over the stack in their main hand. Only items the build still needs are taken. */
    private String donate(String reference, UUID player) {
        BuildSession session = session(reference);
        P online = living.players().owned(player).orElseThrow(() -> new IllegalStateException("Only an online player can donate"));
        Platform.Held held = platform.held(online).orElseThrow(() -> new IllegalArgumentException("Hold the items to donate in your main hand"));
        session.bind(platform);
        long taken = session.donate(held.item(), held.count());
        if (taken == 0) return "Build " + session.id() + " does not need " + held.item();
        platform.takeHeld(online, (int) taken);
        return "Donated " + taken + " " + held.item() + " to build " + session.id();
    }

    /**
     * {@code donate} from a command, on the player's own owner. A single-owner server answers at once. On a regionized
     * server the held stack is taken first and handed to the build's owner, which keeps what the build still needs; the
     * rest of that same stack goes back to the player on their own region, so no item is ever counted twice or rebuilt.
     */
    public void donateHeld(String reference, UUID player, Consumer<String> reply) {
        if (regions == null) { reply.accept(donate(reference, player)); return; }
        requireAvailable();
        P online = living.players().owned(player).orElseThrow(() -> new IllegalStateException("Only an online player can donate"));
        Platform.Held held = platform.held(online).orElseThrow(() -> new IllegalArgumentException("Hold the items to donate in your main hand"));
        I stack = platform.removeHeld(online);
        Consumer<String> giveBack = (String message) -> {
            if (!living.players().deliver(player, (P back) -> { platform.giveBack(back, stack, held.count()); reply.accept(message); }))
                log.accept("Cannot hand " + held.count() + " " + held.item() + " back to player " + player + ", who left: " + message);
        };
        boolean queued = command(() -> {
            BuildSession session = session(reference);
            session.bind(platform);
            long taken;
            try { taken = session.donate(held.item(), held.count()); }
            catch (IllegalStateException refused) { giveBack.accept(Palette.error("Build: " + refused.getMessage())); return; }
            long rest = held.count() - taken;
            String message = taken == 0 ? "Build " + session.id() + " does not need " + held.item() : "Donated " + taken + " " + held.item() + " to build " + session.id();
            if (rest <= 0) { living.players().deliver(player, (P back) -> reply.accept(message)); return; }
            if (!living.players().deliver(player, (P back) -> { platform.giveBack(back, stack, (int) rest); reply.accept(message); }))
                log.accept("Cannot hand " + rest + " " + held.item() + " back to player " + player + ", who left");
        }, (String refused) -> giveBack.accept(Palette.error("Build: " + refused)));
        if (!queued) giveBack.accept(Palette.error("Build commands busy; try again in a moment"));
    }

    /**
     * Runs a command on the service's owner. A single-owner server runs it now. A regionized server queues it for the
     * global region; a command that finds its build out on a region turn ({@link Busy}) runs again when the build is
     * back, for at most ten seconds. False when the queue is full or builds have stopped.
     */
    public boolean command(Runnable work, Consumer<String> refused) {
        if (regions == null) { work.run(); return true; }
        if (closed) return false;
        if (queuedCommands.incrementAndGet() > MAXIMUM_COMMANDS) { queuedCommands.decrementAndGet(); return false; }
        commands.add(new Command(work, refused, 0));
        return true;
    }

    /**
     * The {@code animus:build} behavior action. Refusals carry their reason back to the behavior; a start answers once
     * loading begins, and its result goes to the server log. On a regionized server the behavior runs on its NPC's
     * region, so the operation is queued for the service's owner, answers {@code queued}, and logs its result.
     */
    public String behavior(String npc, BehaviorPlatform.BuildOperation operation, Optional<String> schematic, Optional<Vec3> origin, Map<String, String> words) {
        if (regions != null) {
            Consumer<String> logged = (String line) -> log.accept("Build " + npc + " (behavior " + operation.name().toLowerCase(java.util.Locale.ROOT) + "): " + line);
            if (!command(() -> {
                try { logged.accept(ownerBehavior(npc, operation, schematic, origin, words)); }
                catch (CapabilityRefusal refused) { logged.accept(refused.getMessage()); }
            }, logged)) throw new CapabilityRefusal(Outcome.Status.UNAVAILABLE, "build-refused", "Build commands are busy; try again");
            JsonObject response = new JsonObject();
            response.addProperty("build", npc);
            response.addProperty("state", "queued");
            return response.toString();
        }
        return ownerBehavior(npc, operation, schematic, origin, words);
    }
    private String ownerBehavior(String npc, BehaviorPlatform.BuildOperation operation, Optional<String> schematic, Optional<Vec3> origin, Map<String, String> words) {
        try {
            String result = switch (operation) {
                case START -> {
                    Consumer<String> reply = (String line) -> log.accept("Build " + npc + " (behavior): " + line);
                    if (schematic.isPresent()) start(new Start(npc, schematic.get(), origin.map(BlockPos::containing), BuildOptions.DEFAULT.with(words), Optional.empty()), reply);
                    else startFromDefinition(npc, Optional.empty(), reply);
                    yield "loading";
                }
                case PAUSE -> { pause(npc); yield "paused"; }
                case RESUME -> { resume(npc); yield "running"; }
                case CANCEL -> { cancel(npc); yield "cancelled"; }
                case UNDO -> { undo(npc); yield "undoing"; }
            };
            JsonObject response = new JsonObject();
            response.addProperty("build", operation == BehaviorPlatform.BuildOperation.START ? npc : session(npc).id());
            response.addProperty("state", result);
            return response.toString();
        } catch (IllegalArgumentException invalid) {
            throw new CapabilityRefusal(Outcome.Status.INVALID, "build-invalid", invalid.getMessage());
        } catch (IllegalStateException refused) {
            throw new CapabilityRefusal(Outcome.Status.UNAVAILABLE, "build-refused", refused.getMessage());
        }
    }

    /** The open or saved build an NPC belongs to. */
    public Optional<BuildSession> memberOf(String npc) {
        for (BuildSession session : sessions.values()) {
            BuildSession.State state = stateOf(session);
            if ((state.open() || state == BuildSession.State.UNDOING) && teamOf(session).contains(npc)) return Optional.of(session);
        }
        return Optional.empty();
    }

    // A build's state as the owner may read it: the session itself at home, or what it was when it left on a turn.

    private boolean out(BuildSession session) { return turns != null && turns.out(session.id()); }
    private BuildSession.State stateOf(BuildSession session) { View view = out(session) ? views.get(session.id()) : null; return view == null ? session.state() : view.state(); }
    private List<String> teamOf(BuildSession session) { View view = out(session) ? views.get(session.id()) : null; return view == null ? session.team() : view.team(); }
    private String statusOf(BuildSession session) { View view = out(session) ? views.get(session.id()) : null; return view == null ? session.status() : view.status(); }
    private long open() { return sessions.values().stream().filter((BuildSession session) -> stateOf(session).open()).count(); }
    /** Runs the work now when the build is at home, otherwise again on a later owner tick. */
    private void whenHome(String id, Runnable work) {
        if (turns != null && turns.out(id)) { post(() -> whenHome(id, work)); return; }
        work.run();
    }

    // Owner tick

    private void tick() {
        if (closed) return;
        tick++;
        // Work posted while this runs waits for the next tick, so a build still out cannot spin here.
        for (int pending = completions.size(); pending > 0; pending--) {
            Runnable completion = completions.poll();
            if (completion == null) break;
            try { completion.run(); }
            catch (RuntimeException failure) { log.accept("Build work failed: " + PersistedNpcService.concise(failure)); }
        }
        if (turns != null) for (String id : turns.collect(System.nanoTime())) {
            views.remove(id);
            BuildSession session = sessions.get(id);
            if (session != null && paused) session.stopCrew(crew);
        }
        runCommands();
        BuildSettings current = settings;
        if (!current.enabled() || !living.available()) { pause(); return; }
        paused = false;
        restoreWhenReady();
        BuildSession.Budget budget = new BuildSession.Budget(current.placementsPerTick(), current.checksPerTick(), current.undoPerTick());
        BuildSession.Context context = new BuildSession.Context(platform, crew, protection, budget);
        List<BuildSession> order = new ArrayList<>(sessions.values());
        if (!order.isEmpty()) {
            rotation = (rotation + 1) % order.size();
            for (int index = 0; index < order.size(); index++) {
                BuildSession session = order.get((index + rotation) % order.size());
                if (turns != null) { send(session, current); continue; }
                turn(session, context);
            }
        }
        if (urgentSave) { urgentSave = false; lastSave = Long.MIN_VALUE / 2; }
        if (!dirty.isEmpty() && tick - lastSave >= (long) current.saveSeconds() * TICKS_PER_SECOND) save(false);
    }
    private void turn(BuildSession session, BuildSession.Context context) {
        try { session.tick(context); }
        catch (RuntimeException failure) {
            log.accept("Build " + session.id() + " stopped after an error: " + PersistedNpcService.concise(failure));
            session.fail("stopped after an error: " + PersistedNpcService.concise(failure));
            session.stopCrew(crew);
        }
    }
    /**
     * Regionized servers: sends a build that has work for one turn to the region that owns where it works now. Its own
     * budget applies to that turn. A build whose place cannot take a turn waits with the reason.
     */
    private void send(BuildSession session, BuildSettings current) {
        if (turns.out(session.id()) || !session.wantsTurn()) return;
        BlockPos focus = session.focus(crew);
        View view = new View(session.state(), session.team(), session.status());
        BuildSession.Context context = new BuildSession.Context(platform, crew, protection,
                new BuildSession.Budget(current.placementsPerTick(), current.checksPerTick(), current.undoPerTick()));
        views.put(session.id(), view);
        String refused = turns.send(session.id(), session.world(), focus, System.nanoTime(), () -> turn(session, context));
        if (refused == null) return;
        views.remove(session.id());
        session.unreachable(refused);
    }

    /** Owner tick: queued commands, each once; a command whose build is out waits for a later tick. */
    private void runCommands() {
        for (int pending = queuedCommands.get(); pending > 0; pending--) {
            Command command = commands.poll();
            if (command == null) break;
            try { command.work().run(); queuedCommands.decrementAndGet(); }
            catch (Busy busy) {
                if (command.waited() < COMMAND_WAIT_TICKS) commands.add(new Command(command.work(), command.refused(), command.waited() + 1));
                else { queuedCommands.decrementAndGet(); refuse(command, busy.getMessage() + " for too long; try again"); }
            } catch (RuntimeException failure) { queuedCommands.decrementAndGet(); refuse(command, PersistedNpcService.concise(failure)); }
        }
    }
    private void refuse(Command command, String why) {
        try { command.refused().accept(why); }
        catch (RuntimeException ignored) { /* A failing reply cannot stop the other commands. */ }
    }

    /**
     * Builds were turned off or the NPC runtime went away: crews stop where they stand and progress is saved. Open builds
     * keep their state and continue from it once builds are on again. A build out on a turn stops when it comes back.
     */
    private void pause() {
        if (paused) return;
        paused = true;
        for (BuildSession session : sessions.values()) if (!out(session)) session.stopCrew(crew);
        if (!dirty.isEmpty()) save(false);
    }

    /** Reads the saved builds the first time builds can run, so a server started with builds off restores them when they are turned on. */
    private void restoreWhenReady() {
        if (restored || unavailable().isPresent()) return;
        restored = true;
        try { worker.execute(this::restoreSaved); }
        catch (RejectedExecutionException stopped) { restored = false; }
    }

    private void save(boolean now) {
        lastSave = tick;
        for (String id : List.copyOf(dirty)) {
            BuildSession session = sessions.get(id);
            if (session == null) { dirty.remove(id); continue; }
            // A build out on a region turn is saved after it comes back.
            if (out(session) || !now && saving.contains(id)) continue;
            dirty.remove(id);
            List<DocumentStore.Generated> documents = documents(session);
            if (now) {
                String failure = write(id, session, documents);
                if (failure != null) log.accept("Cannot save build " + id + ": " + failure);
                continue;
            }
            saving.add(id);
            try {
                files.execute(() -> {
                    String failure = write(id, session, documents);
                    post(() -> {
                        saving.remove(id);
                        if (failure == null) return;
                        saveFailures++;
                        log.accept("Cannot save build " + id + ": " + failure);
                        whenHome(id, () -> { session.journal().forgetSaved(); dirty.add(id); });
                    });
                });
            } catch (RejectedExecutionException full) { saving.remove(id); dirty.add(id); session.journal().forgetSaved(); }
        }
    }
    private List<DocumentStore.Generated> documents(BuildSession session) {
        List<DocumentStore.Generated> documents = new ArrayList<>();
        for (int chunk : session.journal().dirtyChunks())
            documents.add(new DocumentStore.Generated(chunkPath(session.id(), chunk), JsonDocuments.write(session.journal().chunk(chunk))));
        session.journal().saved();
        documents.add(new DocumentStore.Generated(STATE + "/" + session.id() + ".json", JsonDocuments.write(session.toJson())));
        return documents;
    }
    /** Commits in batches that keep each journal well under its size limit; the state document goes last. */
    private String write(String id, BuildSession session, List<DocumentStore.Generated> documents) {
        synchronized (saveLock) {
            if (finalSaved) return null;
            try {
                List<DocumentStore.Generated> batch = new ArrayList<>();
                long bytes = 0;
                for (DocumentStore.Generated document : documents) {
                    if (!batch.isEmpty() && bytes + document.content().length() > BATCH_CHARACTERS) {
                        runtime.documents().commitGenerated(batch);
                        batch = new ArrayList<>();
                        bytes = 0;
                    }
                    batch.add(document);
                    bytes += document.content().length();
                }
                if (!batch.isEmpty()) runtime.documents().commitGenerated(batch);
                return null;
            } catch (IOException | RuntimeException failure) { return PersistedNpcService.concise(failure); }
        }
    }
    private static String chunkPath(String id, int chunk) { return STATE + "/" + id + "/undo-" + String.format("%05d", chunk) + ".json"; }

    /** Deletes a replaced build's saved files on the file lane. */
    private void forget(BuildSession previous) {
        dirty.remove(previous.id());
        int chunks = previous.journal().chunks();
        String id = previous.id();
        try {
            files.execute(() -> {
                try {
                    for (int chunk = 0; chunk < chunks; chunk++) runtime.documents().deleteGenerated(chunkPath(id, chunk));
                } catch (IOException | RuntimeException failure) { log.accept("Cannot delete the old undo record of build " + id + ": " + PersistedNpcService.concise(failure)); }
            });
        } catch (RejectedExecutionException full) { log.accept("Cannot delete the old undo record of build " + id + " now; its files stay under " + STATE); }
    }

    // Loading

    @FunctionalInterface private interface Work { void run() throws IOException, SchematicException; }

    private void submit(Work work, Consumer<String> failed) {
        try {
            worker.execute(() -> {
                try { work.run(); }
                catch (IOException | SchematicException | RuntimeException failure) {
                    String message = failure instanceof SchematicException || failure instanceof IllegalArgumentException ? failure.getMessage() : PersistedNpcService.concise(failure);
                    post(() -> failed.accept(message));
                }
            });
        } catch (RejectedExecutionException stopped) { failed.accept("the build planner has stopped"); }
    }
    private void post(Runnable work) { if (!closed) completions.add(work); }

    private Loaded read(String name) throws IOException, SchematicException {
        Path path = runtime.documents().resolve(SCHEMATICS + "/" + name);
        if (!Files.isRegularFile(path)) throw new IOException("no schematic named " + name + " in the " + SCHEMATICS + " folder");
        long size = Files.size(path);
        if (size > settings.maximumFileBytes()) throw new IOException(name + " is " + size + " bytes; the limit is " + settings.maximumFileBytes() + " (config.toml builds.maximumFileBytes)");
        byte[] bytes;
        try (InputStream input = Files.newInputStream(path)) { bytes = input.readNBytes(settings.maximumFileBytes() + 1); }
        if (bytes.length > settings.maximumFileBytes()) throw new IOException(name + " grew past the size limit while it was read");
        SchematicReader.Limits limits = new SchematicReader.Limits(NbtReader.Limits.DEFAULT, settings.maximumVolume(), settings.maximumPalette(), settings.maximumSide());
        return new Loaded(SchematicReader.read(name, bytes, limits), sha256(bytes));
    }
    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException missing) { throw new IllegalStateException(missing); }
    }
    static String fileName(String text) {
        if (text == null || !FILE.matcher(text).matches())
            throw new IllegalArgumentException("Schematic must be a file name ending in .schem, .nbt or .schematic, such as tower.schem");
        return text;
    }

    /** Worker: reads every saved build, then hands them to the owner, which re-plans the open ones. */
    private void restoreSaved() {
        List<JsonObject> saved = new ArrayList<>();
        List<List<JsonObject>> journals = new ArrayList<>();
        try {
            Path folder = runtime.documents().resolve(STATE);
            if (!Files.isDirectory(folder)) return;
            List<Path> records = new ArrayList<>();
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder, "*.json")) {
                for (Path entry : entries) if (records.size() < 1024) records.add(entry);
            }
            records.sort(Path::compareTo);
            for (Path record : records) {
                try {
                    JsonObject json = JsonDocuments.object(JsonDocuments.read(record));
                    String id = json.get("id").getAsString();
                    if (!record.getFileName().toString().equals(id + ".json")) throw new IOException("its id does not match its file name");
                    int entries = json.getAsJsonObject("undo").get("entries").getAsInt();
                    List<JsonObject> chunks = new ArrayList<>();
                    for (int chunk = 0; chunk < (entries + UndoJournal.CHUNK - 1) / UndoJournal.CHUNK; chunk++)
                        chunks.add(JsonDocuments.object(JsonDocuments.read(runtime.documents().resolve(chunkPath(id, chunk)))));
                    saved.add(json);
                    journals.add(chunks);
                } catch (IOException | RuntimeException invalid) {
                    String file = record.getFileName().toString();
                    post(() -> log.accept("Saved build " + file + " cannot be read and was left as it is: " + PersistedNpcService.concise(invalid)));
                }
            }
        } catch (IOException | RuntimeException failure) {
            post(() -> log.accept("Saved builds cannot be listed: " + PersistedNpcService.concise(failure)));
            return;
        }
        post(() -> {
            for (int index = 0; index < saved.size(); index++) {
                JsonObject json = saved.get(index);
                try {
                    JsonObject undo = json.getAsJsonObject("undo");
                    UndoJournal journal = UndoJournal.restore(Math.max(undo.get("capacity").getAsInt(), undo.get("entries").getAsInt()), undo.get("full").getAsBoolean(), journals.get(index));
                    BuildSession session = BuildSession.restore(json, journal, listener);
                    session.bind(platform);
                    sessions.put(session.id(), session);
                    if (session.state().open()) replan(session);
                } catch (RuntimeException invalid) {
                    log.accept("Saved build " + json.get("id") + " cannot be restored and was left as it is: " + PersistedNpcService.concise(invalid));
                }
            }
            if (!saved.isEmpty()) log.accept("Restored " + sessions.size() + " saved builds");
        });
    }

    /** Rebuilds the plan of a restored open build from its schematic; a changed or missing file stops it with the reason. */
    private void replan(BuildSession session) {
        loading.add(session.id());
        Consumer<String> failed = (String failure) -> whenHome(session.id(), () -> { loading.remove(session.id()); session.fail("cannot resume: " + failure + "; undo still works"); });
        submit(() -> {
            Loaded loaded = read(session.schematic());
            if (!loaded.hash().equals(session.schematicHash())) throw new IOException(session.schematic() + " changed since the build started");
            post(() -> {
                PaletteResolver.Result palette;
                try { palette = PaletteResolver.resolve(loaded.schematic(), session.options().transform(), platform::resolve); }
                catch (RuntimeException failure) { failed.accept(PersistedNpcService.concise(failure)); return; }
                submit(() -> {
                    BuildPlan plan = BuildPlanner.plan(loaded.schematic(), palette.palette(), palette.placeable(), session.options(), session.origin());
                    post(() -> whenHome(session.id(), () -> {
                        loading.remove(session.id());
                        try { session.attach(plan); }
                        catch (IllegalArgumentException mismatch) { session.fail("cannot resume: " + mismatch.getMessage() + "; undo still works"); }
                    }));
                }, failed);
            });
        }, failed);
    }

    @Override public void close() {
        if (closed || worker == null) { closed = true; return; }
        try { settingsListener.close(); } catch (Exception ignored) { /* Removing a listener cannot fail. */ }
        try { if (ticker != null) ticker.close(); }
        catch (Exception ignored) { /* the tick checks closed */ }
        if (turns == null) {
            // Scaffolding never outlives the server: it comes down now, and the saved build no longer lists it.
            BuildSession.Context context = new BuildSession.Context(platform, crew, protection, new BuildSession.Budget(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE));
            for (BuildSession session : sessions.values()) {
                session.stopCrew(crew);
                try { session.dismantle(context); }
                catch (RuntimeException failure) { log.accept("Cannot take down the scaffolding of build " + session.id() + ": " + PersistedNpcService.concise(failure)); }
            }
        } else {
            // No region takes work while the plugin stops, so standing scaffolding stays listed and comes down after the restart.
            List<String> running = turns.close(SHUTDOWN_WAIT_NANOS);
            for (String id : running) {
                BuildSession session = sessions.remove(id);
                dirty.remove(id);
                if (session != null) log.accept("Build " + id + " was still on its region at shutdown; its last saved record stays");
            }
            views.clear();
            for (Command command; (command = commands.poll()) != null; ) refuse(command, "builds stopped");
        }
        // A lane write still queued is skipped once the final save is done, so its chunks are written again here.
        for (String id : saving) {
            BuildSession session = sessions.get(id);
            if (session != null) { session.journal().forgetSaved(); dirty.add(id); }
        }
        try { save(true); }
        catch (RuntimeException failure) { log.accept("Cannot save builds at shutdown: " + PersistedNpcService.concise(failure)); }
        synchronized (saveLock) { finalSaved = true; }
        closed = true;
        completions.clear();
        if (worker != null) worker.shutdownNow();
    }

    // Owner-side views for the session

    private final class Crew implements BuildCrew {
        @Override public Optional<Member> member(String npc) {
            Optional<NpcDefinition> definition = runtime.npcs().find(npc);
            if (definition.isEmpty()) return Optional.empty();
            return living.active(definition.get().uuid()).map((NpcRuntime.ActiveNpc active) -> {
                Hitbox box = active.state().bounds();
                return new Member(npc, definition.get().uuid(), active.state().position(), box.max().x() - box.min().x(), box.max().y() - box.min().y());
            });
        }
        @Override public boolean here(String npc) { return living.here(npc); }
        @Override public boolean routing(String npc) { return living.routing(npc); }
        @Override public void walk(String npc, WorldPosition goal, double speed) { living.navigate(npc, goal, MovementProfile.Mode.WALK, speed); }
        @Override public void stop(String npc) {
            try { living.stopNavigation(npc); }
            catch (RuntimeException inactive) { /* nothing moves it */ }
        }
        @Override public boolean relocate(String npc, WorldPosition to) { return living.relocate(npc, to); }
        @Override public void face(String npc, WorldPosition at) {
            try { living.lookAt(npc, at); }
            catch (RuntimeException ignored) { /* facing is cosmetic */ }
        }
        @Override public void swing(String npc) { living.swing(npc); }
        @Override public void terrainChanged(String world, BlockPos at) { living.terrainChanged(world, at); }
        @Override public void hold(String npc, Optional<String> item) {
            try { living.setEquipment(npc, EquipmentSlot.MAIN_HAND, platform.stack(item)); }
            catch (RuntimeException ignored) { /* holding is cosmetic */ }
        }
    }

    private final class Listener implements BuildSession.Listener {
        @Override public void paused(BuildSession session, String reason) { publish(session, TriggerEvent.BuildPaused::new); }
        @Override public void finished(BuildSession session) { publish(session, TriggerEvent.BuildFinished::new); log.accept("Build " + session.id() + " finished"); }
        /** On the owner, or on the region running the build's turn. */
        @Override public void changed(BuildSession session) {
            boolean urgent = !session.state().open() || session.state() == BuildSession.State.PAUSED;
            dirty.add(session.id());
            if (urgent) urgentSave = true;
        }
        /** Reaches the player on their own owner, which may be another region. */
        @Override public void tell(Optional<UUID> player, String message) {
            player.ifPresent((UUID id) -> living.players().deliver(id, (P online) -> platform.tell(online, message)));
        }
        private void publish(BuildSession session, Function<UUID, TriggerEvent> event) {
            TriggerSink sink = triggers.get();
            if (sink == null || !sink.available()) return;
            for (String npc : session.team()) runtime.npcs().find(npc).ifPresent((NpcDefinition definition) -> sink.publish(event.apply(definition.uuid())));
        }
    }
}
