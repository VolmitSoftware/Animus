package art.arcane.animus.core.api;

import art.arcane.animus.api.Flag;
import art.arcane.animus.api.NpcHandle;
import art.arcane.animus.api.Outcome;
import art.arcane.animus.core.behavior.BehaviorFlags;
import art.arcane.animus.core.behavior.BehaviorProgram;
import art.arcane.animus.core.behavior.BehaviorState;
import art.arcane.animus.core.behavior.BehaviorValues;
import art.arcane.animus.core.command.BehaviorCommands;
import art.arcane.animus.core.npc.NpcDefinition;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** Flags in behavior state, and behavior runs, for the public service. */
final class ApiState<P, I> {
    static final int MAXIMUM_VARIABLES = 32, MAXIMUM_VALUE = 1024;
    /** The NPC a global flag names, which the store ignores for that scope. */
    private static final UUID NO_NPC = new UUID(0, 0);
    private static final BehaviorProgram.Value CLEARED = new BehaviorProgram.Value(BehaviorProgram.Type.BOOLEAN, false);
    private final NpcApi<P, I> api;

    ApiState(NpcApi<P, I> api) { this.api = api; }

    CompletionStage<Outcome<Optional<String>>> flag(Flag flag) {
        Optional<Outcome<Optional<String>>> wrong = checked(flag);
        if (wrong.isPresent()) return api.done(wrong.get());
        BehaviorProgram.Value unset = new BehaviorProgram.Value(BehaviorProgram.Type.BOOLEAN, false);
        try {
            BehaviorProgram.Value stored = api.parts.runtime().behaviorState().read(subject(flag), flag.name(), declaration(flag), unset, BehaviorProgram.Type.ANY);
            return api.done(Outcome.ok(stored == unset ? Optional.empty() : Optional.of(BehaviorValues.json(stored).toString())));
        } catch (BehaviorState.Rejected rejected) { return api.done(new Outcome<>(rejected.status(), Optional.empty(), java.util.List.of(rejected.diagnostic()))); }
        catch (IllegalStateException stopped) { return api.done(NpcApi.stopped()); }
    }

    CompletionStage<Outcome<Flag>> setFlag(Flag flag, Optional<String> json) {
        Optional<Outcome<Flag>> wrong = checked(flag);
        if (wrong.isPresent()) return api.done(wrong.get());
        BehaviorProgram.Value value;
        Map<String, Long> expiry = Map.of();
        if (json.isEmpty()) { value = CLEARED; expiry = Map.of(flag.name(), System.currentTimeMillis()); }
        else {
            try {
                JsonElement parsed = JsonParser.parseString(json.get());
                value = BehaviorValues.read(parsed);
            } catch (JsonParseException | IllegalArgumentException | ArithmeticException invalid) {
                return api.done(NpcApi.invalid("flag-value", "$.value", "A flag holds one JSON value within the behavior value bounds, not null"));
            }
        }
        try {
            api.parts.runtime().behaviorState().write(subject(flag), Map.of(flag.name(), value), Map.of(flag.name(), declaration(flag)), expiry);
            return api.done(Outcome.ok(flag));
        } catch (BehaviorState.Rejected rejected) { return api.done(new Outcome<>(rejected.status(), Optional.empty(), java.util.List.of(rejected.diagnostic()))); }
        catch (IllegalStateException stopped) { return api.done(NpcApi.stopped()); }
    }

    CompletionStage<Outcome<UUID>> start(NpcHandle npc, String behavior, Optional<UUID> player, Map<String, String> variables) {
        Optional<NpcDefinition> definition = api.definition(npc.uuid());
        if (definition.isEmpty()) return api.done(NpcApi.missing());
        if (variables.size() > MAXIMUM_VARIABLES) return api.done(NpcApi.invalid("variables", "$.variables", "At most " + MAXIMUM_VARIABLES + " variables"));
        Map<String, Object> context = new LinkedHashMap<>();
        for (Map.Entry<String, String> variable : variables.entrySet()) {
            if (!variable.getKey().matches("[A-Za-z_][A-Za-z0-9_]{0,63}"))
                return api.done(NpcApi.invalid("variable-name", "$.variables", "Variable names are letters, digits and _, starting with a letter or _"));
            if (variable.getValue().length() > MAXIMUM_VALUE)
                return api.done(NpcApi.invalid("variable-value", "$.variables." + variable.getKey(), "A variable holds at most " + MAXIMUM_VALUE + " characters"));
            context.put(variable.getKey(), variable.getValue());
        }
        BehaviorCommands behaviors = api.parts.behaviors().get();
        if (behaviors == null) return api.done(Outcome.rejected(Outcome.Status.UNAVAILABLE, "behaviors-unavailable", "Behaviors are not running"));
        return api.delivered(behaviors.start(definition.get().id(), behavior, player, context));
    }

    CompletionStage<Outcome<Boolean>> stop(UUID run) {
        BehaviorCommands behaviors = api.parts.behaviors().get();
        if (behaviors == null) return api.done(Outcome.rejected(Outcome.Status.UNAVAILABLE, "behaviors-unavailable", "Behaviors are not running"));
        return api.delivered(behaviors.stop(run));
    }

    private static BehaviorState.Subject subject(Flag flag) {
        return new BehaviorState.Subject(BehaviorFlags.FLAGS + flag.namespace(), flag.npc().map(NpcHandle::uuid).orElse(NO_NPC), flag.player());
    }

    private static BehaviorProgram.ScopedVariable declaration(Flag flag) { return BehaviorFlags.declaration(flag.scope().name().toLowerCase(Locale.ROOT)); }

    private static <T> Optional<Outcome<T>> checked(Flag flag) {
        if (!flag.namespace().matches("[a-z0-9_-]+") || flag.namespace().length() > 64)
            return Optional.of(NpcApi.invalid("flag-namespace", "$.namespace", "A behavior namespace is lowercase letters, digits, _ or -, such as town"));
        if (flag.name().isBlank() || flag.name().length() > 64 || flag.name().codePoints().anyMatch(Character::isISOControl))
            return Optional.of(NpcApi.invalid("flag-name", "$.name", "A flag name is 1 to 64 characters without control characters"));
        boolean needsNpc = flag.scope() == Flag.Scope.NPC || flag.scope() == Flag.Scope.RELATIONSHIP;
        boolean needsPlayer = flag.scope() == Flag.Scope.PLAYER || flag.scope() == Flag.Scope.RELATIONSHIP;
        if (needsNpc && flag.npc().isEmpty()) return Optional.of(NpcApi.invalid("flag-npc", "$.npc", "An " + flag.scope().name().toLowerCase(Locale.ROOT) + " flag names its NPC"));
        if (needsPlayer && flag.player().isEmpty()) return Optional.of(NpcApi.invalid("flag-player", "$.player", "A " + flag.scope().name().toLowerCase(Locale.ROOT) + " flag names its player"));
        return Optional.empty();
    }
}
