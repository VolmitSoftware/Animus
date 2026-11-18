package art.arcane.animus.core.behavior.builtin;

import static art.arcane.animus.core.behavior.builtin.BuiltinArguments.*;

import art.arcane.animus.api.AnimusApi;
import art.arcane.animus.api.ApiRange;
import art.arcane.animus.api.Capabilities;
import art.arcane.animus.api.Capabilities.Descriptor;
import art.arcane.animus.api.Capabilities.Invocation;
import art.arcane.animus.api.Capabilities.Key;
import art.arcane.animus.api.Capabilities.Kind;
import art.arcane.animus.api.Diagnostic;
import art.arcane.animus.api.ModuleContext;
import art.arcane.animus.api.ModuleManifest;
import art.arcane.animus.api.NpcHandle;
import art.arcane.animus.api.Outcome;
import art.arcane.animus.api.Platform;
import art.arcane.animus.api.TrustedModule;
import art.arcane.animus.api.Version;
import art.arcane.animus.core.behavior.BehaviorFlags;
import art.arcane.animus.core.quest.QuestControl;
import art.arcane.animus.core.behavior.BehaviorProgram;
import art.arcane.animus.core.behavior.BehaviorValues;
import art.arcane.animus.core.behavior.BehaviorState;
import art.arcane.animus.core.behavior.ServerNpc;
import art.arcane.animus.core.behavior.builtin.BehaviorPlatform.AppearanceChange;
import art.arcane.animus.core.behavior.builtin.BehaviorPlatform.BuildOperation;
import art.arcane.animus.core.behavior.builtin.BehaviorPlatform.Audience;
import art.arcane.animus.core.behavior.builtin.BehaviorPlatform.ChoiceOption;
import art.arcane.animus.core.behavior.builtin.BehaviorPlatform.CombatOperation;
import art.arcane.animus.core.behavior.builtin.BehaviorPlatform.CommandSource;
import art.arcane.animus.core.behavior.builtin.BehaviorPlatform.NpcStatus;
import art.arcane.animus.core.behavior.builtin.BehaviorPlatform.ParticleEffect;
import art.arcane.animus.core.behavior.builtin.BehaviorPlatform.SoundEffect;
import art.arcane.animus.core.config.BuiltinActionSettings;
import art.arcane.animus.core.extension.BuiltinPolicy;
import art.arcane.animus.core.extension.CapabilityRefusal;
import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.NpcAttributes;
import art.arcane.animus.core.platform.MinecraftIds;
import art.arcane.animus.core.navigation.MovementProfile;
import art.arcane.animus.core.recording.Playback;
import art.arcane.animus.core.storage.JsonDocuments;
import art.arcane.animus.core.virtual.Vec3;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.IntUnaryOperator;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The {@code animus:} capability set, registered through the same registry and schema path as trusted modules.
 * Handlers parse and classify on the module worker; all world and player work crosses {@link OwnerActionQueue}.
 */
public final class BuiltinCapabilities implements TrustedModule, BuiltinPolicy, AutoCloseable {
    public static final String NAMESPACE = "animus";
    private static final ModuleManifest MANIFEST = new ModuleManifest(NAMESPACE, new Version(1, 0, 0),
            new ApiRange(AnimusApi.API_VERSION.major(), AnimusApi.API_VERSION.major()), Set.of(Platform.values()), List.of());
    private static final double CHOICE_RANGE = 64;
    private static final int MAXIMUM_LOGGED_COMMAND = 1024;
    private static final String PLAYER_TOKEN = "{player}", PLAYER_NAME_TOKEN = "{player_name}";
    private static final int MAXIMUM_RANDOM_STAGE_BOUND = 1_000_000;
    private static final String FLAG_STATE = BehaviorFlags.FLAGS, QUEST_STATE = BehaviorFlags.QUESTS;
    private static final Key NAVIGATE = new Key(NAMESPACE, "navigate"), PLAY_RECORDING = new Key(NAMESPACE, "play-recording");
    private static final int DEFAULT_WAIT_TICKS = 1200, MINIMUM_WAIT_TICKS = 20, MAXIMUM_WAIT_TICKS = 12_000;
    private static final long OWNER_TICK_MILLIS = 50, LAGGING_OWNER_ALLOWANCE = 2, WAIT_DEADLINE_SLACK_MILLIS = 5000;
    private static final String ANIMATION_ID = "[a-z0-9_-]{1,64}:[a-z0-9_-]{1,64}(/[a-z0-9_-]{1,64}){0,14}";
    private static final long MAXIMUM_EXPIRY_SECONDS = 31_536_000L;
    private static final int MAXIMUM_ITEMS = 6400;
    /** Capabilities that need an NPC body or NPC presence; a server behavior's run refuses them as no-npc. */
    private static final Set<String> NPC_ONLY = Set.of("say", "look", "navigate", "stop-navigation", "play-recording", "resume-patrol", "set-appearance", "set-equipment", "set-attribute", "combat-control",
            "build", "play-animation", "stop-animation", "npc-state", "player-in-range", "choices");
    /** Whether a capability needs an NPC body or NPC presence, so a server behavior cannot run it. */
    public static boolean npcOnly(Key key) { return key.namespace().equals(NAMESPACE) && NPC_ONLY.contains(key.name()); }
    private static final String PERMISSION = "[A-Za-z0-9_-]+(\\.[A-Za-z0-9_*-]+)*";
    /** The longest a speech bubble shows: one minute. */
    static final int MAXIMUM_BUBBLE_TICKS = 1200;
    /** At most 16 nodes a command grants, for at most an hour after it runs. */
    static final int MAXIMUM_COMMAND_PERMISSIONS = 16, MAXIMUM_PERMISSION_TICKS = 72_000;

    /** Decides what a capability may do when previewed and whether it needs the platform owner. */
    private enum Effect {
        STATE_READ(false), STATE_WRITE(false), OWNER_READ(true), PRESENTATION(true), WORLD(true), PRIVILEGED(true);
        final boolean owner;
        Effect(boolean owner) { this.owner = owner; }
    }
    private record StateSlot(BehaviorState.Subject subject, String name, BehaviorProgram.ScopedVariable declaration, BehaviorProgram.Type type) {}
    private static final BehaviorProgram.Value UNSET_FLAG = new BehaviorProgram.Value(BehaviorProgram.Type.BOOLEAN, false);
    private static final BehaviorProgram.Value UNSET_QUEST = stage(0);
    /** {@code subject}, when set, is the NPC an {@code npc} argument names, which the capability acts on instead of the run's. */
    private record Call(Invocation invocation, JsonObject arguments, NpcHandle subject) {
        NpcHandle npc() { return subject != null ? subject : invocation.npc(); }
        Optional<UUID> player() { return invocation.player(); }
    }
    @FunctionalInterface private interface Body<T> { CompletionStage<T> run(Call call); }
    @FunctionalInterface private interface Semantics { void check(JsonObject arguments, List<Diagnostic> problems); }
    private record Spec(Key key, Effect effect, Kind kind, String description, String schema, Semantics semantics, Body<String> action, Body<Boolean> condition) {}

    private final BehaviorPlatform platform;
    private final BehaviorState state;
    private final Object advances = new Object();
    private volatile BuiltinActionSettings settings;
    private final Consumer<String> log;
    private final IntUnaryOperator randomBelow;
    private final LongSupplier clock;
    private final OwnerActionQueue queue;
    private final NavigationWaits waits;
    private final PendingChoices choices;
    private final Map<Key, Spec> specs = new LinkedHashMap<>();
    private volatile boolean closed;
    private AutoCloseable ticks;
    /** Hears what an NPC said with animus:say, on the NPC's owner; quick and nonblocking. */
    @FunctionalInterface public interface SpeechListener { void spoke(NpcHandle npc, String plain); }
    private volatile SpeechListener speech = (NpcHandle npc, String plain) -> {};
    /** Connect during startup; npc_speech triggers hear speech through it. */
    public void speech(SpeechListener listener) { speech = Objects.requireNonNull(listener); }

    public BuiltinCapabilities(BehaviorPlatform platform, BehaviorState state, DialogueChoiceSink choiceSink,
                               BuiltinActionSettings settings, Consumer<String> log) {
        this(platform, state, choiceSink, settings, log, (int bound) -> ThreadLocalRandom.current().nextInt(bound));
    }

    /** {@code randomBelow} returns a uniform integer in [0, bound) and may be called from any module worker. */
    BuiltinCapabilities(BehaviorPlatform platform, BehaviorState state, DialogueChoiceSink choiceSink,
                        BuiltinActionSettings settings, Consumer<String> log, IntUnaryOperator randomBelow) {
        this(platform, state, choiceSink, settings, log, randomBelow, System::currentTimeMillis);
    }

    /** {@code clock} is epoch milliseconds for flag expiry. */
    BuiltinCapabilities(BehaviorPlatform platform, BehaviorState state, DialogueChoiceSink choiceSink,
                        BuiltinActionSettings settings, Consumer<String> log, IntUnaryOperator randomBelow, LongSupplier clock) {
        this.randomBelow = Objects.requireNonNull(randomBelow);
        this.clock = Objects.requireNonNull(clock);
        this.platform = Objects.requireNonNull(platform);
        this.state = Objects.requireNonNull(state);
        this.settings = Objects.requireNonNull(settings);
        this.log = Objects.requireNonNull(log);
        queue = new OwnerActionQueue(new OwnerActionQueue.Limits(settings.actionsPerTick(), settings.actionsPerNpcPerTick(), settings.queuedActions()), new OwnerActionQueue.Router() {
            @Override public boolean dispatch(NpcHandle npc, Runnable work, Consumer<String> refused) { return platform.onNpcOwner(npc, work, refused); }
            @Override public boolean player(UUID player, Runnable work, Consumer<String> refused) { return platform.onPlayerOwner(player, work, refused); }
            @Override public boolean global(Runnable work, Consumer<String> refused) { return platform.onGlobalOwner(work, refused); }
        });
        waits = new NavigationWaits(settings.navigationWaits(), platform);
        choices = new PendingChoices(settings.pendingChoices(), choiceSink, System::nanoTime,
                failure -> this.log.accept("Dialogue choice listener failed: " + failure.getClass().getSimpleName()));
        registerConditions();
        registerActions();
    }

    @Override public ModuleManifest manifest() { return MANIFEST; }

    @Override public synchronized void enable(ModuleContext context) {
        Capabilities.Registrar registrar = context.registrations();
        for (Spec spec : specs.values()) {
            Descriptor descriptor = new Descriptor(spec.key, spec.kind, spec.description, spec.schema, Set.of(Platform.values()), spec.effect == Effect.PRIVILEGED);
            if (spec.kind == Kind.ACTION) registrar.action(descriptor, invocation -> invoke(spec, spec.action, invocation));
            else registrar.condition(descriptor, invocation -> invoke(spec, spec.condition, invocation));
        }
        if (!closed && platform.unavailable().isEmpty()) ticks = platform.ownerTicks(this::ownerTick);
    }

    private void ownerTick() {
        queue.tick();
        waits.tick();
    }

    @Override public void disable() { close(); }

    /** Revokes new work immediately and fails anything still queued. Safe to call more than once. */
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        queue.close();
        waits.close();
        if (ticks == null) return;
        try { ticks.close(); }
        catch (Exception ignored) { /* The platform owner ends with the process; there is nothing left to release. */ }
        ticks = null;
    }

    @Override public Optional<String> unavailable(Key key) {
        Spec spec = specs.get(key);
        if (spec == null || !spec.effect.owner) return Optional.empty();
        if (closed) return Optional.of("built-in actions are stopping");
        return platform.unavailable();
    }

    @Override public List<Diagnostic> validate(Key key, String argumentsJson) {
        Spec spec = specs.get(key);
        if (spec == null) return List.of();
        JsonObject arguments;
        try { arguments = JsonDocuments.object(argumentsJson); }
        catch (IOException invalid) { return List.of(problem("$", "invalid-json", "Expected one JSON object")); }
        List<Diagnostic> problems = new ArrayList<>();
        spec.semantics.check(arguments, problems);
        return List.copyOf(problems);
    }

    /** A waiting navigate or recording playback is bounded by its own timeout instead of the short module deadline. */
    @Override public OptionalLong deadlineMillis(Key key, String argumentsJson) {
        if (!key.equals(NAVIGATE) && !key.equals(PLAY_RECORDING)) return OptionalLong.empty();
        try {
            JsonObject arguments = JsonDocuments.object(argumentsJson);
            if (!flag(arguments, "wait", false)) return OptionalLong.empty();
            return OptionalLong.of(timeoutTicks(arguments) * OWNER_TICK_MILLIS * LAGGING_OWNER_ALLOWANCE + WAIT_DEADLINE_SLACK_MILLIS);
        } catch (IOException | RuntimeException invalid) { return OptionalLong.empty(); }
    }

    public boolean commandAllowed(String behavior) { return settings.commandAllowed(behavior); }

    public boolean rawTextAllowed(String behavior) { return settings.rawTextAllowed(behavior); }

    /**
     * A hotload of {@code [behaviors.actions]}: the command allow-lists and the default choice lifetime apply to the next
     * action; the queue, wait and choice budgets are adopted by their owners on the next tick or prompt. Lowered caps keep
     * queued actions, waits and prompts and refuse new ones as busy until usage drops under them.
     */
    public void settings(BuiltinActionSettings next) {
        settings = Objects.requireNonNull(next);
        queue.limits(new OwnerActionQueue.Limits(next.actionsPerTick(), next.actionsPerNpcPerTick(), next.queuedActions()));
        waits.maximum(next.navigationWaits());
        choices.maximum(next.pendingChoices());
    }

    /**
     * Owner only. Handles a click from the client. Forged, stale, foreign and out-of-range clicks are rejected,
     * and a rejected click publishes nothing.
     */
    /** Whether a dialogue choice waits for {@code player} to click it. */
    public boolean choiceWaiting(UUID player) { return choices.waiting(player); }

    public PendingChoices.Selection selectChoice(UUID player, String token, String option) {
        return choices.select(player, token, option, npc -> platform.unavailable().isEmpty() && !closed
                && platform.npcStatus(npc).spawned() && platform.playerInRange(npc, Optional.of(player), CHOICE_RANGE));
    }

    private <T> CompletionStage<T> invoke(Spec spec, Body<T> body, Invocation invocation) throws IOException {
        JsonObject arguments = JsonDocuments.object(invocation.argumentsJson());
        NpcHandle subject = null;
        if (NPC_SUBJECT.contains(spec.key.name()) && arguments.has("npc") && arguments.get("npc").isJsonPrimitive()) {
            try { subject = new NpcHandle(UUID.fromString(arguments.get("npc").getAsString())); }
            catch (IllegalArgumentException invalid) { throw new CapabilityRefusal(Outcome.Status.INVALID, "npc-uuid", "npc must be an NPC UUID"); }
        }
        Call call = new Call(invocation, arguments, subject);
        if (npcOnly(spec.key)) requireNpc(call);
        if (invocation.preview() && refusedInPreview(spec, call.arguments))
            throw new CapabilityRefusal(Outcome.Status.UNAVAILABLE, "preview-refused", spec.key + " is not available in a preview");
        return body.run(call);
    }

    private static boolean refusedInPreview(Spec spec, JsonObject arguments) {
        return switch (spec.effect) {
            case STATE_READ, OWNER_READ -> false;
            case PRESENTATION -> text(arguments, "audience").orElse("trigger").equals("nearby") || arguments.has("player")
                    || !text(arguments, "to").orElse("trigger").equals("trigger");
            case STATE_WRITE, WORLD, PRIVILEGED -> true;
        };
    }

    /** The platform can become unavailable after admission, so this rechecks on the owner immediately before the work. */
    private <T> CompletionStage<T> onOwner(Call call, Supplier<T> work) {
        return queue.submit(call.npc(), call.invocation.cancellation(), () -> {
            Optional<String> reason = platform.unavailable();
            if (reason.isPresent()) throw new CapabilityRefusal(Outcome.Status.UNAVAILABLE, "platform-unavailable", reason.get());
            return work.get();
        });
    }

    /** Player-addressed work of a server run, and the player-only actions, run where the player is owned. */
    private <T> CompletionStage<T> onPlayer(Call call, UUID player, Supplier<T> work) {
        return queue.submit(OwnerActionQueue.Route.player(call.npc(), player), call.invocation.cancellation(), () -> available(work));
    }
    /** Broadcasts and console work run on the global owner. */
    private <T> CompletionStage<T> onGlobal(Call call, Supplier<T> work) {
        return queue.submit(OwnerActionQueue.Route.global(call.npc()), call.invocation.cancellation(), () -> available(work));
    }
    /** An NPC run keeps its NPC's owner; a server run goes to the player's owner. */
    private <T> CompletionStage<T> onOwnerOrPlayer(Call call, UUID player, Supplier<T> work) {
        return server(call) ? onPlayer(call, player, work) : onOwner(call, work);
    }
    private <T> T available(Supplier<T> work) {
        Optional<String> reason = platform.unavailable();
        if (reason.isPresent()) throw new CapabilityRefusal(Outcome.Status.UNAVAILABLE, "platform-unavailable", reason.get());
        return work.get();
    }
    private static boolean server(Call call) { return ServerNpc.is(call.npc()); }
    private static void requireNpc(Call call) {
        if (server(call)) throw new CapabilityRefusal(Outcome.Status.INVALID, "no-npc", "A server behavior has no NPC for this capability");
    }

    private void register(String name, Effect effect, Kind kind, String description, String schema, Semantics semantics, Body<String> action, Body<Boolean> condition) {
        Key key = new Key(NAMESPACE, name);
        specs.put(key, new Spec(key, effect, kind, description, schema, semantics, action, condition));
    }
    private void action(String name, Effect effect, String description, String schema, Semantics semantics, Body<String> body) {
        register(name, effect, Kind.ACTION, description, schema, semantics, body, null);
    }
    private void condition(String name, Effect effect, String description, String schema, Semantics semantics, Body<Boolean> body) {
        register(name, effect, Kind.CONDITION, description, schema, semantics, null, body);
    }

    private static final String COORDINATES = number("x", -30_000_000, 30_000_000) + "," + number("y", -30_000_000, 30_000_000) + "," + number("z", -30_000_000, 30_000_000);
    private static final String AUDIENCE = choice("audience", "trigger", "nearby") + "," + number("radius", 1, 64);
    private static final String STATE = string("name", 64) + "," + choice("scope", "global", "npc", "player", "relationship");
    /** Names an online player by UUID instead of the triggering player. */
    private static final String PLAYER = string("player", 36);
    /** Capabilities that act on another NPC an {@code npc} argument names, by UUID, instead of the run's NPC. */
    private static final java.util.Set<String> NPC_SUBJECT = java.util.Set.of("look", "animate", "spawn-particle", "play-sound", "explode", "look-close");
    private static final String NPC = string("npc", 36);
    private static final String[] BOSS_COLORS = {"pink", "blue", "red", "green", "yellow", "purple", "white"};
    private static final String[] BOSS_STYLES = {"solid", "segmented_6", "segmented_10", "segmented_12", "segmented_20"};
    /** An item to give or test: its key, amount, name, lore and enchantments, mapping enchantment keys to levels. */
    private static final String ITEM = string("item", 128) + "," + integer("amount", 1, MAXIMUM_ITEMS) + "," + string("name", 256) + "," + texts("lore", 1, 16, 256)
            + ",\"enchantments\":{\"type\":\"object\",\"minProperties\":1,\"maxProperties\":32,\"additionalProperties\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":255}}";
    /**
     * An item to give or test, written out or as stack: one map of the same keys, such as an item a read-inventory
     * result lists (its slot is ignored). An amount next to stack replaces the stack's.
     */
    private static final String GIFT = ITEM + ",\"stack\":{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{" + ITEM + "," + integer("slot", 0, 255) + ","
            + string("data", BehaviorPlatform.MAXIMUM_ITEM_DATA) + "}," + required("item") + "}";
    private static final String GIFT_REQUIRED = "\"anyOf\":[{" + required("item") + "},{" + required("stack") + "}]";
    /** The player a sound or particle is placed at when that is not the player who hears or sees it. */
    private static final String AT_PLAYER = string("atPlayer", 36);
    /** Names another NPC by UUID instead of the behavior's own. */
    private static final String NAMED_NPC = string("npc", 36);
    private static final String ANY_VALUE = "\"value\":{\"description\":\"Any JSON value: true, false, a number, text, a list or a map\"}";

    private void registerConditions() {
        condition("has-permission", Effect.OWNER_READ, "True when the triggering player holds a permission. False when there is no triggering player.",
                schema(string("permission", 128), required("permission")),
                (arguments, problems) -> matches(arguments, "permission", PERMISSION, problems),
                call -> {
                    if (call.player().isEmpty()) return onOwner(call, () -> false);
                    UUID player = call.player().get();
                    String node = text(call.arguments, "permission").orElseThrow();
                    return onOwnerOrPlayer(call, player, () -> platform.hasPermission(player, node));
                });
        condition("can-afford", Effect.OWNER_READ, "True when the triggering player, or the player named by player, holds the money, experience levels and items of the cost. False when there is no triggering player.",
                ChargeArguments.SCHEMA, (arguments, problems) -> { ChargeArguments.check(arguments, problems); checkPlayer(arguments, problems); },
                call -> {
                    if (call.player().isEmpty() && !call.arguments.has("player")) return onOwner(call, () -> false);
                    UUID player = target(call);
                    BehaviorPlatform.Cost cost = ChargeArguments.parse(call.arguments);
                    return onPlayer(call, player, () -> platform.affordable(player, cost));
                });
        condition("commands-allowed", Effect.STATE_READ, "True when the running behavior is on the command allow-list, so animus:command would run. Checks it before taking a cost for a command.",
                schema(""), (arguments, problems) -> {},
                call -> CompletableFuture.completedFuture(call.invocation.behavior().map(settings::commandAllowed).orElse(false)));
        condition("npc-state", Effect.OWNER_READ, "Compares the NPC's spawned state and current health.",
                schema(bool("spawned") + "," + number("minimumHealth", 0, 1_000_000) + "," + number("maximumHealth", 0, 1_000_000),
                        "\"anyOf\":[" + "{" + required("spawned") + "},{" + required("minimumHealth") + "},{" + required("maximumHealth") + "}]"),
                (arguments, problems) -> {},
                call -> onOwner(call, () -> npcStateMatches(platform.npcStatus(call.npc()), call.arguments)));
        condition("player-in-range", Effect.OWNER_READ, "True when the triggering player, or any player with anyPlayer, is within the radius.",
                schema(number("radius", 1, 64) + "," + bool("anyPlayer"), required("radius")),
                (arguments, problems) -> {},
                call -> {
                    double radius = call.arguments.get("radius").getAsDouble();
                    boolean anyone = flag(call.arguments, "anyPlayer", false);
                    if (!anyone && call.player().isEmpty()) return onOwner(call, () -> false);
                    Optional<UUID> player = anyone ? Optional.empty() : call.player();
                    return onOwner(call, () -> platform.playerInRange(call.npc(), player, radius));
                });
        condition("flag-state", Effect.STATE_READ, "Compares a flag with value (true by default) by exact JSON equality. An unset or expired flag is false: it equals false and nothing else. A player or relationship flag belongs to the triggering player, or the player named by player; an NPC or relationship flag to this NPC, or the NPC named by npc.",
                schema(STATE + "," + ANY_VALUE + "," + PLAYER + "," + NAMED_NPC, required("name")),
                (arguments, problems) -> { BuiltinArguments.checkFlagValue(arguments, problems); checkFlagPlayer(arguments, problems); },
                call -> CompletableFuture.completedFuture(flagEquals(slot(call, FLAG_STATE), flagValue(call.arguments))));
        condition("quest-state", Effect.STATE_READ, "Compares a quest stage. An unset quest is stage zero.",
                schema(STATE + "," + integer("equals", 0, 1_000_000_000_000L) + "," + integer("atLeast", 0, 1_000_000_000_000L) + "," + integer("atMost", 0, 1_000_000_000_000L),
                        required("name"), "\"anyOf\":[" + "{" + required("equals") + "},{" + required("atLeast") + "},{" + required("atMost") + "}]"),
                (arguments, problems) -> matches(arguments, "name", IDENTIFIER, problems),
                call -> CompletableFuture.completedFuture(questMatches((Long) read(slot(call, QUEST_STATE), UNSET_QUEST).value(), call.arguments)));
    }

    private void registerActions() {
        action("say", Effect.PRESENTATION, "Sends dialogue text to the triggering player, the player named by player, or nearby players; {player_name} becomes that player's name.",
                schema(string("text", 1024) + "," + AUDIENCE + "," + PLAYER + "," + integer("bubbleTicks", 1, MAXIMUM_BUBBLE_TICKS) + "," + bool("chat"), required("text")),
                (arguments, problems) -> { plainText(arguments, "text", problems); checkPlayer(arguments, problems); },
                call -> {
                    Audience audience = audience(call);
                    String written = text(call.arguments, "text").orElseThrow();
                    Optional<UUID> named = written.contains(PLAYER_NAME_TOKEN) ? Optional.of(target(call)) : Optional.empty();
                    int bubble = call.arguments.has("bubbleTicks") ? call.arguments.get("bubbleTicks").getAsInt() : 0;
                    boolean chat = flag(call.arguments, "chat", true);
                    return onOwner(call, () -> {
                        String spoken = withPlayerName(written, named);
                        // A bubble shows one line over the NPC to everyone who sees it, so it holds the first 256 characters.
                        if (bubble > 0) platform.bubble(call.npc(), spoken.length() > 256 ? spoken.substring(0, 256) : spoken, bubble);
                        if (!call.invocation.preview()) speech.spoke(call.npc(), art.arcane.animus.core.text.RichText.plain(spoken));
                        return delivered(chat ? platform.say(call.npc(), audience, spoken) : 0);
                    });
                });
        action("choices", Effect.WORLD, "Presents clickable options to the triggering player and publishes the validated click.",
                schema(string("id", 64) + "," + string("prompt", 512) + "," + integer("lifetimeSeconds", 1, 600)
                                + ",\"options\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":8,\"items\":" + schema(string("id", 32) + "," + string("text", 128) + "," + string("color", 16) + "," + string("hover", 256)
                                + "," + choice("style", "bold", "italic", "underlined", "plain"), required("id", "text")) + "}",
                        required("id", "prompt", "options")),
                BuiltinArguments::checkChoices, this::presentChoices);
        action("command", Effect.PRIVILEGED, "Runs a console or player command; {player} becomes the triggering player's UUID and {player_name} their name, and placeholders are filled for that player. A player command may hold permissions while it runs, and for permissionTicks after, or with asOperator run with the player made an operator for that one command. Allowed only for behaviors on the TOML allow-list, and never in a preview.",
                schema(string("command", 1024) + "," + choice("as", "console", "player") + "," + bool("asOperator") + "," + PLAYER + "," + texts("permissions", 1, MAXIMUM_COMMAND_PERMISSIONS, 128)
                        + "," + integer("permissionTicks", 0, MAXIMUM_PERMISSION_TICKS), required("command")),
                BuiltinArguments::checkCommand, this::runCommand);
        action("navigate", Effect.WORLD, "Starts a route to a position in the NPC's world, or follows a reusable route. Completes when requested, or on arrival with wait.",
                schema(string("route", 64) + "," + string("anchor", 32) + "," + COORDINATES + "," + choice("mode", "walk", "swim", "fly") + "," + number("speed", 1, 5) + "," + bool("wait") + "," + integer("timeoutTicks", MINIMUM_WAIT_TICKS, MAXIMUM_WAIT_TICKS),
                        "\"oneOf\":[{" + required("route") + "},{" + required("anchor") + "},{" + required("x", "y", "z") + "}]"),
                BuiltinArguments::checkNavigate, this::navigate);
        action("stop-navigation", Effect.WORLD, "Stops the NPC's current route and any route it is following.", schema(""),
                (arguments, problems) -> {},
                call -> onOwner(call, () -> result("stopped", platform.stopNavigation(call.npc()))));
        action("play-recording", Effect.WORLD, "Replaces the NPC's route with a recording it walks to and plays once, in a loop or back and forth; without playback, the recording's own playback, else loop. With wait, a once playback completes when it ends.",
                schema(string("recording", 64) + "," + choice("playback", "once", "loop", "pingpong") + "," + bool("wait") + "," + integer("timeoutTicks", MINIMUM_WAIT_TICKS, MAXIMUM_WAIT_TICKS),
                        required("recording")),
                BuiltinArguments::checkPlayRecording, this::playRecording);
        action("resume-patrol", Effect.WORLD, "Restarts the route or recording the NPC's definition declares; a stopped recording continues where it stopped. Reports resumed or nothing-to-resume.",
                schema(""), (arguments, problems) -> {},
                call -> onOwner(call, () -> status(platform.resumePatrol(call.npc()) ? "resumed" : "nothing-to-resume")));
        action("look", Effect.WORLD, "Turns the NPC, or another NPC named by npc, toward a position or the triggering player.",
                schema(COORDINATES + ",\"target\":{\"const\":\"player\"}," + NPC, "\"oneOf\":[{" + required("target") + "},{" + required("x", "y", "z") + "}]"),
                BuiltinCapabilities::checkNpc,
                this::look);
        action("pose", Effect.WORLD, "Turns the NPC to one of the named poses in its definition's poses.",
                schema(string("pose", 32), required("pose")),
                (arguments, problems) -> matches(arguments, "pose", art.arcane.animus.core.npc.NamedPoses.NAME, problems),
                call -> {
                    String pose = text(call.arguments, "pose").orElseThrow();
                    return onOwner(call, () -> { platform.pose(call.npc(), pose); return "{}"; });
                });
        action("animate", Effect.PRESENTATION, "Shows a vanilla animation on the NPC, or another NPC named by npc: an arm swing, the hurt flash or a critical hit once, or a pose or held-item use that lasts until something changes it.",
                schema(choice("animation", "swing", "swing_offhand", "hurt", "crit", "magic_crit", "wake", "sneak", "sleep", "sit", "stand", "use_item", "stop_using_item") + "," + NPC, required("animation")),
                (arguments, problems) -> {
                    checkNpc(arguments, problems);
                    if (text(arguments, "animation").flatMap(art.arcane.animus.core.npc.NpcAnimation::parse).isEmpty())
                        problems.add(problem("$.animation", "animation-unknown", "animation must be one of the vanilla animations"));
                },
                call -> {
                    art.arcane.animus.core.npc.NpcAnimation animation = art.arcane.animus.core.npc.NpcAnimation.parse(text(call.arguments, "animation").orElseThrow()).orElseThrow();
                    return onOwner(call, () -> { platform.animate(call.npc(), animation); return "{}"; });
                });
        action("set-appearance", Effect.WORLD, "Changes the NPC's displayed name or skin until the runtime restarts or its definition changes.",
                schema(string("name", 16) + "," + string("skin", 64) + "," + bool("reset"),
                        "\"anyOf\":[{" + required("name") + "},{" + required("skin") + "},{" + required("reset") + "}]"),
                BuiltinArguments::checkAppearance,
                call -> {
                    AppearanceChange change = new AppearanceChange(text(call.arguments, "name"), text(call.arguments, "skin"), flag(call.arguments, "reset", false));
                    return onOwner(call, () -> { platform.setAppearance(call.npc(), change); return "{}"; });
                });
        action("set-equipment", Effect.WORLD, "Shows an item in an equipment slot, or clears the slot when no item is given. Lasts until the runtime restarts or the definition changes.",
                schema(choice("slot", "main_hand", "off_hand", "head", "chest", "legs", "feet", "body", "saddle") + "," + string("item", 128), required("slot")),
                (arguments, problems) -> {
                    if (arguments.has("item")) matches(arguments, "item", MinecraftIds.PATTERN, problems);
                },
                call -> {
                    EquipmentSlot slot = EquipmentSlot.valueOf(text(call.arguments, "slot").orElseThrow().toUpperCase(Locale.ROOT));
                    Optional<String> item = text(call.arguments, "item").map(MinecraftIds::normalize);
                    return onOwner(call, () -> { platform.setEquipment(call.npc(), slot, item); return "{}"; });
                });
        action("set-attribute", Effect.WORLD, "Sets one vanilla attribute of the NPC, such as scale or movement_speed, or with reset returns it to the definition's value."
                        + " Lasts until the runtime restarts or the definition changes.",
                schema(string("attribute", 64) + "," + "\"value\":{\"type\":\"number\"}," + bool("reset"),
                        required("attribute"), "\"anyOf\":[{" + required("value") + "},{" + required("reset") + "}]"),
                BuiltinArguments::checkAttribute,
                call -> {
                    String attribute = NpcAttributes.id(text(call.arguments, "attribute").orElseThrow()).orElseThrow();
                    OptionalDouble value = flag(call.arguments, "reset", false) ? OptionalDouble.empty() : decimal(call.arguments, "value");
                    return onOwner(call, () -> { platform.setAttribute(call.npc(), attribute, value); return "{}"; });
                });
        action("play-sound", Effect.PRESENTATION, "Plays a sound at the NPC, or with at: player at the player (atPlayer names another player to play it at), for the triggering player or nearby players. Server behaviors play at the player.",
                schema(string("sound", 128) + "," + number("volume", 0, 4) + "," + number("pitch", 0.5, 2) + "," + AUDIENCE + "," + choice("at", "npc", "player") + "," + AT_PLAYER + "," + NPC + "," + PLAYER, required("sound")),
                (arguments, problems) -> { matches(arguments, "sound", MinecraftIds.PATTERN, problems); checkPlayer(arguments, problems); checkAtPlayer(arguments, problems); checkNpc(arguments, problems); },
                call -> {
                    SoundEffect sound = new SoundEffect(MinecraftIds.normalize(text(call.arguments, "sound").orElseThrow()), (float) decimal(call.arguments, "volume").orElse(1), (float) decimal(call.arguments, "pitch").orElse(1));
                    if (atPlayer(call)) {
                        UUID anchor = anchor(call);
                        Audience audience = audience(call);
                        return onPlayer(call, anchor, () -> delivered(platform.playSoundAtPlayer(anchor, audience, sound)));
                    }
                    Audience audience = audience(call);
                    return onOwner(call, () -> delivered(platform.playSound(call.npc(), audience, sound)));
                });
        action("spawn-particle", Effect.PRESENTATION, "Shows a particle effect at the NPC, or with at: player at the player (atPlayer names another player to show it at), to the triggering player or nearby players, at the middle of the body or height blocks above the feet. Server behaviors show it at the player.",
                schema(string("particle", 128) + "," + integer("count", 1, 64) + "," + number("spread", 0, 8) + "," + number("height", -16, 16) + "," + AUDIENCE + "," + choice("at", "npc", "player") + "," + AT_PLAYER + "," + NPC + "," + PLAYER, required("particle")),
                (arguments, problems) -> { matches(arguments, "particle", MinecraftIds.PATTERN, problems); checkPlayer(arguments, problems); checkAtPlayer(arguments, problems); checkNpc(arguments, problems); },
                call -> {
                    int count = call.arguments.has("count") ? call.arguments.get("count").getAsInt() : 1;
                    ParticleEffect particle = new ParticleEffect(MinecraftIds.normalize(text(call.arguments, "particle").orElseThrow()), count, decimal(call.arguments, "spread").orElse(0), decimal(call.arguments, "height"));
                    if (atPlayer(call)) {
                        UUID anchor = anchor(call);
                        Audience audience = audience(call);
                        return onPlayer(call, anchor, () -> delivered(platform.spawnParticleAtPlayer(anchor, audience, particle)));
                    }
                    Audience audience = audience(call);
                    return onOwner(call, () -> delivered(platform.spawnParticle(call.npc(), audience, particle)));
                });
        action("combat-control", Effect.WORLD, "Engages or disengages the NPC's combat, attacks or releases a target, or guards a participant (no target stops guarding; player is the triggering player). Needs enabled combat settings; protection still applies.",
                schema(choice("operation", "enable", "disable", "target", "release", "guard") + "," + string("target", 36), required("operation")),
                BuiltinArguments::checkCombat,
                call -> {
                    CombatOperation operation = CombatOperation.valueOf(text(call.arguments, "operation").orElseThrow().toUpperCase(Locale.ROOT));
                    Optional<String> named = text(call.arguments, "target");
                    Optional<UUID> target = named.isEmpty() ? Optional.empty()
                            : Optional.of(named.get().equals("player") ? requirePlayer(call) : UUID.fromString(named.get()));
                    return onOwner(call, () -> { platform.combat(call.npc(), operation, target); return "{}"; });
                });
        action("open-shop", Effect.WORLD, "Opens a shop for the triggering player.",
                schema(string("shop", 256) + "," + PLAYER, required("shop")),
                (arguments, problems) -> { matches(arguments, "shop", art.arcane.animus.core.shop.ShopDocument.ID, problems); checkPlayer(arguments, problems); },
                call -> openScreen(call, BehaviorPlatform.ScreenKind.SHOP, "shop"));
        action("open-gui", Effect.WORLD, "Opens a chest GUI (kind inventory, the default), holographic menu, shop or, with Gloss installed, a Gloss menu for the triggering player.",
                schema(string("gui", 256) + "," + choice("kind", "inventory", "menu", "shop", "gloss") + "," + PLAYER, required("gui")),
                (arguments, problems) -> { matches(arguments, "gui", screenKind(arguments) == BehaviorPlatform.ScreenKind.GLOSS
                        ? art.arcane.animus.core.gui.GuiBinding.GLOSS_ID : art.arcane.animus.core.shop.ShopDocument.ID, problems); checkPlayer(arguments, problems); },
                call -> openScreen(call, screenKind(call.arguments), "gui"));
        action("build", Effect.WORLD, "Starts, pauses, resumes, cancels or undoes the NPC's build. A start names a schematic in the schematics folder, or uses the NPC's saved builder section; it builds at x, y, z or where the NPC stands. Protection still applies.",
                schema(choice("operation", "start", "pause", "resume", "cancel", "undo") + "," + string("schematic", 132) + "," + COORDINATES + ","
                        + integer("rotation", 0, 270) + "," + choice("mirror", "none", "x", "z") + "," + choice("anchor", "center", "corner", "schematic") + ","
                        + choice("pattern", "spiral", "reversespiral", "linear", "reverselinear") + "," + choice("materials", "creative", "donate") + ","
                        + bool("excavate") + "," + bool("ignoreAir") + "," + bool("ignoreLiquids"), required("operation")),
                BuiltinArguments::checkBuild,
                call -> {
                    BuildOperation operation = BuildOperation.valueOf(text(call.arguments, "operation").orElseThrow().toUpperCase(Locale.ROOT));
                    Optional<String> schematic = text(call.arguments, "schematic");
                    Optional<Vec3> origin = call.arguments.has("x") ? Optional.of(point(call.arguments)) : Optional.empty();
                    Map<String, String> options = new LinkedHashMap<>();
                    for (String name : List.of("rotation", "mirror", "anchor", "pattern", "materials", "excavate", "ignoreAir", "ignoreLiquids"))
                        if (call.arguments.has(name)) options.put(name.toLowerCase(Locale.ROOT), call.arguments.get(name).getAsString());
                    return onOwner(call, () -> platform.build(call.npc(), operation, schematic, origin, Map.copyOf(options)));
                });
        action("play-animation", Effect.WORLD, "Plays an animation of the NPC's rig on a layer (0-3). Higher layers blend over lower ones. Fires animation_finished when a once or hold clip reaches its end.",
                schema(string("animation", 256) + "," + number("speed", 0.25, 4) + "," + choice("loop", "once", "loop", "pingpong", "hold") + "," + integer("layer", 0, 3), required("animation")),
                (arguments, problems) -> matches(arguments, "animation", ANIMATION_ID, problems),
                call -> {
                    String animation = text(call.arguments, "animation").orElseThrow();
                    double speed = decimal(call.arguments, "speed").orElse(1);
                    Optional<String> loop = text(call.arguments, "loop");
                    int layer = call.arguments.has("layer") ? call.arguments.get("layer").getAsInt() : 0;
                    return onOwner(call, () -> { platform.playAnimation(call.npc(), animation, speed, loop, layer); return "{}"; });
                });
        action("stop-animation", Effect.WORLD, "Stops one animation layer, or every played layer, and returns to the rig's idle animation.",
                schema(integer("layer", 0, 3) + "," + integer("fadeTicks", 0, 40)),
                (arguments, problems) -> {},
                call -> {
                    java.util.OptionalInt layer = call.arguments.has("layer") ? java.util.OptionalInt.of(call.arguments.get("layer").getAsInt()) : java.util.OptionalInt.empty();
                    int fade = call.arguments.has("fadeTicks") ? call.arguments.get("fadeTicks").getAsInt() : 0;
                    return onOwner(call, () -> { platform.stopAnimation(call.npc(), layer, fade); return "{}"; });
                });
        action("set-flag", Effect.STATE_WRITE, "Sets a flag to value (true by default: any JSON value), optionally for expireSeconds; clear: true unsets it. A player or relationship flag belongs to the triggering player, or the player named by player; an NPC or relationship flag to this NPC, or the NPC named by npc.",
                schema(STATE + "," + ANY_VALUE + "," + integer("expireSeconds", 1, MAXIMUM_EXPIRY_SECONDS) + ",\"clear\":{\"const\":true}," + PLAYER + "," + NAMED_NPC, required("name")),
                (arguments, problems) -> { BuiltinArguments.checkSetFlag(arguments, problems); checkFlagPlayer(arguments, problems); },
                call -> {
                    StateSlot slot = slot(call, FLAG_STATE);
                    long now = clock.getAsLong();
                    if (flag(call.arguments, "clear", false)) write(slot, UNSET_FLAG, OptionalLong.of(now));
                    else write(slot, flagValue(call.arguments), call.arguments.has("expireSeconds") ? OptionalLong.of(now + call.arguments.get("expireSeconds").getAsLong() * 1000L) : OptionalLong.empty());
                    return CompletableFuture.completedFuture("{}");
                });
        action("set-quest-state", Effect.STATE_WRITE, "Sets a quest stage to value, or to a random stage from zero to below random.",
                schema(STATE + "," + integer("value", 0, 1_000_000_000_000L) + "," + integer("random", 1, MAXIMUM_RANDOM_STAGE_BOUND),
                        required("name"), "\"oneOf\":[{" + required("value") + "},{" + required("random") + "}]"),
                (arguments, problems) -> matches(arguments, "name", IDENTIFIER, problems),
                call -> {
                    long value = call.arguments.has("random") ? randomBelow.applyAsInt(call.arguments.get("random").getAsInt()) : call.arguments.get("value").getAsLong();
                    write(slot(call, QUEST_STATE), stage(value));
                    return CompletableFuture.completedFuture("{}");
                });
        action("advance-quest-state", Effect.STATE_WRITE, "Advances a quest stage, by one unless by says otherwise, without passing maximum.",
                schema(STATE + "," + integer("by", 1, 1_000_000) + "," + integer("maximum", 0, 1_000_000_000_000L), required("name")),
                (arguments, problems) -> matches(arguments, "name", IDENTIFIER, problems),
                call -> {
                    long by = call.arguments.has("by") ? call.arguments.get("by").getAsLong() : 1;
                    long maximum = call.arguments.has("maximum") ? call.arguments.get("maximum").getAsLong() : 1_000_000_000_000L;
                    JsonObject response = new JsonObject();
                    response.addProperty("value", advance(slot(call, QUEST_STATE), by, maximum));
                    return CompletableFuture.completedFuture(response.toString());
                });
        registerPlayerActions();
        registerQuests();
    }

    /** Animus quest actions and the quest-status condition; they refuse until {@link #quests(QuestControl)} connects the quest service. */
    private void registerQuests() {
        String quest = string("quest", 129);
        action("start-quest", Effect.WORLD, "Starts an Animus quest for the triggering player, or the player named by player. quest is namespace:name, or a name in this behavior's namespace; force: true skips its requirements and repeat rule. Reports stage.",
                schema(quest + "," + bool("force") + "," + PLAYER, required("quest")), BuiltinCapabilities::checkQuest,
                call -> { UUID player = target(call); String id = questId(call); boolean force = flag(call.arguments, "force", false);
                    return onPlayer(call, player, () -> quested(() -> result("stage", (int) quests.start(player, id, force)))); });
        action("advance-quest", Effect.WORLD, "Moves the player's active Animus quest to its next stage, or to stage (from 1). Past the last stage it completes the quest with its rewards. Reports stage.",
                schema(quest + "," + integer("stage", 1, 33) + "," + PLAYER, required("quest")), BuiltinCapabilities::checkQuest,
                call -> { UUID player = target(call); String id = questId(call);
                    java.util.OptionalInt stage = call.arguments.has("stage") ? java.util.OptionalInt.of(call.arguments.get("stage").getAsInt()) : java.util.OptionalInt.empty();
                    return onPlayer(call, player, () -> quested(() -> result("stage", (int) quests.advance(player, id, stage)))); });
        action("complete-quest", Effect.WORLD, "Completes the player's active Animus quest, with its rewards unless rewards is false. Reports stage.",
                schema(quest + "," + bool("rewards") + "," + PLAYER, required("quest")), BuiltinCapabilities::checkQuest,
                call -> { UUID player = target(call); String id = questId(call); boolean rewards = flag(call.arguments, "rewards", true);
                    return onPlayer(call, player, () -> quested(() -> result("stage", (int) quests.complete(player, id, rewards)))); });
        action("quest-objective", Effect.WORLD, "Adds amount (1 by default) to a custom objective of the player's active Animus quest stage. Reports count.",
                schema(quest + "," + string("objective", 64) + "," + integer("amount", 1, 100_000) + "," + PLAYER, required("quest", "objective")),
                (arguments, problems) -> { checkQuest(arguments, problems); matches(arguments, "objective", IDENTIFIER, problems); },
                call -> { UUID player = target(call); String id = questId(call); String objective = text(call.arguments, "objective").orElseThrow(); int amount = amount(call.arguments);
                    return onPlayer(call, player, () -> quested(() -> result("count", quests.objective(player, id, objective, amount)))); });
        condition("quest-status", Effect.STATE_READ, "Compares where the player is in an Animus quest: none, active, ready (waiting for the turn-in NPC) or finished.",
                schema(quest + "," + choice("status", "none", "active", "ready", "finished") + "," + PLAYER, required("quest", "status")), BuiltinCapabilities::checkQuest,
                call -> { UUID player = target(call); String id = questId(call);
                    return CompletableFuture.completedFuture(quested(() -> quests.status(player, id)).equals(text(call.arguments, "status").orElseThrow())); });
    }

    private static final String QUEST_ID = "([a-z0-9_-]{1,64}:)?[a-z0-9][a-z0-9_-]{0,63}";
    private volatile QuestControl quests = QuestControl.ABSENT;

    /** Connects the quest service the quest actions and condition use. */
    public void quests(QuestControl control) { quests = Objects.requireNonNull(control); }

    private static void checkQuest(JsonObject arguments, List<Diagnostic> problems) { matches(arguments, "quest", QUEST_ID, problems); checkPlayer(arguments, problems); }

    /** A quest name without a namespace belongs to the calling behavior's namespace. */
    private static String questId(Call call) {
        String quest = text(call.arguments, "quest").orElseThrow();
        if (quest.indexOf(':') >= 0) return quest;
        String behavior = call.invocation.behavior().orElseThrow(() -> new CapabilityRefusal(Outcome.Status.INVALID, "no-behavior-namespace", "Name the quest as namespace:name; this call has no behavior"));
        return behavior.substring(0, behavior.indexOf(':')) + ":" + quest;
    }

    private static <T> T quested(Supplier<T> work) {
        try { return work.get(); }
        catch (QuestControl.Refused refused) {
            Outcome.Status status = switch (refused.code()) {
                case "quests-unavailable", "player-offline" -> Outcome.Status.UNAVAILABLE;
                case "quest-unknown", "objective-unknown" -> Outcome.Status.NOT_FOUND;
                case "stage-invalid" -> Outcome.Status.INVALID;
                default -> Outcome.Status.CONFLICT;
            };
            throw new CapabilityRefusal(status, refused.code(), refused.getMessage());
        }
    }

    /** Actions that address players rather than an NPC. Server behaviors use them; NPC behaviors may too. */
    private void registerPlayerActions() {
        action("message", Effect.PRESENTATION, "Sends formatted text without a speaker: to the triggering player (trigger, the default) or the player named by player, every online player (all), the players in the NPC's or triggering player's world (world), the players within radius of the NPC (nearby), or the console. {player_name} becomes the player's name.",
                schema(string("text", 1024) + "," + choice("to", "trigger", "all", "world", "nearby", "console") + "," + number("radius", 1, 64) + "," + PLAYER, required("text")),
                (arguments, problems) -> { plainText(arguments, "text", problems); checkPlayer(arguments, problems);
                    if (arguments.has("player") && !text(arguments, "to").orElse("trigger").equals("trigger")) problems.add(problem("$.player", "message-player", "player is used only when to is trigger"));
                    if (arguments.has("radius") && !text(arguments, "to").orElse("trigger").equals("nearby")) problems.add(problem("$.radius", "message-radius", "radius is used only when to is nearby"));
                    if (arguments.has("radius") && arguments.get("radius").isJsonPrimitive() && arguments.get("radius").getAsJsonPrimitive().isNumber()
                            && !(arguments.get("radius").getAsDouble() >= 1 && arguments.get("radius").getAsDouble() <= 64))
                        problems.add(problem("$.radius", "message-radius", "radius must be 1..64 blocks")); },
                this::message);
        action("title", Effect.PRESENTATION, "Shows a title and subtitle to the triggering player, or the player named by player.",
                schema(string("title", 256) + "," + string("subtitle", 256) + "," + integer("fadeIn", 0, 72000) + "," + integer("stay", 0, 72000) + "," + integer("fadeOut", 0, 72000) + "," + PLAYER,
                        "\"anyOf\":[{" + required("title") + "},{" + required("subtitle") + "}]"),
                (arguments, problems) -> { if (arguments.has("title")) displayText(arguments, "title", problems); if (arguments.has("subtitle")) displayText(arguments, "subtitle", problems); checkPlayer(arguments, problems); },
                call -> {
                    UUID player = target(call);
                    BehaviorPlatform.Title title = new BehaviorPlatform.Title(text(call.arguments, "title"), text(call.arguments, "subtitle"),
                            ticks(call.arguments, "fadeIn", 10), ticks(call.arguments, "stay", 70), ticks(call.arguments, "fadeOut", 20));
                    return onPlayer(call, player, () -> delivered(platform.title(player, named(title, player))));
                });
        action("actionbar", Effect.PRESENTATION, "Shows action bar text to the triggering player, or the player named by player.",
                schema(string("text", 256) + "," + PLAYER, required("text")),
                (arguments, problems) -> { displayText(arguments, "text", problems); checkPlayer(arguments, problems); },
                call -> {
                    UUID player = target(call);
                    String written = text(call.arguments, "text").orElseThrow();
                    return onPlayer(call, player, () -> delivered(platform.actionbar(player, withPlayerName(written, written.contains(PLAYER_NAME_TOKEN) ? Optional.of(player) : Optional.empty()))));
                });
        action("bossbar", Effect.PRESENTATION, "Shows the triggering player, or the player named by player, a boss bar by its id, or updates it: title, progress from 0 to 1, color and style. operation remove takes it away. A new bar starts empty, white and solid.",
                schema(choice("operation", "show", "remove") + "," + string("id", 64) + "," + string("title", 256) + "," + number("progress", 0, 1) + "," + choice("color", BOSS_COLORS)
                        + "," + choice("style", BOSS_STYLES) + "," + PLAYER, required("id")),
                (arguments, problems) -> {
                    matches(arguments, "id", "[a-z0-9_.-]{1,64}", problems);
                    if (arguments.has("title")) displayText(arguments, "title", problems);
                    if (text(arguments, "operation").filter("remove"::equals).isPresent() && List.of("title", "progress", "color", "style").stream().anyMatch(arguments::has))
                        problems.add(problem("$.operation", "bossbar-remove", "a removed bar takes no title, progress, color or style"));
                    checkPlayer(arguments, problems);
                },
                call -> {
                    UUID player = target(call);
                    Optional<String> title = text(call.arguments, "title").map((String written) -> withPlayerName(written, written.contains(PLAYER_NAME_TOKEN) ? Optional.of(player) : Optional.empty()));
                    BehaviorPlatform.BossBar bar = new BehaviorPlatform.BossBar(text(call.arguments, "id").orElseThrow(), text(call.arguments, "operation").filter("remove"::equals).isPresent(),
                            title, decimal(call.arguments, "progress"), text(call.arguments, "color"), text(call.arguments, "style"));
                    return onPlayer(call, player, () -> delivered(platform.bossBar(player, bar)));
                });
        action("sidebar", Effect.PRESENTATION, "Shows the triggering player, or the player named by player, a sidebar with a title and up to 15 lines, top first. operation add adds lines below the last sidebar shown, set_line replaces line (1-15), and remove takes it away.",
                schema(choice("operation", "show", "add", "set_line", "remove") + "," + string("title", 128) + "," + texts("lines", 0, 15, 128) + "," + integer("line", 1, 15) + "," + PLAYER),
                (arguments, problems) -> {
                    String operation = text(arguments, "operation").orElse("show");
                    if (operation.equals("remove") && (arguments.has("title") || arguments.has("lines"))) problems.add(problem("$.operation", "sidebar-remove", "a removed sidebar takes no title or lines"));
                    if (operation.equals("show") && !arguments.has("title")) problems.add(problem("$.title", "sidebar-title", "a shown sidebar needs a title"));
                    if (operation.equals("set_line") != arguments.has("line")) problems.add(problem("$.line", "sidebar-line", "line goes with set_line only, which needs it"));
                    if (operation.equals("set_line") && (!arguments.has("lines") || arguments.get("lines").isJsonArray() && arguments.getAsJsonArray("lines").size() != 1))
                        problems.add(problem("$.lines", "sidebar-line", "set_line takes one line of text"));
                    if (arguments.has("title")) displayText(arguments, "title", problems);
                    checkPlayer(arguments, problems);
                },
                call -> {
                    UUID player = target(call);
                    String operation = text(call.arguments, "operation").orElse("show");
                    if (operation.equals("remove")) {
                        sidebars.remove(player);
                        return onPlayer(call, player, () -> delivered(platform.sidebar(player, Optional.empty())));
                    }
                    List<String> lines = new ArrayList<>();
                    if (call.arguments.has("lines")) for (JsonElement line : call.arguments.getAsJsonArray("lines")) lines.add(line.getAsString());
                    BehaviorPlatform.Sidebar shown = sidebars.get(player);
                    BehaviorPlatform.Sidebar sidebar = switch (operation) {
                        case "add" -> {
                            List<String> all = new ArrayList<>(shown == null ? List.of() : shown.lines());
                            for (String line : lines) if (all.size() < 15) all.add(line);
                            yield new BehaviorPlatform.Sidebar(text(call.arguments, "title").orElse(shown == null ? "" : shown.title()), all);
                        }
                        case "set_line" -> {
                            List<String> all = new ArrayList<>(shown == null ? List.of() : shown.lines());
                            int line = call.arguments.get("line").getAsInt();
                            while (all.size() < line) all.add("");
                            all.set(line - 1, lines.getFirst());
                            yield new BehaviorPlatform.Sidebar(text(call.arguments, "title").orElse(shown == null ? "" : shown.title()), all);
                        }
                        default -> new BehaviorPlatform.Sidebar(text(call.arguments, "title").orElseThrow(), lines);
                    };
                    if (sidebars.size() >= MAXIMUM_SIDEBARS && !sidebars.containsKey(player)) sidebars.clear();
                    sidebars.put(player, sidebar);
                    return onPlayer(call, player, () -> delivered(platform.sidebar(player, Optional.of(sidebar))));
                });
        action("ray-trace", Effect.OWNER_READ, "Finds the block the triggering player, or the player named by player, looks at within range blocks (1-128, default 50) into result: location, written x,y,z,world, and block, or nothing for either when the player looks at no block.",
                schema(number("range", 1, 128) + "," + PLAYER), BuiltinArguments::checkPlayer,
                call -> {
                    UUID player = target(call);
                    double range = decimal(call.arguments, "range").orElse(50);
                    return onPlayer(call, player, () -> {
                        Optional<BehaviorPlatform.BlockHit> hit = platform.rayTrace(player, range);
                        JsonObject response = new JsonObject();
                        response.addProperty("location", hit.map(BehaviorPlatform.BlockHit::location).orElse(""));
                        response.addProperty("block", hit.map(BehaviorPlatform.BlockHit::block).orElse(""));
                        return response.toString();
                    });
                });
        action("read-block", Effect.OWNER_READ, "Reads the block at location, written x,y,z,world as the location facts write it, into result: block, its key, or nothing when the location is empty text or its world or chunk is not loaded. A server run reads it where the triggering player is owned.",
                schema("\"location\":{\"type\":\"string\",\"maxLength\":128}", required("location")), (arguments, problems) -> {},
                call -> {
                    String location = text(call.arguments, "location").orElseThrow();
                    java.util.function.Supplier<String> read = () -> {
                        JsonObject response = new JsonObject();
                        response.addProperty("block", platform.blockAt(location).orElse(""));
                        return response.toString();
                    };
                    return server(call) ? onPlayer(call, requirePlayer(call), read) : onOwner(call, read);
                });
        action("explode", Effect.WORLD, "Sets off an explosion of power (0-16, default 1) at x, y, z of the NPC's world, or of world, or with no position at the NPC, or another NPC named by npc. fire and breakBlocks start fires and break blocks only when behaviors.actions explosionsDamageBlocks allows it; protection plugins decide as for any explosion.",
                schema(COORDINATES + "," + string("world", 64) + "," + number("power", 0, 16) + "," + bool("fire") + "," + bool("breakBlocks") + "," + NPC),
                (arguments, problems) -> {
                    checkNpc(arguments, problems);
                    if (arguments.has("world")) matches(arguments, "world", "[A-Za-z0-9_./:-]{1,64}", problems);
                    long given = java.util.stream.Stream.of("x", "y", "z").filter(arguments::has).count();
                    if (given != 0 && given != 3) problems.add(problem("$.x", "explode-position", "give x, y and z together, or none to explode at the NPC"));
                },
                call -> {
                    requireNpc(call);
                    boolean grief = settings.explosionsDamageBlocks();
                    Optional<Vec3> at = call.arguments.has("x") ? Optional.of(point(call.arguments)) : Optional.empty();
                    float power = (float) decimal(call.arguments, "power").orElse(1);
                    boolean fire = grief && flag(call.arguments, "fire", false), breakBlocks = grief && flag(call.arguments, "breakBlocks", false);
                    return onOwner(call, () -> { platform.explode(call.npc(), new BehaviorPlatform.Explosion(text(call.arguments, "world"), at, power, fire, breakBlocks)); return "{}"; });
                });
        action("look-close", Effect.WORLD, "Turns the NPC's look-close, or another NPC's named by npc, on or off until a restart or a definition change, with an optional range (1-64) and lineOfSight.",
                schema(bool("enabled") + "," + number("range", 1, 64) + "," + bool("lineOfSight") + "," + NPC, required("enabled")),
                BuiltinCapabilities::checkNpc,
                call -> {
                    boolean enabled = call.arguments.get("enabled").getAsBoolean();
                    java.util.OptionalDouble range = decimal(call.arguments, "range");
                    Optional<Boolean> sight = call.arguments.has("lineOfSight") ? Optional.of(call.arguments.get("lineOfSight").getAsBoolean()) : Optional.empty();
                    return onOwner(call, () -> { platform.lookClose(call.npc(), enabled, range, sight); return "{}"; });
                });
        action("teleport", Effect.WORLD, "Teleports the triggering player (target: player, the default), or the NPC (target: npc), to x, y, z with optional world, yaw and pitch, to the NPC or the player, or the NPC to one of its anchors.",
                schema(choice("target", "player", "npc") + "," + COORDINATES + "," + string("world", 64) + "," + number("yaw", -360, 360) + "," + number("pitch", -90, 90) + "," + choice("to", "npc", "player") + "," + string("anchor", 32) + "," + PLAYER,
                        "\"oneOf\":[{" + required("x", "y", "z") + "},{" + required("to") + "},{" + required("anchor") + "}]"),
                BuiltinArguments::checkTeleport, this::teleport);
        action("give-item", Effect.WORLD, "Gives the triggering player, or the player named by player, amount of an item (1 by default), with an optional name, lore and enchantments. Reports given.",
                schema(GIFT + "," + PLAYER, GIFT_REQUIRED),
                (arguments, problems) -> { checkGift(arguments, problems); checkPlayer(arguments, problems); },
                call -> {
                    UUID player = target(call);
                    BehaviorPlatform.ItemGift gift = gift(call.arguments);
                    return onPlayer(call, player, () -> result("given", platform.giveItem(player, gift)));
                });
        condition("can-fit", Effect.OWNER_READ, "True when amount of an item (1 by default), with its name, lore and enchantments, fits in the storage slots of the triggering player, or the player named by player, without dropping any. False when there is no triggering player.",
                schema(GIFT + "," + PLAYER, GIFT_REQUIRED),
                (arguments, problems) -> { checkGift(arguments, problems); checkPlayer(arguments, problems); },
                call -> {
                    if (call.player().isEmpty() && !call.arguments.has("player")) return onOwner(call, () -> false);
                    UUID player = target(call);
                    BehaviorPlatform.ItemGift gift = gift(call.arguments);
                    return onPlayer(call, player, () -> platform.canFit(player, gift));
                });
        action("read-inventory", Effect.OWNER_READ, "Reads the filled slots of the triggering player's inventory, or the player named by player, into result: items, a list of maps with slot, item, amount and, for a renamed item, its name as plain text.",
                schema(PLAYER), BuiltinArguments::checkPlayer,
                call -> {
                    UUID player = target(call);
                    return onPlayer(call, player, () -> {
                        JsonObject response = new JsonObject();
                        response.add("items", heldItems(platform.inventory(player)));
                        return response.toString();
                    });
                });
        action("take-item", Effect.WORLD, "Takes up to amount (1 by default) of an item from the triggering player, or the player named by player. Reports taken, never more than held.",
                schema(string("item", 128) + "," + integer("amount", 1, MAXIMUM_ITEMS) + "," + PLAYER, required("item")),
                (arguments, problems) -> { matches(arguments, "item", MinecraftIds.PATTERN, problems); checkPlayer(arguments, problems); },
                call -> {
                    UUID player = target(call);
                    String item = MinecraftIds.normalize(text(call.arguments, "item").orElseThrow());
                    int amount = amount(call.arguments);
                    return onPlayer(call, player, () -> result("taken", Math.min(amount, Math.max(0, platform.takeItem(player, item, amount)))));
                });
        action("charge", Effect.WORLD, "Takes money, experience levels and items from the triggering player, or the player named by player, all or nothing. Refuses with cannot-afford when they cannot pay, which ends the run.",
                ChargeArguments.SCHEMA, (arguments, problems) -> { ChargeArguments.check(arguments, problems); checkPlayer(arguments, problems); },
                call -> {
                    UUID player = target(call);
                    BehaviorPlatform.Cost cost = ChargeArguments.parse(call.arguments);
                    return onPlayer(call, player, () -> {
                        if (!platform.charge(player, cost)) throw new CapabilityRefusal(Outcome.Status.CONFLICT, "cannot-afford", "The player cannot pay this cost");
                        return result("charged", true);
                    });
                });
        action("give-book", Effect.WORLD, "Gives the triggering player, or the player named by player, a written book. Pages use the dialogue colour codes. Reports given.",
                schema(string("title", 32) + "," + string("author", 32) + "," + texts("pages", 1, 50, 1024) + "," + PLAYER, required("title", "author", "pages")),
                (arguments, problems) -> { plainText(arguments, "title", problems); plainText(arguments, "author", problems); checkPlayer(arguments, problems); },
                call -> {
                    UUID player = target(call);
                    List<String> pages = new ArrayList<>();
                    for (JsonElement page : call.arguments.getAsJsonArray("pages")) pages.add(page.getAsString());
                    BehaviorPlatform.Book book = new BehaviorPlatform.Book(text(call.arguments, "title").orElseThrow(), text(call.arguments, "author").orElseThrow(), pages);
                    return onPlayer(call, player, () -> result("given", platform.giveBook(player, book)));
                });
    }

    private CompletionStage<String> message(Call call) {
        String written = text(call.arguments, "text").orElseThrow();
        BehaviorPlatform.MessageTarget to = switch (text(call.arguments, "to").orElse("trigger")) {
            case "all" -> BehaviorPlatform.MessageTarget.ALL;
            case "world" -> BehaviorPlatform.MessageTarget.WORLD;
            case "console" -> BehaviorPlatform.MessageTarget.CONSOLE;
            case "nearby" -> BehaviorPlatform.MessageTarget.NEARBY;
            default -> BehaviorPlatform.MessageTarget.PLAYER;
        };
        return switch (to) {
            case NEARBY -> {
                if (server(call)) throw new CapabilityRefusal(Outcome.Status.INVALID, "message-needs-npc", "A nearby message needs an NPC; a server behavior has none");
                double radius = call.arguments.has("radius") ? call.arguments.get("radius").getAsDouble() : 16;
                Optional<UUID> player = call.player();
                yield onOwner(call, () -> delivered(platform.messageNearby(call.npc(), radius, withPlayerName(written, player.filter(ignored -> written.contains(PLAYER_NAME_TOKEN))))));
            }
            case PLAYER -> {
                UUID player = target(call);
                yield onPlayer(call, player, () -> delivered(platform.message(call.npc(), Optional.of(player), to, withPlayerName(written, named(written, player)))));
            }
            case WORLD -> {
                if (server(call)) {
                    UUID player = requirePlayer(call);
                    yield onPlayer(call, player, () -> delivered(platform.message(call.npc(), Optional.of(player), to, withPlayerName(written, named(written, player)))));
                }
                Optional<UUID> player = call.player();
                yield onOwner(call, () -> delivered(platform.message(call.npc(), player, to, withPlayerName(written, player.filter(ignored -> written.contains(PLAYER_NAME_TOKEN))))));
            }
            case ALL, CONSOLE -> {
                Optional<UUID> named = written.contains(PLAYER_NAME_TOKEN) ? Optional.of(requirePlayer(call)) : Optional.empty();
                yield onGlobal(call, () -> delivered(platform.message(call.npc(), call.player(), to, withPlayerName(written, named))));
            }
        };
    }

    private CompletionStage<String> teleport(Call call) {
        if (call.arguments.has("anchor")) {
            requireNpc(call);
            String anchor = text(call.arguments, "anchor").orElseThrow();
            return onOwner(call, () -> { platform.teleportNpc(call.npc(), platform.anchor(call.npc(), anchor)); return "{}"; });
        }
        BehaviorPlatform.Destination destination;
        if (call.arguments.has("to")) {
            if (text(call.arguments, "to").orElseThrow().equals("npc")) { requireNpc(call); destination = new BehaviorPlatform.ToNpc(); }
            else destination = new BehaviorPlatform.ToPlayer(target(call));
        } else destination = new BehaviorPlatform.Point(text(call.arguments, "world"), point(call.arguments), decimal(call.arguments, "yaw"), decimal(call.arguments, "pitch"));
        if (text(call.arguments, "target").orElse("player").equals("npc")) {
            requireNpc(call);
            BehaviorPlatform.Destination to = destination;
            return onOwner(call, () -> { platform.teleportNpc(call.npc(), to); return "{}"; });
        }
        UUID player = target(call);
        BehaviorPlatform.Destination to = destination;
        return onPlayer(call, player, () -> result("teleported", platform.teleportPlayer(player, call.npc(), to)));
    }


    private static int ticks(JsonObject arguments, String name, int fallback) { return arguments.has(name) ? arguments.get(name).getAsInt() : fallback; }
    private static int amount(JsonObject arguments) { return arguments.has("amount") ? arguments.get("amount").getAsInt() : 1; }
    private static Optional<UUID> named(String text, UUID player) { return text.contains(PLAYER_NAME_TOKEN) ? Optional.of(player) : Optional.empty(); }
    /** Owner only. Fills {player_name} in the title and subtitle. */
    private BehaviorPlatform.Title named(BehaviorPlatform.Title title, UUID player) {
        return new BehaviorPlatform.Title(title.title().map(text -> withPlayerName(text, named(text, player))), title.subtitle().map(text -> withPlayerName(text, named(text, player))),
                title.fadeIn(), title.stay(), title.fadeOut());
    }
    /** Server behaviors always address the player; NPC behaviors use the NPC unless at says player. */
    private static boolean atPlayer(Call call) {
        String at = text(call.arguments, "at").orElse(server(call) ? "player" : "npc");
        if (at.equals("npc")) requireNpc(call);
        return at.equals("player");
    }

    /** The typed value of a flag argument by the canonical JSON rules: true when absent. */
    private static BehaviorProgram.Value flagValue(JsonObject arguments) {
        if (!arguments.has("value") || arguments.get("value").isJsonNull()) return new BehaviorProgram.Value(BehaviorProgram.Type.BOOLEAN, true);
        try { return BehaviorValues.read(arguments.get("value")); }
        catch (IllegalArgumentException | ArithmeticException invalid) {
            throw new CapabilityRefusal(Outcome.Status.INVALID, "flag-value-unsupported", "Flag value is outside the value bounds");
        }
    }
    /** A named player or NPC only for the scopes that belong to one. */
    private static void checkFlagPlayer(JsonObject arguments, List<Diagnostic> problems) {
        checkPlayer(arguments, problems);
        String scope = text(arguments, "scope").orElse("player");
        if (arguments.has("player") && !List.of("player", "relationship").contains(scope))
            problems.add(problem("$.player", "flag-player-scope", "player names whose flag it is, so it needs scope player or relationship"));
        if (!arguments.has("npc")) return;
        String npc = arguments.get("npc").getAsString();
        try { if (!UUID.fromString(npc).toString().equals(npc)) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException invalid) { problems.add(problem("$.npc", "npc-uuid", "npc must be a lowercase NPC UUID")); }
        if (!List.of("npc", "relationship").contains(scope)) problems.add(problem("$.npc", "flag-npc-scope", "npc names whose flag it is, so it needs scope npc or relationship"));
    }

    /** Exact typed equality. An unset or expired flag equals false only; a stored value of another type equals nothing. */
    private boolean flagEquals(StateSlot slot, BehaviorProgram.Value expected) {
        BehaviorProgram.Value unset = new BehaviorProgram.Value(expected.type(), expected.value());
        BehaviorProgram.Value stored;
        try { stored = state.read(slot.subject, slot.name, slot.declaration, unset, slot.type); }
        catch (BehaviorState.Rejected rejected) {
            if (rejected.diagnostic().code().equals("behavior-state-type-mismatch")) return false;
            throw new CapabilityRefusal(rejected.status(), rejected.diagnostic());
        }
        if (stored == unset) return expected.equals(UNSET_FLAG);
        return stored.equals(expected);
    }

    private CompletionStage<String> presentChoices(Call call) {
        UUID player = requirePlayer(call);
        String id = text(call.arguments, "id").orElseThrow();
        String prompt = text(call.arguments, "prompt").orElseThrow();
        List<ChoiceOption> options = new ArrayList<>();
        for (JsonElement option : call.arguments.getAsJsonArray("options")) {
            JsonObject entry = option.getAsJsonObject();
            options.add(new ChoiceOption(entry.get("id").getAsString(), entry.get("text").getAsString(), text(entry, "color"), text(entry, "hover"), text(entry, "style")));
        }
        long seconds = call.arguments.has("lifetimeSeconds") ? call.arguments.get("lifetimeSeconds").getAsLong() : settings.choiceLifetimeSeconds();
        return onOwner(call, () -> {
            String token = choices.open(call.npc(), player, call.invocation.behavior(), id, options, Duration.ofSeconds(seconds));
            int reached = platform.presentChoices(call.npc(), player, prompt, token, List.copyOf(options));
            if (reached == 0) choices.discard(token);
            return delivered(reached);
        });
    }

    private CompletionStage<String> runCommand(Call call) {
        Optional<String> behavior = call.invocation.behavior();
        if (call.invocation.preview() || behavior.isEmpty() || !settings.commandAllowed(behavior.get()))
            throw new CapabilityRefusal(Outcome.Status.UNAVAILABLE, "privileged-capability-not-granted", "This behavior is not on the command allow-list");
        CommandSource source = text(call.arguments, "as").orElse("console").equals("player") ? CommandSource.PLAYER : CommandSource.CONSOLE;
        Optional<UUID> player = source == CommandSource.PLAYER ? Optional.of(target(call)) : Optional.empty();
        Optional<UUID> filledFor = player.isPresent() || call.arguments.has("player") ? Optional.of(target(call)) : call.player();
        boolean rawText = settings.rawTextAllowed(behavior.get());
        List<String> permissions = BuiltinArguments.permissions(call.arguments);
        int permissionTicks = ticks(call.arguments, "permissionTicks", 0);
        String written = text(call.arguments, "command").orElseThrow();
        String identified = written.contains(PLAYER_TOKEN) ? written.replace(PLAYER_TOKEN, target(call).toString()) : written;
        Optional<UUID> named = identified.contains(PLAYER_NAME_TOKEN) ? Optional.of(target(call)) : Optional.empty();
        Supplier<String> work = () -> {
            String command = withPlayerName(identified, named.map(this::commandSafeName));
            boolean operator = flag(call.arguments, "asOperator", false);
            log.accept("Behavior command: behavior=" + behavior.get() + " npc=" + call.npc().uuid() + " as=" + (operator ? "operator" : source.name().toLowerCase(Locale.ROOT))
                    + player.map(id -> " player=" + id).orElse("") + (permissions.isEmpty() ? "" : " permissions=" + String.join(",", permissions))
                    + " command=" + command.substring(0, Math.min(command.length(), MAXIMUM_LOGGED_COMMAND)));
            platform.runCommand(new BehaviorPlatform.CommandRun(source, player, command, filledFor, rawText, permissions, permissionTicks, operator));
            return "{}";
        };
        if (!server(call)) return onOwner(call, work);
        return player.isPresent() ? onPlayer(call, player.get(), work) : named.isPresent() ? onPlayer(call, named.get(), work) : onGlobal(call, work);
    }

    private static BehaviorPlatform.ScreenKind screenKind(JsonObject arguments) {
        return switch (text(arguments, "kind").orElse("inventory")) {
            case "menu" -> BehaviorPlatform.ScreenKind.MENU;
            case "shop" -> BehaviorPlatform.ScreenKind.SHOP;
            case "gloss" -> BehaviorPlatform.ScreenKind.GLOSS;
            default -> BehaviorPlatform.ScreenKind.GUI;
        };
    }

    private CompletionStage<String> openScreen(Call call, BehaviorPlatform.ScreenKind kind, String field) {
        UUID player = target(call);
        String id = text(call.arguments, field).orElseThrow();
        return onOwnerOrPlayer(call, player, () -> result("delivered", platform.openScreen(call.npc(), player, kind, id) ? 1 : 0));
    }

    private CompletionStage<String> navigate(Call call) { return awaitable(call, routeStarter(call), "arrived"); }

    private CompletionStage<String> playRecording(Call call) {
        String recording = text(call.arguments, "recording").orElseThrow();
        Optional<Playback> playback = text(call.arguments, "playback").flatMap(Playback::parse);
        return awaitable(call, () -> { platform.playRecording(call.npc(), recording, playback); return new JsonObject(); }, "finished");
    }

    /** Starts the movement on the owner; with wait, completes when the route finishes and marks the response with {@code finished}. */
    private CompletionStage<String> awaitable(Call call, Supplier<JsonObject> start, String finished) {
        if (!flag(call.arguments, "wait", false)) return onOwner(call, () -> start.get().toString());
        int timeout = timeoutTicks(call.arguments);
        CompletionStage<CompletionStage<String>> waiting = onOwner(call, () -> {
            waits.requireCapacity();
            JsonObject response = start.get();
            response.addProperty(finished, true);
            return waits.watch(call.npc(), call.invocation.cancellation(), platform.routeFinished(call.npc()), response.toString(), timeout);
        });
        return waiting.thenCompose((CompletionStage<String> arrival) -> arrival);
    }

    private Supplier<JsonObject> routeStarter(Call call) {
        Optional<String> route = text(call.arguments, "route");
        if (route.isPresent()) return () -> { platform.followRoute(call.npc(), route.get()); return new JsonObject(); };
        Optional<String> anchor = text(call.arguments, "anchor");
        Vec3 given = anchor.isPresent() ? null : point(call.arguments);
        MovementProfile.Mode mode = MovementProfile.Mode.valueOf(text(call.arguments, "mode").orElse("walk").toUpperCase(Locale.ROOT));
        double speed = decimal(call.arguments, "speed").orElse(2);
        return () -> {
            Vec3 destination = given;
            if (anchor.isPresent()) {
                BehaviorPlatform.Point place = platform.anchor(call.npc(), anchor.get());
                if (place.world().isPresent())
                    throw new CapabilityRefusal(Outcome.Status.INVALID, "anchor-in-another-world", "Anchor " + anchor.get() + " is in another world; teleport the NPC there");
                destination = place.position();
            }
            JsonObject response = new JsonObject();
            response.addProperty("route", platform.navigate(call.npc(), destination, mode, speed));
            return response;
        };
    }

    private static int timeoutTicks(JsonObject arguments) {
        return arguments.has("timeoutTicks") ? arguments.get("timeoutTicks").getAsInt() : DEFAULT_WAIT_TICKS;
    }

    private CompletionStage<String> look(Call call) {
        if (call.arguments.has("target")) {
            UUID player = requirePlayer(call);
            return onOwner(call, () -> { platform.lookAtPlayer(call.npc(), player); return "{}"; });
        }
        Vec3 target = point(call.arguments);
        return onOwner(call, () -> { platform.look(call.npc(), target); return "{}"; });
    }









    private static boolean npcStateMatches(NpcStatus status, JsonObject arguments) {
        if (arguments.has("spawned") && arguments.get("spawned").getAsBoolean() != status.spawned()) return false;
        if (arguments.has("minimumHealth") && status.health() < arguments.get("minimumHealth").getAsDouble()) return false;
        return !arguments.has("maximumHealth") || status.health() <= arguments.get("maximumHealth").getAsDouble();
    }

    private static boolean questMatches(long stage, JsonObject arguments) {
        if (arguments.has("equals") && stage != arguments.get("equals").getAsLong()) return false;
        if (arguments.has("atLeast") && stage < arguments.get("atLeast").getAsLong()) return false;
        return !arguments.has("atMost") || stage <= arguments.get("atMost").getAsLong();
    }

    /**
     * Flags and quests live in behavior state under one reserved identity per kind and behavior namespace (see
     * {@link BehaviorFlags}). Flags are declared {@code any}; quest stages are integers.
     */
    private static StateSlot slot(Call call, String identity) {
        String behavior = call.invocation.behavior().orElseThrow(() -> new CapabilityRefusal(Outcome.Status.INVALID, "no-behavior-namespace", "Flags and quests belong to a behavior namespace, and this call has no behavior"));
        String scopeName = text(call.arguments, "scope").orElse("player");
        if ((scopeName.equals("npc") || scopeName.equals("relationship")) && server(call) && !call.arguments.has("npc"))
            throw new CapabilityRefusal(Outcome.Status.INVALID, "no-npc", "A server behavior has no NPC: use player or global flags and quests, or name the NPC");
        BehaviorProgram.ScopedVariable declaration = BehaviorFlags.declaration(scopeName);
        Optional<UUID> player = call.arguments.has("player") ? Optional.of(target(call)) : call.player();
        if (declaration.scope().requiresPlayer() && player.isEmpty()) requirePlayer(call);
        UUID npc = call.arguments.has("npc") ? UUID.fromString(text(call.arguments, "npc").orElseThrow()) : call.npc().uuid();
        return new StateSlot(BehaviorFlags.subject(identity, behavior, npc, player), text(call.arguments, "name").orElseThrow(), declaration,
                identity.equals(FLAG_STATE) ? BehaviorProgram.Type.ANY : BehaviorProgram.Type.INTEGER);
    }

    private BehaviorProgram.Value read(StateSlot slot, BehaviorProgram.Value unset) {
        try { return state.read(slot.subject, slot.name, slot.declaration, unset, slot.type); }
        catch (BehaviorState.Rejected rejected) { throw new CapabilityRefusal(rejected.status(), rejected.diagnostic()); }
    }

    private void write(StateSlot slot, BehaviorProgram.Value value) { write(slot, value, OptionalLong.empty()); }

    /** An expiry at or before the store's current time removes the key, so it also clears a flag. */
    private void write(StateSlot slot, BehaviorProgram.Value value, OptionalLong expiresAtMillis) {
        Map<String, Long> expiry = expiresAtMillis.isPresent() ? Map.of(slot.name, expiresAtMillis.getAsLong()) : Map.of();
        try { state.write(slot.subject, Map.of(slot.name, value), Map.of(slot.name, slot.declaration), expiry); }
        catch (BehaviorState.Rejected rejected) { throw new CapabilityRefusal(rejected.status(), rejected.diagnostic()); }
    }

    /** Store writes are last-write-wins, so concurrent module workers serialize this read and write here. */
    private long advance(StateSlot slot, long by, long maximum) {
        synchronized (advances) {
            long current = (Long) read(slot, UNSET_QUEST).value();
            long next = Math.min(current + by, Math.max(current, maximum));
            write(slot, stage(next));
            return next;
        }
    }

    private static BehaviorProgram.Value stage(long value) { return new BehaviorProgram.Value(BehaviorProgram.Type.INTEGER, value); }

    private static Audience audience(Call call) {
        if (text(call.arguments, "audience").orElse("trigger").equals("nearby"))
            return new Audience(Optional.empty(), decimal(call.arguments, "radius").orElse(16));
        return new Audience(Optional.of(target(call)), 0);
    }

    /** Owner only. Refuses a name {@link art.arcane.animus.core.behavior.ArgumentTemplate#commandSafeName} refuses. */
    private UUID commandSafeName(UUID player) {
        Optional<String> name = platform.playerName(player);
        if (name.isPresent() && !art.arcane.animus.core.behavior.ArgumentTemplate.commandSafeName(name.get()))
            throw new CapabilityRefusal(Outcome.Status.INVALID, "player-name-unsafe", "The triggering player's name cannot be written into a command");
        return player;
    }

    /** Owner only. Replaces {player_name} with the player's current name, which cannot name a placeholder. */
    private String withPlayerName(String text, Optional<UUID> player) {
        if (player.isEmpty()) return text;
        String name = platform.playerName(player.get()).orElseThrow(() -> new CapabilityRefusal(Outcome.Status.UNAVAILABLE, "player-name-unknown", "The triggering player's name is not known"));
        return text.replace(PLAYER_NAME_TOKEN, art.arcane.animus.core.text.Placeholders.escape(name));
    }

    /** The most players whose last sidebar is kept for add and set_line; past it the oldest are forgotten all at once. */
    private static final int MAXIMUM_SIDEBARS = 4096;
    /** Each player's last shown sidebar, which add and set_line change. */
    private final java.util.Map<UUID, BehaviorPlatform.Sidebar> sidebars = new java.util.concurrent.ConcurrentHashMap<>();
    /** The most characters of item data one inventory read carries in all, so its result stays within the result bound. */
    private static final int MAXIMUM_INVENTORY_DATA = 48_000;

    /** Inventory items as result maps: slot, item, amount, name when renamed, and data while the whole read stays bounded. */
    public static JsonArray heldItems(List<BehaviorPlatform.HeldItem> held) {
        JsonArray items = new JsonArray();
        int data = 0;
        for (BehaviorPlatform.HeldItem one : held) {
            JsonObject item = new JsonObject();
            item.addProperty("slot", one.slot());
            item.addProperty("item", one.item());
            item.addProperty("amount", one.amount());
            one.name().ifPresent((String name) -> item.addProperty("name", name));
            if (one.data().isPresent() && one.data().get().length() <= BehaviorPlatform.MAXIMUM_ITEM_DATA && data + one.data().get().length() <= MAXIMUM_INVENTORY_DATA) {
                item.addProperty("data", one.data().get());
                data += one.data().get().length();
            }
            items.add(item);
        }
        return items;
    }

    private static void checkNpc(JsonObject arguments, List<Diagnostic> problems) {
        if (!arguments.has("npc") || !arguments.get("npc").isJsonPrimitive()) return;
        String npc = arguments.get("npc").getAsString();
        try { if (!UUID.fromString(npc).toString().equals(npc)) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException invalid) { problems.add(problem("$.npc", "npc-uuid", "npc must be a lowercase NPC UUID")); }
    }

    private static void checkGift(JsonObject arguments, List<Diagnostic> problems) {
        if (arguments.has("stack") && List.of("item", "name", "lore", "enchantments").stream().anyMatch(arguments::has))
            problems.add(problem("$.stack", "stack-and-item", "give the item as stack or by its keys, not both"));
        JsonObject source = arguments.has("stack") && arguments.get("stack").isJsonObject() ? arguments.getAsJsonObject("stack") : arguments;
        String at = source == arguments ? "$" : "$.stack";
        if (source.has("item") && source.get("item").isJsonPrimitive() && !source.get("item").getAsString().matches(MinecraftIds.PATTERN))
            problems.add(problem(at + ".item", "argument-syntax", "item does not have the expected form"));
        if (source.has("name") && source.get("name").isJsonPrimitive()) plainText(source, "name", problems);
        if (source.has("enchantments") && source.get("enchantments").isJsonObject())
            for (String key : source.getAsJsonObject("enchantments").keySet())
                if (!key.matches(MinecraftIds.PATTERN)) problems.add(problem(at + ".enchantments", "enchantment-key", "enchantment " + key + " is not a resource key such as minecraft:sharpness"));
    }

    private static BehaviorPlatform.ItemGift gift(JsonObject arguments) {
        JsonObject source = arguments.has("stack") ? arguments.getAsJsonObject("stack") : arguments;
        List<String> lore = new ArrayList<>();
        if (source.has("lore")) for (JsonElement line : source.getAsJsonArray("lore")) lore.add(line.getAsString());
        java.util.Map<String, Integer> enchantments = new java.util.TreeMap<>();
        if (source.has("enchantments")) for (java.util.Map.Entry<String, JsonElement> entry : source.getAsJsonObject("enchantments").entrySet())
            enchantments.put(MinecraftIds.normalize(entry.getKey()), entry.getValue().getAsInt());
        int amount = arguments.has("amount") ? amount(arguments) : amount(source);
        return new BehaviorPlatform.ItemGift(MinecraftIds.normalize(text(source, "item").orElseThrow()), amount, text(source, "name"), lore, enchantments, text(source, "data"));
    }

    /** The player an effect at: player is placed at: atPlayer, or else the player it is for. */
    private static UUID anchor(Call call) {
        Optional<String> named = text(call.arguments, "atPlayer");
        if (named.isEmpty()) return target(call);
        try { return UUID.fromString(named.get()); }
        catch (IllegalArgumentException invalid) { throw new CapabilityRefusal(Outcome.Status.INVALID, "player-uuid", "atPlayer must be a player UUID"); }
    }

    /** atPlayer is a player UUID, and only an effect placed at a player takes it. */
    private static void checkAtPlayer(JsonObject arguments, List<Diagnostic> problems) {
        if (!arguments.has("atPlayer") || arguments.get("atPlayer").isJsonNull()) return;
        String player = arguments.get("atPlayer").getAsString();
        try { if (!UUID.fromString(player).toString().equals(player)) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException invalid) { problems.add(problem("$.atPlayer", "player-uuid", "atPlayer must be a lowercase player UUID")); }
        if (text(arguments, "at").filter("npc"::equals).isPresent()) problems.add(problem("$.atPlayer", "at-player-npc", "atPlayer is used only with at: player"));
    }

    /** The player named by the player argument, or the triggering player. */
    private static UUID target(Call call) {
        Optional<String> named = text(call.arguments, "player");
        if (named.isPresent()) {
            try { return UUID.fromString(named.get()); }
            catch (IllegalArgumentException invalid) { throw new CapabilityRefusal(Outcome.Status.INVALID, "player-uuid", "player must be a player UUID"); }
        }
        return requirePlayer(call);
    }

    private static UUID requirePlayer(Call call) {
        return call.player().orElseThrow(() -> new CapabilityRefusal(Outcome.Status.INVALID, "no-triggering-player", "This behavior was not started by a player"));
    }

    private static Vec3 point(JsonObject arguments) {
        return new Vec3(arguments.get("x").getAsDouble(), arguments.get("y").getAsDouble(), arguments.get("z").getAsDouble());
    }




    private static String delivered(int viewers) { return result("delivered", viewers); }
    private static String result(String name, int value) {
        JsonObject response = new JsonObject();
        response.addProperty(name, value);
        return response.toString();
    }
    private static String status(String value) {
        JsonObject response = new JsonObject();
        response.addProperty("result", value);
        return response.toString();
    }
    private static String result(String name, boolean value) {
        JsonObject response = new JsonObject();
        response.addProperty(name, value);
        return response.toString();
    }




}
