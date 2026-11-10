package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.editor.DocumentKind;
import art.arcane.animus.core.editor.EditorDocuments;
import art.arcane.animus.core.npc.DefinitionBatch;
import art.arcane.animus.core.npc.GroupDocument;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import art.arcane.animus.core.storage.NpcPaths;
import art.arcane.animus.core.virtual.Vec3;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The toolkit's file work, on the file lane. Every change to several NPCs is one transaction with one rollback receipt,
 * refused whole when any result is invalid. Lines read like the matching {@code /animus} command's reply.
 */
final class ToolkitFiles {
    static final String PLAYER = "minecraft:player", NAVIGATION = "animus:navigation";
    private static final String COPY = "-copy";
    private final NpcRepository npcs;
    private final DocumentStore store;

    ToolkitFiles(NpcRepository npcs, DocumentStore store) { this.npcs = npcs; this.store = store; }

    /** Drafts and the definitions they become, for a spawn the owner checks before it is applied. */
    record Batch(List<NpcRepository.Draft> drafts, List<NpcDefinition> candidates) {
        Batch { drafts = List.copyOf(drafts); candidates = List.copyOf(candidates); }
    }

    /** A skin file and who wears it. */
    record Skin(String hash, List<String> npcs, List<String> groups) {
        Skin { npcs = List.copyOf(npcs); groups = List.copyOf(groups); }
    }

    @FunctionalInterface private interface Edit { boolean change(JsonObject document); }

    /** Adds or removes the trait group on every listed NPC. */
    String groups(String group, List<String> ids, boolean assign) throws IOException {
        if (assign && npcs.groups().find(group).isEmpty()) throw new IOException("Unknown group " + group + "; save groups/" + group + ".json, then /animus reload");
        int[] unchanged = {0};
        List<NpcRepository.Draft> drafts = drafts(ids, (JsonObject document) -> {
            List<String> listed = strings(document.get("groups"));
            if (listed.contains(group) == assign) { unchanged[0]++; return false; }
            JsonArray next = new JsonArray();
            for (String existing : listed) if (!existing.equals(group)) next.add(existing);
            if (assign) next.add(group);
            if (next.isEmpty()) document.remove("groups"); else document.add("groups", next);
            return true;
        });
        String head = (assign ? "Assigned " + group + " to " : "Unassigned " + group + " from ") + named(drafts) + (unchanged[0] == 0 ? "" : ", " + unchanged[0] + " unchanged");
        return drafts.isEmpty() ? head : head + "; tx " + npcs.applyAll(drafts);
    }

    /** Moves every listed NPC into the folder under {@code npcs/}, renaming the files in one transaction. */
    String folder(String folder, List<String> ids) throws IOException {
        if (!NpcPaths.folder(folder)) throw new IOException("Folder " + folder + " is not lowercase words split by /");
        List<NpcDefinition> moved = new ArrayList<>();
        Map<String, String> expected = new HashMap<>();
        List<DocumentStore.Change> removals = new ArrayList<>();
        for (String id : ids) {
            NpcRepository.Draft draft = npcs.draft(id);
            JsonObject document = draft.document();
            if (document.get("group").getAsString().equals(folder)) continue;
            document.addProperty("group", folder);
            document.addProperty("revision", draft.expectedRevision() + 1);
            NpcDefinition next = NpcDefinition.parse(JsonDocuments.write(document));
            if (store.read(next.path()) != null) throw new IOException(next.path() + " already exists");
            moved.add(next);
            expected.put(next.path(), "missing");
            removals.add(new DocumentStore.Change(draft.path(), draft.expectedHash(), null));
            pointer(draft.path()).ifPresent(removals::add);
        }
        String where = "npcs/" + (folder.isEmpty() ? "" : folder + "/");
        if (moved.isEmpty()) return "Already in " + where;
        String tx = DefinitionBatch.commit(store, moved, expected, removals);
        npcs.reload();
        return "Moved " + count(moved.size()) + " to " + where + "; tx " + tx;
    }

    /** Points every listed NPC at {@code routes/<route>.json}, replacing its own route settings. */
    String route(String route, List<String> ids) throws IOException {
        if (!route.matches(NpcPaths.SEGMENT) || store.read("routes/" + route + ".json") == null) throw new IOException("No route routes/" + route + ".json");
        List<NpcRepository.Draft> drafts = drafts(ids, (JsonObject document) -> {
            JsonObject modules = document.has("modules") && document.get("modules").isJsonObject() ? document.getAsJsonObject("modules") : new JsonObject();
            JsonObject previous = modules.has(NAVIGATION) && modules.get(NAVIGATION).isJsonObject() ? modules.getAsJsonObject(NAVIGATION) : new JsonObject();
            JsonObject navigation = new JsonObject();
            navigation.addProperty("enabled", true);
            navigation.addProperty("route", route);
            if (navigation.equals(previous)) return false;
            modules.add(NAVIGATION, navigation);
            document.add("modules", modules);
            return true;
        });
        if (drafts.isEmpty()) return "Route " + route + " already set";
        return "Route " + route + " on " + named(drafts) + "; tx " + npcs.applyAll(drafts);
    }

    /** Sets or, when empty, removes the skin of every listed player body; other bodies are skipped. */
    String skin(Optional<String> hash, List<String> ids) throws IOException {
        if (hash.isPresent() && store.read("skins/" + hash.get() + ".json") == null) throw new IOException("No skin file skins/" + hash.get() + ".json");
        int[] skipped = {0};
        List<NpcRepository.Draft> drafts = drafts(ids, (JsonObject document) -> {
            if (!PLAYER.equals(document.get("type").getAsString())) { skipped[0]++; return false; }
            JsonElement current = document.get("skin");
            if (hash.isEmpty()) return document.remove("skin") != null;
            if (current != null && current.isJsonPrimitive() && current.getAsString().equals(hash.get())) return false;
            document.addProperty("skin", hash.get());
            return true;
        });
        if (drafts.isEmpty() && skipped[0] == ids.size()) return "No player bodies in the selection; skins only show on " + PLAYER;
        Optional<String> label = hash.isPresent() ? SkinFiles.label(store.read("skins/" + hash.get() + ".json")) : Optional.empty();
        String head = "Skin " + label.map((String name) -> name + " ").orElse("") + hash.orElse("removed") + " on " + named(drafts) + (skipped[0] == 0 ? "" : ", " + skipped[0] + " skipped (not a player body)");
        return drafts.isEmpty() ? head : head + "; tx " + npcs.applyAll(drafts);
    }

    /** Saves a captured route as {@code wp-<player>}, or the first free {@code wp-<player>-<n>}; returns the id. */
    String saveRoute(String player, String document) throws IOException {
        String base = "wp-" + player.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
        base = base.substring(0, Math.min(base.length(), 58));
        String id = base;
        for (int n = 2; store.read("routes/" + id + ".json") != null; n++) id = base + "-" + n;
        store.commit(List.of(new DocumentStore.Change("routes/" + id + ".json", "missing", document)));
        return id;
    }

    /** Saves where the NPC stands, keeping its world. */
    String position(String id, Vec3 at, float yaw, float pitch) throws IOException {
        List<NpcRepository.Draft> drafts = drafts(List.of(id), (JsonObject document) -> {
            JsonObject position = document.getAsJsonObject("position");
            position.addProperty("x", at.x()); position.addProperty("y", at.y()); position.addProperty("z", at.z());
            position.addProperty("yaw", yaw); position.addProperty("pitch", pitch);
            return true;
        });
        return "Moved " + id + " here; tx " + npcs.applyAll(drafts);
    }

    /** The drafts that spawn or despawn the listed NPCs; NPCs already in that state are left out. */
    Batch spawned(List<String> ids, boolean spawn) throws IOException {
        List<NpcRepository.Draft> drafts = drafts(ids, (JsonObject document) -> {
            JsonElement current = document.get("spawned");
            if (current != null && current.isJsonPrimitive() && current.getAsBoolean() == spawn) return false;
            document.addProperty("spawned", spawn);
            return true;
        });
        List<NpcDefinition> candidates = new ArrayList<>();
        for (NpcRepository.Draft draft : drafts) candidates.add(NpcDefinition.parse(JsonDocuments.write(draft.document())));
        return new Batch(drafts, candidates);
    }

    String apply(Batch batch, boolean spawn) throws IOException {
        if (batch.drafts().isEmpty()) return spawn ? "Already spawned" : "Already despawned";
        return (spawn ? "Spawning " : "Despawned ") + count(batch.drafts().size()) + "; tx " + npcs.applyAll(batch.drafts());
    }

    /** A new NPC's file, checked and ready to write. */
    record Created(NpcDefinition definition, NpcRepository.Draft draft) {}

    /**
     * The file {@code /animus create} would write for a new NPC: {@code npcs/<id>.json} with the id as its name and
     * {@code owner}, the player who made it, as its owner. Refused when the id is taken or malformed.
     */
    Created draft(String id, String type, String world, Vec3 at, float yaw, UUID owner, boolean spawn) throws IOException {
        if (!id.matches(NpcPaths.SEGMENT)) throw new IOException("An id is 1 to 64 lowercase letters, digits, _ or -");
        if (npcs.stored().stream().anyMatch((NpcDefinition existing) -> existing.id().equals(id))) throw new IOException("An NPC named " + id + " already exists");
        JsonObject document = new JsonObject();
        document.addProperty("schemaVersion", NpcDefinition.SCHEMA_VERSION); document.addProperty("revision", 1);
        document.addProperty("uuid", UUID.randomUUID().toString()); document.addProperty("id", id);
        document.addProperty("type", type); document.addProperty("group", "");
        document.addProperty("name", id);
        document.addProperty("spawned", spawn);
        document.addProperty("owner", owner.toString());
        JsonObject position = new JsonObject();
        position.addProperty("world", world);
        position.addProperty("x", Math.round(at.x() * 100) / 100.0); position.addProperty("y", Math.round(at.y() * 100) / 100.0); position.addProperty("z", Math.round(at.z() * 100) / 100.0);
        position.addProperty("yaw", Math.round(yaw * 100) / 100f); position.addProperty("pitch", 0);
        document.add("position", position);
        NpcDefinition definition = NpcDefinition.parse(JsonDocuments.write(document));
        return new Created(definition, new NpcRepository.Draft(definition.path(), "missing", 0, document));
    }

    /** Writes the new NPC in one transaction. */
    String create(Created created) throws IOException {
        String tx = npcs.apply(created.draft());
        return "Created " + created.definition().path() + (created.definition().spawned() ? " and spawned it" : ", despawned") + "; tx " + tx;
    }

    /** Deletes every listed NPC in one transaction; rollback restores them all. */
    String delete(List<String> ids) throws IOException {
        List<DocumentStore.Change> changes = new ArrayList<>();
        for (String id : ids) {
            NpcRepository.Draft draft = npcs.draft(id);
            changes.add(new DocumentStore.Change(draft.path(), draft.expectedHash(), null));
            pointer(draft.path()).ifPresent(changes::add);
        }
        if (changes.isEmpty()) return "Nothing to delete";
        String tx = store.commit(changes);
        npcs.reload();
        return "Deleted " + (ids.size() == 1 ? ids.getFirst() : count(ids.size())) + "; tx " + tx;
    }

    /** Copies every listed NPC as a dormant {@code <id>-copy}, numbered when that id is taken. */
    String copies(List<String> ids) throws IOException {
        Set<String> taken = new HashSet<>();
        for (NpcDefinition definition : npcs.stored()) taken.add(definition.id());
        List<NpcDefinition> copies = new ArrayList<>();
        Map<String, String> expected = new LinkedHashMap<>();
        for (String id : ids) {
            JsonObject document = npcs.draft(id).document();
            String base = id.substring(0, Math.min(id.length(), 64 - COPY.length() - 4)) + COPY, copy = base;
            for (int n = 2; taken.contains(copy); n++) copy = base + "-" + n;
            taken.add(copy);
            document.addProperty("id", copy);
            document.addProperty("uuid", UUID.randomUUID().toString());
            document.addProperty("revision", 1);
            document.addProperty("spawned", false);
            document.remove("migration");
            NpcDefinition definition = NpcDefinition.parse(JsonDocuments.write(document));
            if (store.read(definition.path()) != null) throw new IOException(definition.path() + " already exists");
            copies.add(definition);
            expected.put(definition.path(), "missing");
        }
        if (copies.isEmpty()) return "Nothing to clone";
        String tx = DefinitionBatch.commit(store, copies, expected, List.of());
        npcs.reload();
        List<String> names = copies.stream().map(NpcDefinition::id).toList();
        return (ids.size() == 1 ? "Cloned " + ids.getFirst() + " as dormant " + names.getFirst() : "Cloned " + count(ids.size()) + " as dormant " + String.join(", ", names)) + "; tx " + tx;
    }

    /** Saved route ids. */
    List<String> routes() throws IOException {
        return new EditorDocuments(store, () -> 0).list(Set.of(DocumentKind.ROUTE)).stream().map(EditorDocuments.Location::id).toList();
    }

    /** Every skin file with the NPCs and trait groups that use it; most used first. */
    List<Skin> skins() throws IOException {
        Map<String, List<String>> wearers = new TreeMap<>(), groups = new TreeMap<>();
        for (EditorDocuments.Location location : new EditorDocuments(store, () -> 0).list(Set.of(DocumentKind.SKIN))) {
            wearers.put(location.id(), new ArrayList<>());
            groups.put(location.id(), new ArrayList<>());
        }
        for (NpcDefinition definition : npcs.stored()) {
            JsonElement skin = definition.json().get("skin");
            if (skin != null && skin.isJsonPrimitive() && wearers.containsKey(skin.getAsString())) wearers.get(skin.getAsString()).add(definition.id());
        }
        for (GroupDocument group : npcs.groups().groups().values()) {
            JsonObject values = group.values();
            List<String> pool = new ArrayList<>(strings(values.get("skins")));
            if (values.has("skin") && values.get("skin").isJsonPrimitive()) pool.add(values.get("skin").getAsString());
            for (String hash : pool) if (groups.containsKey(hash) && !groups.get(hash).contains(group.id())) groups.get(hash).add(group.id());
        }
        List<Skin> skins = new ArrayList<>();
        for (String hash : wearers.keySet()) skins.add(new Skin(hash, wearers.get(hash).stream().sorted().toList(), groups.get(hash).stream().sorted().toList()));
        skins.sort((Skin a, Skin b) -> Integer.compare(b.npcs().size() + b.groups().size(), a.npcs().size() + a.groups().size()));
        return skins;
    }

    private List<NpcRepository.Draft> drafts(List<String> ids, Edit edit) throws IOException {
        List<NpcRepository.Draft> drafts = new ArrayList<>();
        for (String id : ids) {
            NpcRepository.Draft draft = npcs.draft(id);
            JsonObject document = draft.document();
            if (edit.change(document)) drafts.add(new NpcRepository.Draft(draft.path(), draft.expectedHash(), draft.expectedRevision(), document));
        }
        return drafts;
    }

    /** The last-valid copy of a definition file, removed with the file so recovery cannot bring it back. */
    private Optional<DocumentStore.Change> pointer(String path) throws IOException {
        String pointer = DocumentStore.DEFINITION_POINTERS + path, current = store.read(pointer);
        return current == null ? Optional.empty() : Optional.of(new DocumentStore.Change(pointer, JsonDocuments.hash(current), null));
    }

    static String count(int npcs) { return npcs + (npcs == 1 ? " NPC" : " NPCs"); }

    /** The one id, else how many NPCs. */
    static String named(Collection<String> ids) { return ids.size() == 1 ? ids.iterator().next() : count(ids.size()); }

    /** The one NPC's id, else how many. */
    static String named(List<NpcRepository.Draft> drafts) {
        if (drafts.size() != 1) return count(drafts.size());
        JsonElement id = drafts.getFirst().document().get("id");
        return id != null && id.isJsonPrimitive() ? id.getAsString() : count(1);
    }

    private static List<String> strings(JsonElement value) {
        List<String> list = new ArrayList<>();
        if (value != null && value.isJsonArray()) for (JsonElement element : value.getAsJsonArray()) if (element.isJsonPrimitive()) list.add(element.getAsString());
        return list;
    }
}
