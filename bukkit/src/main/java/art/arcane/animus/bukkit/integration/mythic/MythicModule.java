package art.arcane.animus.bukkit.integration.mythic;

import art.arcane.animus.api.AnimusApi;
import art.arcane.animus.api.ApiRange;
import art.arcane.animus.api.Capabilities;
import art.arcane.animus.api.Diagnostic;
import art.arcane.animus.api.ModuleContext;
import art.arcane.animus.api.ModuleManifest;
import art.arcane.animus.api.Outcome;
import art.arcane.animus.api.Platform;
import art.arcane.animus.api.TrustedModule;
import art.arcane.animus.api.Version;
import art.arcane.animus.core.extension.BuiltinPolicy;
import art.arcane.animus.core.extension.CapabilityRefusal;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * The {@code mythic} behavior capabilities: {@code mythic:cast-skill} casts a Mythic skill from the NPC, or as the
 * triggering player. Bound like the built-in capabilities while MythicMobs is enabled. A skill can run commands, so the
 * action is privileged: it runs only for behaviors on the command allow-list, as {@code animus:command} does.
 */
final class MythicModule implements TrustedModule, BuiltinPolicy {
    static final Capabilities.Key CAST = new Capabilities.Key("mythic", "cast-skill");
    private static final Set<Platform> PLATFORMS = Set.of(Platform.SPIGOT, Platform.PAPER, Platform.FOLIA);
    private static final ModuleManifest MANIFEST = new ModuleManifest("mythic", new Version(1, 0, 0),
            new ApiRange(AnimusApi.API_VERSION.major(), AnimusApi.API_VERSION.major()), PLATFORMS, List.of());
    private static final String SCHEMA = "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{"
            + "\"skill\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":128},"
            + "\"caster\":{\"enum\":[\"npc\",\"player\"]},"
            + "\"power\":{\"type\":\"number\",\"minimum\":0.01,\"maximum\":100}},\"required\":[\"skill\"]}";

    private final MythicBridge mythic;
    private final MythicHost host;

    MythicModule(MythicBridge mythic, MythicHost host) { this.mythic = mythic; this.host = host; }

    @Override public ModuleManifest manifest() { return MANIFEST; }

    @Override public void enable(ModuleContext context) {
        context.registrations().action(new Capabilities.Descriptor(CAST, Capabilities.Kind.ACTION,
                "Casts a MythicMobs skill. caster npc (the default) casts it from where the NPC stands, with the triggering player as its trigger and target; "
                        + "caster player casts it as the triggering player. power scales the skill, 1 by default. Reports cast: false when the skill's own conditions or cooldown stop it.",
                SCHEMA, PLATFORMS, true), this::cast);
    }

    @Override public void disable() {}

    @Override public Optional<String> unavailable(Capabilities.Key key) { return Optional.empty(); }

    @Override public List<Diagnostic> validate(Capabilities.Key key, String argumentsJson) {
        String skill = JsonParser.parseString(argumentsJson).getAsJsonObject().get("skill").getAsString();
        if (mythic.skillExists(skill)) return List.of();
        return List.of(new Diagnostic(Diagnostic.Severity.ERROR, "unknown-skill", "$.skill", "MythicMobs has no skill named " + skill));
    }

    private CompletionStage<String> cast(Capabilities.Invocation invocation) {
        JsonObject arguments = JsonParser.parseString(invocation.argumentsJson()).getAsJsonObject();
        String skill = arguments.get("skill").getAsString();
        boolean asPlayer = arguments.has("caster") && arguments.get("caster").getAsString().equals("player");
        JsonElement powerValue = arguments.get("power");
        float power = powerValue == null ? 1f : powerValue.getAsFloat();
        if (invocation.preview()) return CompletableFuture.completedFuture(result(false));
        CompletableFuture<String> done = new CompletableFuture<>();
        if (asPlayer) {
            UUID player = invocation.player().orElseThrow(() -> new CapabilityRefusal(Outcome.Status.INVALID, "no-player", "caster player needs a triggering player"));
            if (!host.player(player, (Player online) -> complete(done, () -> mythic.castAsPlayer(skill, online, power))))
                throw new CapabilityRefusal(Outcome.Status.UNAVAILABLE, "player-offline", "The triggering player is offline");
            return done;
        }
        MythicHost.Npc npc = host.npc(invocation.npc().uuid()).filter((MythicHost.Npc found) -> found.location().isPresent())
                .orElseThrow(() -> new CapabilityRefusal(Outcome.Status.UNAVAILABLE, "npc-inactive", "The NPC is not spawned"));
        MythicBridge.Caster caster = MythicBridge.Caster.of(npc);
        boolean accepted = host.at(npc.location().get(), () -> {
            Optional<Entity> trigger = invocation.player().flatMap(host::entity);
            complete(done, () -> mythic.castFromNpc(skill, caster, npc.location().get(), trigger, power));
        });
        if (!accepted) throw new CapabilityRefusal(Outcome.Status.UNAVAILABLE, "owner-refused", "The server refused to run the skill where the NPC stands");
        return done;
    }

    private static void complete(CompletableFuture<String> done, java.util.function.BooleanSupplier cast) {
        try { done.complete(result(cast.getAsBoolean())); }
        catch (RuntimeException | LinkageError failure) { done.completeExceptionally(failure); }
    }

    private static String result(boolean cast) { return "{\"cast\":" + cast + "}"; }
}
