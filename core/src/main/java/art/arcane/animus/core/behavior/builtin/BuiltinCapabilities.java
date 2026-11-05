package art.arcane.animus.core.behavior.builtin;

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
import art.arcane.animus.core.behavior.BehaviorProgram;
import art.arcane.animus.core.behavior.BehaviorState;
import art.arcane.animus.core.behavior.builtin.BehaviorPlatform.AppearanceChange;
import art.arcane.animus.core.behavior.builtin.BehaviorPlatform.BuildOperation;
import art.arcane.animus.core.behavior.builtin.BehaviorPlatform.Audience;
import art.arcane.animus.core.behavior.builtin.BehaviorPlatform.ChoiceOption;
import art.arcane.animus.core.text.RichText;
import art.arcane.animus.core.behavior.builtin.BehaviorPlatform.CombatOperation;
import art.arcane.animus.core.behavior.builtin.BehaviorPlatform.CommandSource;
import art.arcane.animus.core.behavior.builtin.BehaviorPlatform.NpcStatus;
import art.arcane.animus.core.behavior.builtin.BehaviorPlatform.ParticleEffect;
import art.arcane.animus.core.behavior.builtin.BehaviorPlatform.SoundEffect;
import art.arcane.animus.core.config.BuiltinActionSettings;
import art.arcane.animus.core.extension.BuiltinPolicy;
import art.arcane.animus.core.extension.CapabilityRefusal;
import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.navigation.MovementProfile;
import art.arcane.animus.core.storage.JsonDocuments;
import art.arcane.animus.core.virtual.Vec3;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
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
import java.util.function.Consumer;
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
    private static final String PLAYER_TOKEN = "{player}";
    private static final String IDENTIFIER = "[a-z0-9][a-z0-9_-]*";
    private static final String FLAG_STATE = NAMESPACE + ":flags/", QUEST_STATE = NAMESPACE + ":quests/";
    private static final Key NAVIGATE = new Key(NAMESPACE, "navigate");
    private static final int DEFAULT_WAIT_TICKS = 1200, MINIMUM_WAIT_TICKS = 20, MAXIMUM_WAIT_TICKS = 12_000;
    private static final long OWNER_TICK_MILLIS = 50, LAGGING_OWNER_ALLOWANCE = 2, WAIT_DEADLINE_SLACK_MILLIS = 5000;
    private static final String RESOURCE_KEY = "[a-z0-9_.-]+:[a-z0-9_./-]+";
    private static final String ANIMATION_ID = "[a-z0-9_-]{1,64}:[a-z0-9_-]{1,64}(/[a-z0-9_-]{1,64}){0,14}";
    private static final String PERMISSION = "[A-Za-z0-9_-]+(\\.[A-Za-z0-9_*-]+)*";

    /** Decides what a capability may do when previewed and whether it needs the platform owner. */
    private enum Effect {
        STATE_READ(false), STATE_WRITE(false), OWNER_READ(true), PRESENTATION(true), WORLD(true), PRIVILEGED(true);
        final boolean owner;
        Effect(boolean owner) { this.owner = owner; }
    }
    private record StateSlot(BehaviorState.Subject subject, String name, BehaviorProgram.ScopedVariable declaration) {}
    private static final BehaviorProgram.Value UNSET_FLAG = new BehaviorProgram.Value(BehaviorProgram.Type.BOOLEAN, false);
    private static final BehaviorProgram.Value UNSET_QUEST = stage(0);
    private record Call(Invocation invocation, JsonObject arguments) {
        NpcHandle npc() { return invocation.npc(); }
        Optional<UUID> player() { return invocation.player(); }
    }
    @FunctionalInterface private interface Body<T> { CompletionStage<T> run(Call call); }
    @FunctionalInterface private interface Semantics { void check(JsonObject arguments, List<Diagnostic> problems); }
    private record Spec(Key key, Effect effect, Kind kind, String description, String schema, Semantics semantics, Body<String> action, Body<Boolean> condition) {}

    private final BehaviorPlatform platform;
    private final BehaviorState state;
    private final Object advances = new Object();
    private final BuiltinActionSettings settings;
    private final Consumer<String> log;
    private final OwnerActionQueue queue;
    private final NavigationWaits waits;
    private final PendingChoices choices;
    private final Map<Key, Spec> specs = new LinkedHashMap<>();
    private volatile boolean closed;
    private AutoCloseable ticks;

    public BuiltinCapabilities(BehaviorPlatform platform, BehaviorState state, DialogueChoiceSink choiceSink,
                               BuiltinActionSettings settings, Consumer<String> log) {
        this.platform = Objects.requireNonNull(platform);
        this.state = Objects.requireNonNull(state);
        this.settings = Objects.requireNonNull(settings);
        this.log = Objects.requireNonNull(log);
        queue = new OwnerActionQueue(new OwnerActionQueue.Limits(settings.actionsPerTick(), settings.actionsPerNpcPerTick(), settings.queuedActions()), platform::onNpcOwner);
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

    /** A waiting navigate is bounded by its own timeout instead of the short module deadline. */
    @Override public OptionalLong deadlineMillis(Key key, String argumentsJson) {
        if (!key.equals(NAVIGATE)) return OptionalLong.empty();
        try {
            JsonObject arguments = JsonDocuments.object(argumentsJson);
            if (!flag(arguments, "wait", false)) return OptionalLong.empty();
            return OptionalLong.of(timeoutTicks(arguments) * OWNER_TICK_MILLIS * LAGGING_OWNER_ALLOWANCE + WAIT_DEADLINE_SLACK_MILLIS);
        } catch (IOException | RuntimeException invalid) { return OptionalLong.empty(); }
    }

    public boolean commandAllowed(String behavior) { return settings.commandAllowed(behavior); }

    /**
     * Owner only. Handles a click from the client. Forged, stale, foreign and out-of-range clicks are rejected,
     * and a rejected click publishes nothing.
     */
    public PendingChoices.Selection selectChoice(UUID player, String token, String option) {
        return choices.select(player, token, option, npc -> platform.unavailable().isEmpty() && !closed
                && platform.npcStatus(npc).spawned() && platform.playerInRange(npc, Optional.of(player), CHOICE_RANGE));
    }

    private <T> CompletionStage<T> invoke(Spec spec, Body<T> body, Invocation invocation) throws IOException {
        Call call = new Call(invocation, JsonDocuments.object(invocation.argumentsJson()));
        if (invocation.preview() && refusedInPreview(spec, call.arguments))
            throw new CapabilityRefusal(Outcome.Status.UNAVAILABLE, "preview-refused", spec.key + " is not available in a preview");
        return body.run(call);
    }

    private static boolean refusedInPreview(Spec spec, JsonObject arguments) {
        return switch (spec.effect) {
            case STATE_READ, OWNER_READ -> false;
            case PRESENTATION -> text(arguments, "audience").orElse("trigger").equals("nearby");
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

    private static String schema(String properties, String... constraints) {
        StringBuilder result = new StringBuilder("{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{").append(properties).append('}');
        for (String constraint : constraints) result.append(',').append(constraint);
        return result.append('}').toString();
    }
    private static String string(String name, int maximum) { return "\"" + name + "\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":" + maximum + "}"; }
    private static String number(String name, double minimum, double maximum) { return "\"" + name + "\":{\"type\":\"number\",\"minimum\":" + minimum + ",\"maximum\":" + maximum + "}"; }
    private static String integer(String name, long minimum, long maximum) { return "\"" + name + "\":{\"type\":\"integer\",\"minimum\":" + minimum + ",\"maximum\":" + maximum + "}"; }
    private static String choice(String name, String... values) { return "\"" + name + "\":{\"enum\":[\"" + String.join("\",\"", values) + "\"]}"; }
    private static String bool(String name) { return "\"" + name + "\":{\"type\":\"boolean\"}"; }
    private static String required(String... names) { return "\"required\":[\"" + String.join("\",\"", names) + "\"]"; }
    private static final String COORDINATES = number("x", -30_000_000, 30_000_000) + "," + number("y", -30_000_000, 30_000_000) + "," + number("z", -30_000_000, 30_000_000);
    private static final String AUDIENCE = choice("audience", "trigger", "nearby") + "," + number("radius", 1, 64);
    private static final String STATE = string("name", 64) + "," + choice("scope", "global", "npc", "player", "relationship");

    private void registerConditions() {
        condition("has-permission", Effect.OWNER_READ, "True when the triggering player holds a permission. False when there is no triggering player.",
                schema(string("permission", 128), required("permission")),
                (arguments, problems) -> matches(arguments, "permission", PERMISSION, problems),
                call -> {
                    if (call.player().isEmpty()) return onOwner(call, () -> false);
                    UUID player = call.player().get();
                    String node = text(call.arguments, "permission").orElseThrow();
                    return onOwner(call, () -> platform.hasPermission(player, node));
                });
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
        condition("flag-state", Effect.STATE_READ, "Compares a flag. An unset flag is false.",
                schema(STATE + "," + bool("value"), required("name")),
                (arguments, problems) -> matches(arguments, "name", IDENTIFIER, problems),
                call -> {
                    boolean expected = flag(call.arguments, "value", true);
                    return CompletableFuture.completedFuture((Boolean) read(slot(call, FLAG_STATE), UNSET_FLAG).value() == expected);
                });
        condition("quest-state", Effect.STATE_READ, "Compares a quest stage. An unset quest is stage zero.",
                schema(STATE + "," + integer("equals", 0, 1_000_000_000_000L) + "," + integer("atLeast", 0, 1_000_000_000_000L) + "," + integer("atMost", 0, 1_000_000_000_000L),
                        required("name"), "\"anyOf\":[" + "{" + required("equals") + "},{" + required("atLeast") + "},{" + required("atMost") + "}]"),
                (arguments, problems) -> matches(arguments, "name", IDENTIFIER, problems),
                call -> CompletableFuture.completedFuture(questMatches((Long) read(slot(call, QUEST_STATE), UNSET_QUEST).value(), call.arguments)));
    }

    private void registerActions() {
        action("say", Effect.PRESENTATION, "Sends dialogue text to the triggering player or to nearby players.",
                schema(string("text", 1024) + "," + AUDIENCE, required("text")),
                (arguments, problems) -> plainText(arguments, "text", problems),
                call -> {
                    Audience audience = audience(call);
                    String line = text(call.arguments, "text").orElseThrow();
                    return onOwner(call, () -> delivered(platform.say(call.npc(), audience, line)));
                });
        action("choices", Effect.WORLD, "Presents clickable options to the triggering player and publishes the validated click.",
                schema(string("id", 64) + "," + string("prompt", 512) + "," + integer("lifetimeSeconds", 1, 600)
                                + ",\"options\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":8,\"items\":" + schema(string("id", 32) + "," + string("text", 128) + "," + string("color", 16) + "," + string("hover", 256)
                                + "," + choice("style", "bold", "italic", "underlined", "plain"), required("id", "text")) + "}",
                        required("id", "prompt", "options")),
                BuiltinCapabilities::checkChoices, this::presentChoices);
        action("command", Effect.PRIVILEGED, "Runs a console or player command; {player} becomes the triggering player's UUID. Allowed only for behaviors on the TOML allow-list, and never in a preview.",
                schema(string("command", 1024) + "," + choice("as", "console", "player"), required("command")),
                BuiltinCapabilities::checkCommand, this::runCommand);
        action("navigate", Effect.WORLD, "Starts a route to a position in the NPC's world, or follows a reusable route. Completes when requested, or on arrival with wait.",
                schema(string("route", 64) + "," + COORDINATES + "," + choice("mode", "walk", "swim", "fly") + "," + number("speed", 1, 5) + "," + bool("wait") + "," + integer("timeoutTicks", MINIMUM_WAIT_TICKS, MAXIMUM_WAIT_TICKS),
                        "\"oneOf\":[{" + required("route") + "},{" + required("x", "y", "z") + "}]"),
                BuiltinCapabilities::checkNavigate, this::navigate);
        action("stop-navigation", Effect.WORLD, "Stops the NPC's current route and any route it is following.", schema(""),
                (arguments, problems) -> {},
                call -> onOwner(call, () -> result("stopped", platform.stopNavigation(call.npc()))));
        action("look", Effect.WORLD, "Turns the NPC toward a position or the triggering player.",
                schema(COORDINATES + ",\"target\":{\"const\":\"player\"}", "\"oneOf\":[{" + required("target") + "},{" + required("x", "y", "z") + "}]"),
                (arguments, problems) -> {},
                this::look);
        action("set-appearance", Effect.WORLD, "Changes the NPC's displayed name or skin until the runtime restarts or its definition changes.",
                schema(string("name", 16) + "," + string("skin", 64) + "," + bool("reset"),
                        "\"anyOf\":[{" + required("name") + "},{" + required("skin") + "},{" + required("reset") + "}]"),
                BuiltinCapabilities::checkAppearance,
                call -> {
                    AppearanceChange change = new AppearanceChange(text(call.arguments, "name"), text(call.arguments, "skin"), flag(call.arguments, "reset", false));
                    return onOwner(call, () -> { platform.setAppearance(call.npc(), change); return "{}"; });
                });
        action("set-equipment", Effect.WORLD, "Shows an item in an equipment slot, or clears the slot when no item is given. Lasts until the runtime restarts or the definition changes.",
                schema(choice("slot", "main_hand", "off_hand", "head", "chest", "legs", "feet", "body") + "," + string("item", 128), required("slot")),
                (arguments, problems) -> {
                    if (arguments.has("item")) matches(arguments, "item", RESOURCE_KEY, problems);
                },
                call -> {
                    EquipmentSlot slot = EquipmentSlot.valueOf(text(call.arguments, "slot").orElseThrow().toUpperCase(Locale.ROOT));
                    Optional<String> item = text(call.arguments, "item");
                    return onOwner(call, () -> { platform.setEquipment(call.npc(), slot, item); return "{}"; });
                });
        action("play-sound", Effect.PRESENTATION, "Plays a sound at the NPC for the triggering player or nearby players.",
                schema(string("sound", 128) + "," + number("volume", 0, 4) + "," + number("pitch", 0.5, 2) + "," + AUDIENCE, required("sound")),
                (arguments, problems) -> matches(arguments, "sound", RESOURCE_KEY, problems),
                call -> {
                    Audience audience = audience(call);
                    SoundEffect sound = new SoundEffect(text(call.arguments, "sound").orElseThrow(), (float) decimal(call.arguments, "volume").orElse(1), (float) decimal(call.arguments, "pitch").orElse(1));
                    return onOwner(call, () -> delivered(platform.playSound(call.npc(), audience, sound)));
                });
        action("spawn-particle", Effect.PRESENTATION, "Shows a particle effect at the NPC to the triggering player or nearby players.",
                schema(string("particle", 128) + "," + integer("count", 1, 64) + "," + number("spread", 0, 8) + "," + AUDIENCE, required("particle")),
                (arguments, problems) -> matches(arguments, "particle", RESOURCE_KEY, problems),
                call -> {
                    Audience audience = audience(call);
                    int count = call.arguments.has("count") ? call.arguments.get("count").getAsInt() : 1;
                    ParticleEffect particle = new ParticleEffect(text(call.arguments, "particle").orElseThrow(), count, decimal(call.arguments, "spread").orElse(0));
                    return onOwner(call, () -> delivered(platform.spawnParticle(call.npc(), audience, particle)));
                });
        action("combat-control", Effect.WORLD, "Engages or disengages the NPC's combat, attacks or releases a target, or guards a participant (no target stops guarding; player is the triggering player). Needs enabled combat settings; protection still applies.",
                schema(choice("operation", "enable", "disable", "target", "release", "guard") + "," + string("target", 36), required("operation")),
                BuiltinCapabilities::checkCombat,
                call -> {
                    CombatOperation operation = CombatOperation.valueOf(text(call.arguments, "operation").orElseThrow().toUpperCase(Locale.ROOT));
                    Optional<String> named = text(call.arguments, "target");
                    Optional<UUID> target = named.isEmpty() ? Optional.empty()
                            : Optional.of(named.get().equals("player") ? requirePlayer(call) : UUID.fromString(named.get()));
                    return onOwner(call, () -> { platform.combat(call.npc(), operation, target); return "{}"; });
                });
        action("open-shop", Effect.WORLD, "Opens a shop for the triggering player.",
                schema(string("shop", 256), required("shop")),
                (arguments, problems) -> matches(arguments, "shop", art.arcane.animus.core.shop.ShopDocument.ID, problems),
                call -> openScreen(call, BehaviorPlatform.ScreenKind.SHOP, "shop"));
        action("open-gui", Effect.WORLD, "Opens a chest GUI (kind inventory, the default), holographic menu, shop or, with Gloss installed, a Gloss menu for the triggering player.",
                schema(string("gui", 256) + "," + choice("kind", "inventory", "menu", "shop", "gloss"), required("gui")),
                (arguments, problems) -> matches(arguments, "gui", screenKind(arguments) == BehaviorPlatform.ScreenKind.GLOSS
                        ? art.arcane.animus.core.gui.GuiBinding.GLOSS_ID : art.arcane.animus.core.shop.ShopDocument.ID, problems),
                call -> openScreen(call, screenKind(call.arguments), "gui"));
        action("build", Effect.WORLD, "Starts, pauses, resumes, cancels or undoes the NPC's build. A start names a schematic in the schematics folder, or uses the NPC's saved builder section; it builds at x, y, z or where the NPC stands. Protection still applies.",
                schema(choice("operation", "start", "pause", "resume", "cancel", "undo") + "," + string("schematic", 132) + "," + COORDINATES + ","
                        + integer("rotation", 0, 270) + "," + choice("mirror", "none", "x", "z") + "," + choice("anchor", "center", "corner", "schematic") + ","
                        + choice("pattern", "spiral", "reversespiral", "linear", "reverselinear") + "," + choice("materials", "creative", "donate") + ","
                        + bool("excavate") + "," + bool("ignoreAir") + "," + bool("ignoreLiquids"), required("operation")),
                BuiltinCapabilities::checkBuild,
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
        action("set-flag", Effect.STATE_WRITE, "Sets a flag, true unless value says otherwise.",
                schema(STATE + "," + bool("value"), required("name")),
                (arguments, problems) -> matches(arguments, "name", IDENTIFIER, problems),
                call -> { write(slot(call, FLAG_STATE), new BehaviorProgram.Value(BehaviorProgram.Type.BOOLEAN, flag(call.arguments, "value", true))); return CompletableFuture.completedFuture("{}"); });
        action("set-quest-state", Effect.STATE_WRITE, "Sets a quest stage.",
                schema(STATE + "," + integer("value", 0, 1_000_000_000_000L), required("name", "value")),
                (arguments, problems) -> matches(arguments, "name", IDENTIFIER, problems),
                call -> { write(slot(call, QUEST_STATE), stage(call.arguments.get("value").getAsLong())); return CompletableFuture.completedFuture("{}"); });
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
        Optional<UUID> player = source == CommandSource.PLAYER ? Optional.of(requirePlayer(call)) : Optional.empty();
        String written = text(call.arguments, "command").orElseThrow();
        String command = written.contains(PLAYER_TOKEN) ? written.replace(PLAYER_TOKEN, requirePlayer(call).toString()) : written;
        return onOwner(call, () -> {
            log.accept("Behavior command: behavior=" + behavior.get() + " npc=" + call.npc().uuid() + " as=" + source.name().toLowerCase(Locale.ROOT)
                    + player.map(id -> " player=" + id).orElse("") + " command=" + command.substring(0, Math.min(command.length(), MAXIMUM_LOGGED_COMMAND)));
            platform.runCommand(source, player, command);
            return "{}";
        });
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
        UUID player = requirePlayer(call);
        String id = text(call.arguments, field).orElseThrow();
        return onOwner(call, () -> result("delivered", platform.openScreen(call.npc(), player, kind, id) ? 1 : 0));
    }

    private CompletionStage<String> navigate(Call call) {
        Supplier<JsonObject> start = routeStarter(call);
        if (!flag(call.arguments, "wait", false)) return onOwner(call, () -> start.get().toString());
        int timeout = timeoutTicks(call.arguments);
        CompletionStage<CompletionStage<String>> waiting = onOwner(call, () -> {
            waits.requireCapacity();
            JsonObject response = start.get();
            response.addProperty("arrived", true);
            return waits.watch(call.npc(), call.invocation.cancellation(), platform.routeFinished(call.npc()), response.toString(), timeout);
        });
        return waiting.thenCompose(arrival -> arrival);
    }

    private Supplier<JsonObject> routeStarter(Call call) {
        Optional<String> route = text(call.arguments, "route");
        if (route.isPresent()) return () -> { platform.followRoute(call.npc(), route.get()); return new JsonObject(); };
        Vec3 destination = point(call.arguments);
        MovementProfile.Mode mode = MovementProfile.Mode.valueOf(text(call.arguments, "mode").orElse("walk").toUpperCase(Locale.ROOT));
        double speed = decimal(call.arguments, "speed").orElse(2);
        return () -> {
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

    private static void checkChoices(JsonObject arguments, List<Diagnostic> problems) {
        plainText(arguments, "id", problems);
        plainText(arguments, "prompt", problems);
        Set<String> seen = new HashSet<>();
        for (JsonElement option : arguments.getAsJsonArray("options")) {
            JsonObject entry = option.getAsJsonObject();
            String id = entry.get("id").getAsString();
            if (!id.matches(IDENTIFIER)) problems.add(problem("$.options", "choice-option-id", "Option ids use lowercase letters, digits, _ or -"));
            if (!seen.add(id)) problems.add(problem("$.options", "choice-option-duplicate", "Option ids must be unique"));
            plainText(entry, "text", problems);
            if (entry.has("color") && RichText.colourCode(entry.get("color").getAsString()).isEmpty())
                problems.add(problem("$.options", "choice-option-color", "color is a colour name such as green, a code such as a, or #RRGGBB"));
            if (entry.has("style") && !DialogueFormat.STYLES.contains(entry.get("style").getAsString()))
                problems.add(problem("$.options", "choice-option-style", "style is bold, italic, underlined or plain"));
            if (entry.has("hover")) {
                String hover = entry.get("hover").getAsString();
                if (hover.isBlank() || hover.codePoints().anyMatch((int glyph) -> glyph != '\n' && Character.isISOControl(glyph)))
                    problems.add(problem("$.options", "argument-control-characters", "hover must not be blank or contain control characters other than new lines"));
            }
        }
    }

    private static void checkCommand(JsonObject arguments, List<Diagnostic> problems) {
        plainText(arguments, "command", problems);
        if (arguments.get("command").getAsString().startsWith("/"))
            problems.add(problem("$.command", "command-leading-slash", "Write the command without a leading slash"));
    }

    private static void checkNavigate(JsonObject arguments, List<Diagnostic> problems) {
        if (arguments.has("timeoutTicks") && !flag(arguments, "wait", false))
            problems.add(problem("$.timeoutTicks", "navigation-timeout-without-wait", "timeoutTicks applies only with wait set to true"));
        if (!arguments.has("route")) return;
        matches(arguments, "route", IDENTIFIER, problems);
        if (arguments.has("mode") || arguments.has("speed"))
            problems.add(problem("$.route", "route-owns-movement", "A reusable route keeps its own mode and speed"));
    }

    private static void checkAppearance(JsonObject arguments, List<Diagnostic> problems) {
        if (arguments.has("name")) plainText(arguments, "name", problems);
        if (arguments.has("skin")) matches(arguments, "skin", "[a-f0-9]{64}", problems);
        if (arguments.has("reset") && (!arguments.get("reset").getAsBoolean() || arguments.has("name") || arguments.has("skin")))
            problems.add(problem("$.reset", "appearance-reset", "reset must be true and cannot be combined with name or skin"));
    }

    private static void checkBuild(JsonObject arguments, List<Diagnostic> problems) {
        boolean start = arguments.get("operation").getAsString().equals("start");
        for (String name : List.of("schematic", "x", "y", "z", "rotation", "mirror", "anchor", "pattern", "materials", "excavate", "ignoreAir", "ignoreLiquids"))
            if (!start && arguments.has(name)) problems.add(problem("$." + name, "build-option", name + " is used only by the start operation"));
        if (arguments.has("x") != arguments.has("y") || arguments.has("y") != arguments.has("z"))
            problems.add(problem("$.x", "build-origin", "give all of x, y and z, or none to build where the NPC stands"));
        if (arguments.has("rotation") && arguments.get("rotation").getAsInt() % 90 != 0)
            problems.add(problem("$.rotation", "build-rotation", "rotation must be 0, 90, 180 or 270"));
        if (arguments.has("schematic")) matches(arguments, "schematic", "[A-Za-z0-9][A-Za-z0-9_.-]{0,127}\\.(schem|nbt|schematic)", problems);
    }

    private static void checkCombat(JsonObject arguments, List<Diagnostic> problems) {
        String operation = arguments.get("operation").getAsString();
        boolean targeting = operation.equals("target");
        if (!operation.equals("guard") && targeting != arguments.has("target")) {
            problems.add(problem("$.target", "combat-target", "target is required by the target operation, optional for guard, and refused otherwise"));
            return;
        }
        if (!arguments.has("target")) return;
        String target = arguments.get("target").getAsString();
        if (target.equals("player")) return;
        try { if (!UUID.fromString(target).toString().equals(target)) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException invalid) { problems.add(problem("$.target", "combat-target", "target must be an entity UUID or player")); }
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
     * Flags and quests live in behavior state under one reserved identity per kind and behavior namespace,
     * so a pack's behaviors share them, packs stay apart, and the state budgets and purge commands apply.
     */
    private static StateSlot slot(Call call, String identity) {
        String behavior = call.invocation.behavior().orElseThrow(() -> new CapabilityRefusal(Outcome.Status.INVALID, "no-behavior-namespace", "Flags and quests belong to a behavior namespace, and this call has no behavior"));
        BehaviorProgram.Scope scope = BehaviorProgram.Scope.valueOf(text(call.arguments, "scope").orElse("player").toUpperCase(Locale.ROOT));
        if (scope.requiresPlayer()) requirePlayer(call);
        return new StateSlot(new BehaviorState.Subject(identity + behavior.substring(0, behavior.indexOf(':')), call.npc().uuid(), call.player()),
                text(call.arguments, "name").orElseThrow(), new BehaviorProgram.ScopedVariable(scope, BehaviorProgram.Lifetime.PERSISTENT));
    }

    private BehaviorProgram.Value read(StateSlot slot, BehaviorProgram.Value unset) {
        try { return state.read(slot.subject, slot.name, slot.declaration, unset); }
        catch (BehaviorState.Rejected rejected) { throw new CapabilityRefusal(rejected.status(), rejected.diagnostic()); }
    }

    private void write(StateSlot slot, BehaviorProgram.Value value) {
        try { state.write(slot.subject, Map.of(slot.name, value), Map.of(slot.name, slot.declaration)); }
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
        return new Audience(Optional.of(requirePlayer(call)), 0);
    }

    private static UUID requirePlayer(Call call) {
        return call.player().orElseThrow(() -> new CapabilityRefusal(Outcome.Status.INVALID, "no-triggering-player", "This behavior was not started by a player"));
    }

    private static Vec3 point(JsonObject arguments) {
        return new Vec3(arguments.get("x").getAsDouble(), arguments.get("y").getAsDouble(), arguments.get("z").getAsDouble());
    }

    private static Optional<String> text(JsonObject arguments, String name) {
        return arguments.has(name) ? Optional.of(arguments.get(name).getAsString()) : Optional.empty();
    }

    private static OptionalDouble decimal(JsonObject arguments, String name) {
        return arguments.has(name) ? OptionalDouble.of(arguments.get(name).getAsDouble()) : OptionalDouble.empty();
    }

    private static boolean flag(JsonObject arguments, String name, boolean fallback) {
        return arguments.has(name) ? arguments.get(name).getAsBoolean() : fallback;
    }

    private static String delivered(int viewers) { return result("delivered", viewers); }
    private static String result(String name, int value) {
        JsonObject response = new JsonObject();
        response.addProperty(name, value);
        return response.toString();
    }
    private static String result(String name, boolean value) {
        JsonObject response = new JsonObject();
        response.addProperty(name, value);
        return response.toString();
    }

    private static void matches(JsonObject arguments, String name, String pattern, List<Diagnostic> problems) {
        if (!arguments.get(name).getAsString().matches(pattern))
            problems.add(problem("$." + name, "argument-syntax", name + " does not have the expected form"));
    }

    private static void plainText(JsonObject arguments, String name, List<Diagnostic> problems) {
        String value = arguments.get(name).getAsString();
        if (value.isBlank() || value.codePoints().anyMatch(Character::isISOControl))
            problems.add(problem("$." + name, "argument-control-characters", name + " must not be blank or contain control characters"));
    }

    private static Diagnostic problem(String field, String code, String message) {
        return new Diagnostic(Diagnostic.Severity.ERROR, code, field, message);
    }
}
