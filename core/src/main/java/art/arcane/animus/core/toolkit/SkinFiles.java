package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.npc.DefinitionBatch;
import art.arcane.animus.core.npc.GroupDocument;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.presentation.BodySkin;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Skin file work for the Skins screen, on the file lane. A skin file is named by the SHA-256 of its text, so a new
 * label is a new file: the NPCs and trait groups wearing the old one move to it and the old file goes, all in one
 * transaction, as the web editor does when it changes a skin's arm model.
 */
final class SkinFiles {
    static final int LABEL = 64;
    private final NpcRepository npcs;
    private final DocumentStore store;

    SkinFiles(NpcRepository npcs, DocumentStore store) { this.npcs = npcs; this.store = store; }

    /** A skin file, its optional label and who wears it. */
    record Entry(String hash, Optional<String> label, Optional<NpcItem> head, List<String> npcs, List<String> groups) {
        Entry { npcs = List.copyOf(npcs); groups = List.copyOf(groups); }
        boolean used() { return !npcs.isEmpty() || !groups.isEmpty(); }
        String shortHash() { return hash.substring(0, 12); }
        /** The label, else the first NPC or group wearing it. */
        String title() {
            if (label.isPresent()) return label.get();
            if (!npcs.isEmpty()) return npcs.getFirst();
            return groups.isEmpty() ? "Unused skin" : groups.getFirst();
        }
    }

    /** Every skin file with its label and wearers; most used first. */
    List<Entry> skins() throws IOException {
        List<Entry> entries = new ArrayList<>();
        for (ToolkitFiles.Skin skin : new ToolkitFiles(npcs, store).skins()) {
            String text = store.read(path(skin.hash()));
            entries.add(new Entry(skin.hash(), label(text), head(text), skin.npcs(), skin.groups()));
        }
        return entries;
    }

    /** Writes the signed skin labelled {@code label} when that file is new; returns its hash. */
    String save(BodySkin skin, String label) throws IOException {
        JsonObject document = new JsonObject();
        document.addProperty("textureRaw", skin.textureValue());
        if (skin.signature() != null) document.addProperty("signature", skin.signature());
        document.addProperty("label", checked(label).orElseThrow(() -> new IOException("A skin label needs at least one character")));
        String text = JsonDocuments.write(document), hash = JsonDocuments.hash(text);
        if (store.read(path(hash)) == null) store.commit(List.of(new DocumentStore.Change(path(hash), "missing", text)));
        return hash;
    }

    /** Relabels the skin; a blank label removes it. The wearers move to the new file in the same transaction. */
    String rename(String hash, String label) throws IOException {
        String current = store.read(path(hash));
        if (current == null) throw new IOException("No skin file skins/" + hash + ".json");
        JsonObject document = JsonDocuments.object(current);
        Optional<String> next = checked(label);
        if (next.equals(label(current))) return "Skin " + hash.substring(0, 12) + " already " + next.map((String name) -> "labelled " + name).orElse("has no label");
        if (next.isPresent()) document.addProperty("label", next.get()); else document.remove("label");
        String text = JsonDocuments.write(document), moved = JsonDocuments.hash(text);
        List<DocumentStore.Change> changes = new ArrayList<>();
        if (store.read(path(moved)) == null) changes.add(new DocumentStore.Change(path(moved), "missing", text));
        changes.add(new DocumentStore.Change(path(hash), JsonDocuments.hash(current), null));
        List<String> groups = new ArrayList<>();
        for (GroupDocument group : npcs.groups().groups().values()) {
            String source = store.read(group.path());
            if (source == null) continue;
            JsonObject groupDocument = JsonDocuments.object(source);
            if (!retarget(groupDocument, hash, moved)) continue;
            if (groupDocument.has("revision") && groupDocument.get("revision").isJsonPrimitive()) groupDocument.addProperty("revision", groupDocument.get("revision").getAsLong() + 1);
            changes.add(new DocumentStore.Change(group.path(), JsonDocuments.hash(source), JsonDocuments.write(groupDocument)));
            groups.add(group.id());
        }
        List<NpcDefinition> wearers = new ArrayList<>();
        Map<String, String> expected = new HashMap<>();
        for (NpcDefinition definition : npcs.stored()) {
            JsonElement skin = definition.json().get("skin");
            if (skin == null || !skin.isJsonPrimitive() || !skin.getAsString().equals(hash)) continue;
            NpcRepository.Draft draft = npcs.draft(definition.id());
            JsonObject npc = draft.document();
            npc.addProperty("skin", moved);
            npc.addProperty("revision", draft.expectedRevision() + 1);
            wearers.add(NpcDefinition.parse(JsonDocuments.write(npc)));
            expected.put(draft.path(), draft.expectedHash());
        }
        String tx = DefinitionBatch.commit(store, wearers, expected, changes);
        npcs.reload();
        String what = next.map((String name) -> "Labelled skin " + hash.substring(0, 12) + " " + name).orElse("Removed the label of skin " + hash.substring(0, 12));
        String carried = wearers.isEmpty() && groups.isEmpty() ? "" : ", moved " + ToolkitFiles.count(wearers.size()) + (groups.isEmpty() ? "" : " and " + groups.size() + (groups.size() == 1 ? " group" : " groups"));
        return what + "; now " + moved.substring(0, 12) + carried + "; tx " + tx;
    }

    /** Deletes a skin file nothing wears. */
    String delete(String hash) throws IOException {
        String current = store.read(path(hash));
        if (current == null) throw new IOException("No skin file skins/" + hash + ".json");
        for (Entry entry : skins())
            if (entry.hash().equals(hash) && entry.used())
                throw new IOException("Skin " + entry.title() + " is still worn by " + ToolScreen.names(concat(entry.npcs(), entry.groups()), 4) + "; take it off them first");
        String tx = store.commit(List.of(new DocumentStore.Change(path(hash), JsonDocuments.hash(current), null)));
        return "Deleted skins/" + hash.substring(0, 12) + "; tx " + tx;
    }

    /** The trimmed label, or empty when blank. Refuses control characters and labels over {@link #LABEL} characters. */
    static Optional<String> checked(String label) throws IOException {
        String trimmed = label.strip();
        if (trimmed.isEmpty()) return Optional.empty();
        if (trimmed.length() > LABEL) throw new IOException("A skin label is at most " + LABEL + " characters");
        if (trimmed.chars().anyMatch(Character::isISOControl)) throw new IOException("A skin label is one line of text");
        return Optional.of(trimmed);
    }

    /** The {@code label} of a skin file's text, or empty when it has none or cannot be read. */
    static Optional<String> label(String text) {
        if (text == null) return Optional.empty();
        try {
            JsonElement label = JsonDocuments.object(text).get("label");
            return label != null && label.isJsonPrimitive() && label.getAsJsonPrimitive().isString() && !label.getAsString().isBlank() ? Optional.of(label.getAsString()) : Optional.empty();
        } catch (IOException | RuntimeException unreadable) { return Optional.empty(); }
    }

    /** The {@code textureRaw} of a skin file's text, or empty when it has none or cannot be read. */
    static Optional<String> texture(String text) {
        if (text == null) return Optional.empty();
        try {
            JsonElement texture = JsonDocuments.object(text).get("textureRaw");
            return texture != null && texture.isJsonPrimitive() && texture.getAsJsonPrimitive().isString() ? Optional.of(texture.getAsString()) : Optional.empty();
        } catch (IOException | RuntimeException unreadable) { return Optional.empty(); }
    }

    /**
     * A player head wearing a skin file's texture with its signature: the client refuses a texture that asks for a
     * signature and has none, and draws a default head instead.
     */
    static Optional<NpcItem> head(String text) {
        Optional<String> texture = texture(text);
        if (texture.isEmpty()) return Optional.empty();
        Optional<String> signature;
        try {
            JsonElement signed = JsonDocuments.object(text).get("signature");
            signature = signed != null && signed.isJsonPrimitive() && signed.getAsJsonPrimitive().isString() ? Optional.of(signed.getAsString()) : Optional.empty();
        } catch (IOException | RuntimeException unreadable) { signature = Optional.empty(); }
        return Optional.of(ToolScreen.skinHead(texture.get(), signature));
    }

    /** The head each listed NPC's skin draws, by id; NPCs without a readable skin are left out. Reads files, so run it on the file lane. */
    Map<String, NpcItem> worn(List<NpcDefinition> definitions) throws IOException {
        Map<String, NpcItem> textures = new HashMap<>();
        for (NpcDefinition definition : definitions) {
            JsonElement skin = definition.json().get("skin");
            if (skin == null || !skin.isJsonPrimitive() || !skin.getAsString().matches("[a-f0-9]{64}")) continue;
            head(store.read(path(skin.getAsString()))).ifPresent((NpcItem head) -> textures.put(definition.id(), head));
        }
        return textures;
    }

    private static String path(String hash) { return "skins/" + hash + ".json"; }

    /** Points a group's {@code values.skin} and {@code values.skins} at {@code to}; false when it named neither. */
    private static boolean retarget(JsonObject group, String from, String to) {
        if (!group.has("values") || !group.get("values").isJsonObject()) return false;
        JsonObject values = group.getAsJsonObject("values");
        boolean changed = false;
        JsonElement skin = values.get("skin");
        if (skin != null && skin.isJsonPrimitive() && skin.getAsString().equals(from)) { values.addProperty("skin", to); changed = true; }
        JsonElement pool = values.get("skins");
        if (pool != null && pool.isJsonArray()) {
            JsonArray next = new JsonArray();
            List<String> seen = new ArrayList<>();
            for (JsonElement entry : pool.getAsJsonArray()) {
                String value = entry.isJsonPrimitive() ? entry.getAsString() : null;
                if (from.equals(value)) { value = to; changed = true; }
                if (value == null || seen.contains(value)) continue;
                seen.add(value);
                next.add(value);
            }
            values.add("skins", next);
        }
        return changed;
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }
}
