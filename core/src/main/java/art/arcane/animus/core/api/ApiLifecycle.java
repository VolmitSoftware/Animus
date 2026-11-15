package art.arcane.animus.core.api;

import art.arcane.animus.api.DefinitionSnapshot;
import art.arcane.animus.api.NpcHandle;
import art.arcane.animus.api.NpcLocation;
import art.arcane.animus.api.NpcSpec;
import art.arcane.animus.api.NpcState;
import art.arcane.animus.api.Outcome;
import art.arcane.animus.api.RevisionToken;
import art.arcane.animus.core.admin.Administration;
import art.arcane.animus.core.mount.MountDeclaration;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.storage.JsonDocuments;
import art.arcane.animus.core.storage.NpcPaths;
import art.arcane.animus.core.text.ChatLine;
import art.arcane.animus.core.virtual.VirtualNpcState;
import art.arcane.animus.core.virtual.WorldPosition;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/** Creating, editing and removing NPCs for the public service: saved through the file queue, temporary in memory. */
final class ApiLifecycle<P, I> {
    private final NpcApi<P, I> api;

    ApiLifecycle(NpcApi<P, I> api) { this.api = api; }

    /** A definition change worked out on the file queue: the new JSON and the stored version it was based on. */
    private record Change(JsonObject json, String id, boolean temporary, String hash, long revision, boolean activate) {}

    CompletionStage<Outcome<DefinitionSnapshot>> create(String owner, NpcSpec spec, boolean temporary) {
        Optional<String> given = spec.id();
        if (given.isEmpty() && !temporary) return api.done(NpcApi.invalid("id-missing", "$.id", "A persistent NPC needs an id"));
        String id = given.orElseGet(ApiLifecycle::generatedId);
        if (!id.matches(NpcPaths.SEGMENT)) return api.done(NpcApi.invalid("id-invalid", "$.id", "NPC ids are 1-64 lowercase letters, digits, _ or -"));
        return Outcomes.then(api.control.skinHash(owner, spec.skin()), (Optional<String> skin) -> Outcomes.then(api.files(() -> {
            NpcRepository repository = api.parts.runtime().npcs();
            if (repository.find(id).isPresent() || repository.stored().stream().anyMatch((NpcDefinition stored) -> stored.id().equals(id)))
                return Outcome.rejected(Outcome.Status.CONFLICT, "npc-id-taken", "An NPC named " + id + " already exists");
            NpcSpecs.Built built = NpcSpecs.create(spec, id, UUID.randomUUID(), true, skin, api.parts.ports()::worldName);
            if (!built.valid()) return NpcApi.invalid(built.problems());
            Optional<String> body = Administration.bodyProblem(api.parts.runtime(), built.json().get("type").getAsString());
            if (body.isPresent()) return NpcApi.invalid("body-unavailable", "$.type", body.get().substring("$.type: ".length()));
            List<String> modules = repository.moduleProblems(Optional.empty(), built.json());
            if (!modules.isEmpty()) return NpcApi.invalid(NpcSpecs.diagnostics(modules));
            return Outcome.ok(new Change(built.json(), id, temporary, "missing", 0, true));
        }), (Change change) -> write(owner, change, "created")));
    }

    CompletionStage<Outcome<DefinitionSnapshot>> edit(String owner, NpcHandle npc, NpcSpec spec) {
        return Outcomes.then(api.control.skinHash(owner, spec.skin()), (Optional<String> skin) -> Outcomes.then(read(npc, (JsonObject current) -> NpcSpecs.patch(current, spec, skin, api.parts.ports()::worldName)),
                (Change change) -> write(owner, change, "edited")));
    }

    CompletionStage<Outcome<DefinitionSnapshot>> updateModule(String owner, NpcHandle npc, String key, UnaryOperator<Optional<String>> update) {
        return api.files(() -> {
            Outcome<Change> change = change(npc, (JsonObject current) -> {
                Optional<String> before = current.has("modules") && current.get("modules").isJsonObject() && current.getAsJsonObject("modules").get(key) instanceof JsonObject data
                        ? Optional.of(JsonDocuments.write(data)) : Optional.empty();
                Optional<String> after = java.util.Objects.requireNonNull(update.apply(before), "the update returned null");
                return NpcSpecs.module(current, key, after);
            });
            return change.status() == Outcome.Status.OK ? save(owner, change.value().orElseThrow(), null) : Outcomes.retype(change);
        });
    }

    CompletionStage<Outcome<NpcHandle>> remove(String owner, NpcHandle npc) {
        return api.files(() -> {
            NpcRepository repository = api.parts.runtime().npcs();
            Optional<NpcDefinition> definition = api.definition(npc.uuid());
            if (definition.isEmpty()) return NpcApi.missing();
            String id = definition.get().id();
            if (repository.temporary(id).isPresent()) { repository.removeTemporary(id); return Outcome.ok(npc); }
            try {
                String transaction = repository.delete(id);
                api.parts.log().accept(owner + " removed " + id + "; /animus undo " + ChatLine.shortId(transaction) + " brings it back");
                return Outcome.ok(npc);
            } catch (IOException refused) { return failed(refused); }
        });
    }

    CompletionStage<Outcome<NpcState>> state(NpcHandle npc) {
        Optional<NpcDefinition> found = api.definition(npc.uuid());
        if (found.isEmpty()) return api.done(NpcApi.missing());
        NpcDefinition definition = found.get();
        Optional<NpcRepository.Temporary> temporary = api.parts.runtime().npcs().temporary(definition.id());
        JsonObject position = definition.json().getAsJsonObject("position");
        float yaw = position.get("yaw").getAsFloat(), pitch = position.get("pitch").getAsFloat();
        Optional<VirtualNpcState.Snapshot> live = api.parts.living().state(npc.uuid()).filter(VirtualNpcState.Snapshot::active);
        NpcLocation location = live.map((VirtualNpcState.Snapshot snapshot) -> location(snapshot.position(), yaw, pitch))
                .orElseGet(() -> new NpcLocation(position.get("world").getAsString(), position.get("x").getAsDouble(), position.get("y").getAsDouble(), position.get("z").getAsDouble(), yaw, pitch));
        return api.done(Outcome.ok(new NpcState(npc, definition.id(), temporary.isPresent(), temporary.map(NpcRepository.Temporary::owner), live.isPresent(), location)));
    }

    /** Puts {@code npc} on another NPC or on a real entity, through its {@code animus:mount} module. */
    CompletionStage<Outcome<NpcHandle>> mount(String owner, NpcHandle npc, UUID vehicle) {
        if (npc.uuid().equals(vehicle)) return api.done(NpcApi.invalid("mount-self", "$.vehicle", "An NPC cannot ride itself"));
        Optional<NpcDefinition> carrier = api.definition(vehicle);
        CompletionStage<Outcome<JsonObject>> module = carrier.isPresent()
                ? api.done(Outcome.ok(mountModule("vehicle", carrier.get().id())))
                : api.onNpc(npc, (art.arcane.animus.core.runtime.NpcRuntime.ActiveNpc active) -> api.parts.ports().entity(vehicle).isPresent()
                        ? Outcome.ok(mountModule("entity", vehicle.toString()))
                        : Outcome.rejected(Outcome.Status.NOT_FOUND, "vehicle-missing", "No NPC or entity with that UUID is near the NPC"));
        return Outcomes.then(module, (JsonObject data) -> handle(npc, Outcomes.then(read(npc, (JsonObject current) -> NpcSpecs.module(current, MountDeclaration.MODULE, Optional.of(JsonDocuments.write(data)))),
                (Change change) -> write(owner, change, "mounted"))));
    }

    CompletionStage<Outcome<NpcHandle>> dismount(String owner, NpcHandle npc) {
        return handle(npc, Outcomes.then(read(npc, (JsonObject current) -> NpcSpecs.module(current, MountDeclaration.MODULE, Optional.empty())),
                (Change change) -> write(owner, change, "dismounted")));
    }

    // ---- Shared steps ----

    /** On the file queue: the NPC's current definition through {@code edit}, ready to write. */
    private CompletionStage<Outcome<Change>> read(NpcHandle npc, Function<JsonObject, NpcSpecs.Built> edit) {
        return api.files(() -> change(npc, edit));
    }

    private Outcome<Change> change(NpcHandle npc, Function<JsonObject, NpcSpecs.Built> edit) {
        NpcRepository repository = api.parts.runtime().npcs();
        Optional<NpcDefinition> definition = api.definition(npc.uuid());
        if (definition.isEmpty()) return NpcApi.missing();
        String id = definition.get().id();
        Optional<NpcRepository.Temporary> temporary = repository.temporary(id);
        try {
            NpcDefinition before;
            String hash;
            JsonObject current;
            if (temporary.isPresent()) { before = temporary.get().definition(); current = before.json(); hash = ""; }
            else {
                NpcRepository.Draft draft = repository.draft(id);
                before = NpcDefinition.parse(JsonDocuments.write(draft.document()));
                current = draft.document(); hash = draft.expectedHash();
            }
            NpcSpecs.Built built = edit.apply(current);
            if (!built.valid()) return NpcApi.invalid(built.problems());
            String type = built.json().get("type").getAsString();
            if (!type.equals(before.json().get("type").getAsString())) {
                Optional<String> body = Administration.bodyProblem(api.parts.runtime(), type);
                if (body.isPresent()) return NpcApi.invalid("body-unavailable", "$.type", body.get().substring("$.type: ".length()));
            }
            boolean activate = built.json().get("spawned").getAsBoolean() && !NpcDefinition.parse(JsonDocuments.write(built.json())).runtimeForm().equals(before.runtimeForm());
            return Outcome.ok(new Change(built.json(), id, temporary.isPresent(), hash, before.revision(), activate));
        } catch (IOException invalid) { return failed(invalid); }
    }

    /** Checks a definition that goes live where the runtime would, then saves it on the file queue. */
    private CompletionStage<Outcome<DefinitionSnapshot>> write(String owner, Change change, String verb) {
        if (!change.activate()) return api.files(() -> save(owner, change, verb));
        NpcDefinition candidate;
        try { candidate = NpcDefinition.parse(JsonDocuments.write(change.json())); }
        catch (IOException invalid) { return api.done(failed(invalid)); }
        boolean appears = !api.live(candidate.uuid());
        return api.delivered(api.activation(candidate).thenCompose((List<String> problems) -> problems.isEmpty()
                ? Outcomes.then(api.files(() -> save(owner, change, verb)), (DefinitionSnapshot saved) -> appears ? active(saved) : api.done(Outcome.ok(saved)))
                : CompletableFuture.completedFuture(NpcApi.invalid(NpcSpecs.diagnostics(problems)))));
    }

    /** The saved snapshot once the NPC's body is active, so the caller can drive it at once; a dormant one is still saved. */
    private CompletionStage<Outcome<DefinitionSnapshot>> active(DefinitionSnapshot saved) {
        return api.delivered(api.untilActive(saved.npc().uuid()).thenApply((Boolean active) -> Outcome.ok(saved)));
    }

    /** Saves the change; {@code verb} names it in the console, or null for a quiet module update. */
    private Outcome<DefinitionSnapshot> save(String owner, Change change, String verb) {
        NpcRepository repository = api.parts.runtime().npcs();
        try {
            if (change.temporary()) {
                NpcDefinition saved = change.revision() == 0 ? repository.addTemporary(owner, change.json()) : repository.replaceTemporary(change.id(), withRevision(change.json(), change.revision()));
                return Outcome.ok(snapshot(saved, new RevisionToken(saved.revision(), JsonDocuments.hash(saved.serialize())), saved.serialize()));
            }
            NpcDefinition parsed = NpcDefinition.parse(JsonDocuments.write(change.json()));
            String transaction = repository.apply(new NpcRepository.Draft(parsed.path(), change.hash(), change.revision(), change.json()));
            if (verb != null) api.parts.log().accept(owner + " " + verb + " " + change.id() + "; /animus undo " + ChatLine.shortId(transaction) + " reverts it");
            NpcRepository.Draft draft = repository.draft(change.id());
            NpcDefinition saved = NpcDefinition.parse(JsonDocuments.write(draft.document()));
            return Outcome.ok(snapshot(saved, new RevisionToken(draft.expectedRevision(), draft.expectedHash()), JsonDocuments.write(draft.document())));
        } catch (IOException refused) { return failed(refused); }
    }

    private static JsonObject withRevision(JsonObject json, long revision) {
        JsonObject copy = json.deepCopy();
        copy.addProperty("revision", revision);
        return copy;
    }

    static DefinitionSnapshot snapshot(NpcDefinition definition, RevisionToken token, String json) {
        return new DefinitionSnapshot(new NpcHandle(definition.uuid()), definition.id(), definition.group(), token, json);
    }

    private NpcLocation location(WorldPosition position, float yaw, float pitch) {
        return new NpcLocation(api.parts.ports().worldName(position.world()), position.point().x(), position.point().y(), position.point().z(), yaw, pitch);
    }

    private static JsonObject mountModule(String field, String value) {
        JsonObject module = new JsonObject();
        module.addProperty(field, value);
        return module;
    }

    /** A file refusal: CONFLICT for a changed file or revision, otherwise INVALID with the parser's field. */
    static <T> Outcome<T> failed(IOException refused) {
        String message = String.valueOf(refused.getMessage());
        if (message.toLowerCase(java.util.Locale.ROOT).contains("conflict") || message.contains("collision") || message.contains("already exists"))
            return Outcome.rejected(Outcome.Status.CONFLICT, "definition-conflict", message.substring(0, Math.min(message.length(), 1000)));
        return NpcApi.invalid(NpcSpecs.diagnostics(List.of(message.split("; "))));
    }

    private static String generatedId() { return "temp-" + HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextInt()); }

    private static CompletionStage<Outcome<NpcHandle>> handle(NpcHandle npc, CompletionStage<Outcome<DefinitionSnapshot>> stage) {
        return stage.thenApply((Outcome<DefinitionSnapshot> outcome) -> outcome.status() == Outcome.Status.OK ? Outcome.ok(npc) : Outcomes.retype(outcome));
    }
}
