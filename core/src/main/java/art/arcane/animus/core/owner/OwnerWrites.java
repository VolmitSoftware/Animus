package art.arcane.animus.core.owner;

import art.arcane.animus.core.behavior.catalog.BehaviorPath;
import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.editor.DocumentKind;
import art.arcane.animus.core.editor.EditorDocuments;
import art.arcane.animus.core.editor.EditorError;
import art.arcane.animus.core.npc.GroupDocument;
import art.arcane.animus.core.npc.GroupMerge;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.shop.ShopCatalog;
import art.arcane.animus.core.shop.ShopDocument;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.NpcPaths;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Checks every write of an NPC definition owned by a player before it lands, whoever makes it: what the definition would
 * newly do, with its trait groups merged, must be granted to its owner (see {@link OwnerNeeds}). What the stored version
 * already did stays, so an owner who loses a node keeps their NPC and an unrelated edit still saves; anything new is
 * refused with its reason. Runs inside the store's commit, so it may wait on an offline owner's permissions.
 * <p>
 * Work a player does from the menu or a command ({@link ActingPlayer}) is held to that player too, unless they are an
 * admin: they write only NPCs they own and keep owning, new routes, recordings, skins, trait groups and behaviors, and
 * existing ones of those only while every NPC that names them is theirs. Nothing else is theirs to write. With
 * {@code animus.npc.others} they write any NPC and document their nodes allow, but still change no NPC's owner.
 */
public final class OwnerWrites implements DocumentStore.CommitCheck {
    private final DocumentStore store;
    private final NpcRepository npcs;
    private final Supplier<OwnerPermissions> permissions;
    private final Supplier<Set<String>> privileged;

    public OwnerWrites(DocumentStore store, NpcRepository npcs, Supplier<OwnerPermissions> permissions, Supplier<Set<String>> privileged) {
        this.store = Objects.requireNonNull(store); this.npcs = Objects.requireNonNull(npcs);
        this.permissions = Objects.requireNonNull(permissions); this.privileged = Objects.requireNonNull(privileged);
    }

    /** Documents a player may add, and change while only their own NPCs name them. */
    private static final List<String> SHARED = List.of("skins/", "routes/", "recordings/", "groups/", "behaviors/");

    @Override public void check(List<DocumentStore.Change> changes) throws IOException {
        Optional<ActingPlayer.Actor> actor = ActingPlayer.current();
        if (actor.isPresent() && !actor.get().admin()) for (DocumentStore.Change change : changes) acting(actor.get(), change);
        Map<String, String> staged = new HashMap<>();
        for (DocumentStore.Change change : changes) if (change.content() != null) staged.put(change.path(), change.content());
        Sources sources = null;
        Map<String, OwnerPermissions.Answer> answers = new HashMap<>();
        for (DocumentStore.Change change : changes) {
            if (change.content() == null || !NpcPaths.file(change.path())) continue;
            JsonObject next = object(change.content());
            if (next == null || OwnerNeeds.owner(next).isEmpty()) continue;
            if (sources == null) sources = new Sources(staged);
            boolean player = actor.isPresent() && !actor.get().admin();
            granted(NpcPaths.idOf(change.path()), Optional.ofNullable(object(store.read(change.path()))), next, sources, answers, player);
        }
    }

    /**
     * The same check for an NPC that is never written, such as a plugin's temporary one: what {@code next} would newly do
     * over {@code previous} must be granted to its owner. May wait on an offline owner's permissions.
     */
    public void temporary(String id, Optional<JsonObject> previous, JsonObject next) throws IOException {
        if (OwnerNeeds.owner(next).isEmpty()) return;
        granted(id, previous, next, new Sources(Map.of()), new HashMap<>(), false);
    }

    /**
     * Refuses {@code next} when its owner lacks a node it newly needs; what {@code previous} already did under the same owner
     * stays. A plugin may put its own module data on the NPC freely, but a {@code player} writing that data themselves, in a
     * command or the menu, still needs {@code animus.owned.server} as for any other plugin's module.
     */
    private void granted(String id, Optional<JsonObject> previous, JsonObject next, Sources sources, Map<String, OwnerPermissions.Answer> answers, boolean player) throws IOException {
        UUID owner = OwnerNeeds.owner(next).orElseThrow();
        JsonObject effective = sources.effective(next);
        Set<OwnerNeed> needs = new LinkedHashSet<>(OwnerNeeds.definition(effective, sources));
        Optional<JsonObject> before = previous.map(sources::effective);
        if (before.isPresent() && OwnerNeeds.owner(previous.get()).equals(Optional.of(owner))) needs.removeAll(OwnerNeeds.definition(before.get(), sources));
        if (player)
            for (String module : sources.pluginModules())
                if (effective.get("modules") instanceof JsonObject modules && modules.has(module)
                        && !modules.get(module).equals(before.flatMap((JsonObject old) -> old.get("modules") instanceof JsonObject kept ? Optional.ofNullable(kept.get(module)) : Optional.empty()).orElse(null)))
                    needs.add(new OwnerNeed(PermissionNodes.OWNED_SERVER, "change a plugin's module data", "module " + module));
        for (OwnerNeed need : needs) {
            OwnerPermissions.Answer answer = answers.computeIfAbsent(owner + " " + need.node(), (String key) -> permissions.get().has(owner, need.node()));
            if (answer != OwnerPermissions.Answer.YES) throw new IOException(need.refusal(id));
        }
    }

    /** Documents as the store holds them now, for checks outside a commit such as what the menu offers. File lane. */
    public static OwnerNeeds.Sources sources(DocumentStore store, NpcRepository npcs, Set<String> privileged) {
        return new OwnerWrites(store, npcs, () -> OwnerPermissions.NONE, () -> privileged).new Sources(Map.of());
    }

    /** Refuses a change the acting player may not make: see the class comment. */
    private void acting(ActingPlayer.Actor actor, DocumentStore.Change change) throws IOException {
        String path = change.path();
        if (path.startsWith("state/")) return;
        JsonObject previous = object(store.read(path));
        if (actor.others()) {
            // A new file keeps whatever owner it names: undo brings a deleted NPC back with the owner it had.
            JsonObject next = change.content() == null ? null : object(change.content());
            if (NpcPaths.file(path) && previous != null && next != null && !OwnerNeeds.owner(previous).equals(OwnerNeeds.owner(next)))
                throw new IOException(NpcOwnership.OWNER_REFUSAL);
            return;
        }
        Optional<UUID> self = Optional.of(actor.player());
        if (NpcPaths.file(path)) {
            String id = NpcPaths.idOf(path);
            if (previous != null && !OwnerNeeds.owner(previous).equals(self)) throw new IOException(id + " is not yours; you can change only NPCs you own");
            if (change.content() == null) return;
            JsonObject next = object(change.content());
            if (next == null || !OwnerNeeds.owner(next).equals(self))
                throw new IOException(previous == null ? "A new NPC is owned by whoever makes it" : NpcOwnership.OWNER_REFUSAL);
            return;
        }
        if (path.startsWith(ShopCatalog.SHOP_FOLDER + "/")) { shop(actor.player(), path, change.content()); return; }
        if (SHARED.stream().noneMatch(path::startsWith)) throw new IOException("Only an admin can change " + path);
        if (store.read(path) == null) return;
        String id = sharedId(path);
        for (NpcDefinition definition : npcs.stored())
            if (!definition.owner().equals(self) && mentions(definition.json(), id))
                throw new IOException(path + " is used by " + definition.id() + ", which is not yours; only an admin can change it");
    }

    /**
     * A player's own shop: they change only shops they own and keep owning, each keeping its stock in chests, so it can
     * trade only what its chests hold. The shop must load, which refuses commands and money in such a shop.
     */
    private void shop(UUID self, String path, String content) throws IOException {
        String stored = store.read(path);
        if (stored != null && !shopOwner(object(stored)).equals(Optional.of(self))) throw new IOException(path + " is not your shop; only an admin can change it");
        if (content == null) return;
        JsonObject next = object(content);
        if (!shopOwner(next).equals(Optional.of(self)))
            throw new IOException(stored == null ? "A shop you make is yours; only an admin can make one for another player" : "Only an admin can change who owns a shop");
        if (!(next.get("containers") instanceof JsonArray chests) || chests.isEmpty()) throw new IOException("A shop you make keeps its stock in chests");
        Optional<String> id = EditorDocuments.idFor(DocumentKind.SHOP, path);
        if (id.isEmpty()) throw new IOException(path + " is not a shop file");
        List<String> problems = ShopDocument.parse(id.get(), next).problems();
        if (!problems.isEmpty()) throw new IOException(problems.getFirst());
    }

    /** The owner a shop file names; none for a malformed one. */
    private static Optional<UUID> shopOwner(JsonObject shop) {
        if (shop == null || !(shop.get("owner") instanceof JsonObject owner) || !(owner.get("uuid") instanceof JsonElement uuid) || !uuid.isJsonPrimitive()) return Optional.empty();
        try { return Optional.of(UUID.fromString(uuid.getAsString())); }
        catch (IllegalArgumentException invalid) { return Optional.empty(); }
    }

    /** The id NPC definitions name a shared document by. */
    private static String sharedId(String path) {
        if (path.startsWith("behaviors/")) {
            try { return BehaviorPath.idFor(path); }
            catch (IllegalArgumentException unnamed) { return path; }
        }
        String file = path.substring(path.indexOf('/') + 1);
        return file.endsWith(".json") ? file.substring(0, file.length() - ".json".length()) : file;
    }

    /** Whether any string in the definition, at any depth, is {@code id}. */
    private static boolean mentions(JsonElement element, String id) {
        if (element.isJsonPrimitive()) return element.getAsJsonPrimitive().isString() && element.getAsString().equals(id);
        if (element.isJsonArray()) { for (JsonElement item : element.getAsJsonArray()) if (mentions(item, id)) return true; return false; }
        if (element.isJsonObject()) for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) if (mentions(entry.getValue(), id)) return true;
        return false;
    }

    private static JsonObject object(String content) {
        if (content == null) return null;
        try { return JsonParser.parseString(content) instanceof JsonObject object ? object : null; }
        catch (JsonParseException unreadable) { return null; }
    }

    /** Documents as this commit leaves them: its own writes first, then the store. */
    private final class Sources implements OwnerNeeds.Sources {
        private final Map<String, String> staged;
        private final Map<UUID, NpcDefinition> byUuid = new HashMap<>();

        Sources(Map<String, String> staged) {
            this.staged = staged;
            for (NpcDefinition definition : npcs.snapshot().values()) byUuid.put(definition.uuid(), definition);
        }

        private Optional<String> read(String path) throws IOException { return staged.containsKey(path) ? Optional.of(staged.get(path)) : Optional.ofNullable(store.read(path)); }

        private Optional<JsonElement> json(String path) {
            try { return read(path).flatMap((String text) -> { try { return Optional.of(JsonParser.parseString(text)); } catch (JsonParseException unreadable) { return Optional.empty(); } }); }
            catch (IOException unreadable) { return Optional.empty(); }
        }

        /** The definition with its trait groups merged under it, this commit's groups first; a missing group adds nothing. */
        JsonObject effective(JsonObject npc) {
            if (!(npc.get("groups") instanceof JsonArray listed)) return npc;
            List<GroupDocument> groups = new ArrayList<>();
            for (JsonElement group : listed) {
                if (!group.isJsonPrimitive()) continue;
                String id = group.getAsString(), path = "groups/" + id + ".json";
                Optional<GroupDocument> found = staged.containsKey(path) ? GroupDocument.parse(id, staged.get(path)).group() : npcs.groups().find(id);
                found.ifPresent(groups::add);
            }
            return groups.isEmpty() ? npc : GroupMerge.merge(npc, groups).effective();
        }

        @Override public Optional<JsonElement> behavior(String id) {
            String loose;
            try { loose = BehaviorPath.forId(id); }
            catch (IllegalArgumentException invalid) { return Optional.empty(); }
            Optional<JsonElement> found = json(loose);
            if (found.isPresent()) return found;
            for (String pack : packs()) {
                Optional<JsonElement> packed = json(BehaviorPath.forId(id, pack));
                if (packed.isPresent()) return packed;
            }
            return Optional.empty();
        }

        private List<String> packs() {
            try {
                Path folder = store.resolve("packs");
                if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) return List.of();
                try (Stream<Path> listed = Files.list(folder)) {
                    Set<String> names = new HashSet<>();
                    listed.filter((Path pack) -> Files.isDirectory(pack, LinkOption.NOFOLLOW_LINKS)).forEach((Path pack) -> names.add(pack.getFileName().toString()));
                    return names.stream().filter((String name) -> name.matches("[a-z0-9_-]{1,64}")).sorted().toList();
                }
            } catch (IOException unreadable) { return List.of(); }
        }

        @Override public Optional<JsonElement> document(DocumentKind kind, String id) {
            try { return json(EditorDocuments.pathFor(kind, id)); }
            catch (EditorError notAnId) { return Optional.empty(); }
        }

        @Override public Optional<NpcDefinition> npc(UUID uuid) { return Optional.ofNullable(byUuid.get(uuid)); }

        @Override public Optional<NpcDefinition> npc(String id) { return npcs.find(id); }

        @Override public Set<String> privileged() { return privileged.get(); }

        @Override public Set<String> pluginModules() { return npcs.moduleKeys(); }
    }
}
