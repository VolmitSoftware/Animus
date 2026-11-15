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
public final class SkinFiles {
    static final int LABEL = 64;
    private final NpcRepository npcs;
    private final DocumentStore store;

    public SkinFiles(NpcRepository npcs, DocumentStore store) { this.npcs = npcs; this.store = store; }

    /** A skin file, its optional label, whether Mojang signed it and who wears it. */
    record Entry(String hash, Optional<String> label, Optional<NpcItem> head, boolean signed, List<String> npcs, List<String> groups) {
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
            entries.add(new Entry(skin.hash(), label(text), head(text), signed(text), skin.npcs(), skin.groups()));
        }
        return entries;
    }

    /** A skin to wear by its hash, with the file's text when the file may not be saved yet. */
    public record Skin(String hash, Optional<String> text) {
        /** The change that saves the file, when it is new. */
        public Optional<DocumentStore.Change> file(DocumentStore store) throws IOException {
            return text.isEmpty() || store.read(path(hash)) != null ? Optional.empty() : Optional.of(new DocumentStore.Change(path(hash), "missing", text.get()));
        }
    }

    /** The file for a signed skin labelled {@code label}, not yet saved. */
    static Skin skin(BodySkin skin, String label) throws IOException { return skin(skin, label, Optional.empty()); }

    /** The file for a skin labelled {@code label}, taken from the account {@code player} when there is one; not yet saved. */
    public static Skin skin(BodySkin skin, String label, Optional<String> player) throws IOException {
        JsonObject document = new JsonObject();
        document.addProperty("textureRaw", skin.textureValue());
        if (skin.signature() != null) document.addProperty("signature", skin.signature());
        document.addProperty("label", checked(label).orElseThrow(() -> new IOException("A skin label needs at least one character")));
        player.ifPresent((String name) -> document.addProperty("player", name));
        String text = JsonDocuments.write(document);
        return new Skin(JsonDocuments.hash(text), Optional.of(text));
    }

    /** Writes the skin labelled {@code label} when that file is new; returns its hash. */
    String save(BodySkin skin, String label, Optional<String> player) throws IOException {
        Skin file = skin(skin, label, player);
        Optional<DocumentStore.Change> change = file.file(store);
        if (change.isPresent()) store.commit(List.of(change.get()));
        return file.hash();
    }

    /**
     * Saves a skin labelled {@code label} and puts it on the listed player bodies; with none listed it is only saved.
     * {@code player} names the account a looked-up skin came from, which NPCs that follow it look up again. Read and
     * written on the file lane.
     */
    public String wear(BodySkin skin, String label, Optional<String> player, List<String> ids) throws IOException {
        String hash = save(skin, label, player);
        return ids.isEmpty() ? "Saved " + label + "'s skin; select NPCs and click it to wear it" : new ToolkitFiles(npcs, store).skin(Optional.of(hash), ids);
    }

    /** The two {@code animus:skin} options as players read them. */
    public static final String FOLLOW = "Follow player", FROM_NAME = "Skin from name";

    /** Turns {@code animus:skin refresh} on or off for the listed player bodies. Read and written on the file lane. */
    public String follow(boolean on, List<String> ids) throws IOException { return new ToolkitFiles(npcs, store).skinOption("refresh", FOLLOW, on, ids); }

    /** Turns {@code animus:skin fromName} on or off for the listed player bodies. Read and written on the file lane. */
    public String fromName(boolean on, List<String> ids) throws IOException { return new ToolkitFiles(npcs, store).skinOption("fromName", FROM_NAME, on, ids); }

    /** Takes the skin off the listed player bodies. */
    public String remove(List<String> ids) throws IOException { return new ToolkitFiles(npcs, store).skin(Optional.empty(), ids); }

    /** Relabels the skin; a blank label removes it. The wearers move to the new file in the same transaction. */
    String rename(String hash, String label) throws IOException {
        String current = store.read(path(hash));
        if (current == null) throw new IOException("That skin was deleted");
        JsonObject document = JsonDocuments.object(current);
        Optional<String> next = checked(label);
        if (next.equals(label(current))) return "Skin " + hash.substring(0, 12) + " already " + next.map((String name) -> "labelled " + name).orElse("has no label");
        if (next.isPresent()) document.addProperty("label", next.get()); else document.remove("label");
        Moved moved = replace(hash, JsonDocuments.write(document));
        String what = next.map((String name) -> "Labelled skin " + hash.substring(0, 12) + " " + name).orElse("Removed the label of skin " + hash.substring(0, 12));
        return what + "; now " + moved.hash().substring(0, 12) + moved.carried(npcs) + "; tx " + moved.tx();
    }

    /** Where {@link #replace} moved a skin's wearers. */
    public record Moved(String hash, List<String> npcs, List<String> groups, String tx) {
        public Moved { npcs = List.copyOf(npcs); groups = List.copyOf(groups); }

        /** {@code , moved guard and 1 trait group}, or nothing when nothing wore it. */
        public String carried(NpcRepository repository) {
            if (npcs.isEmpty() && groups.isEmpty()) return "";
            return ", moved " + (npcs.isEmpty() ? "" : ToolkitFiles.named(repository, npcs)) + (npcs.isEmpty() || groups.isEmpty() ? "" : " and ")
                    + (groups.isEmpty() ? "" : groups.size() + (groups.size() == 1 ? " trait group" : " trait groups"));
        }
    }

    /**
     * Writes {@code text} as a new skin file and moves every NPC and trait group wearing {@code hash} onto it, deleting
     * the old file, in one transaction with Undo. Read and written on the file lane.
     */
    public Moved replace(String hash, String text) throws IOException {
        String current = store.read(path(hash));
        if (current == null) throw new IOException("That skin was deleted");
        String moved = JsonDocuments.hash(text);
        if (moved.equals(hash)) throw new IOException("The skin is unchanged");
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
        return new Moved(moved, wearers.stream().map(NpcDefinition::id).toList(), groups, tx);
    }

    /** Deletes a skin file nothing wears. */
    String delete(String hash) throws IOException {
        String current = store.read(path(hash));
        if (current == null) throw new IOException("That skin was deleted");
        for (Entry entry : skins())
            if (entry.hash().equals(hash) && entry.used())
                throw new IOException("Skin " + entry.title() + " is still worn by " + ToolScreen.names(concat(entry.npcs(), entry.groups()), 4) + "; take it off them first");
        String tx = store.commit(List.of(new DocumentStore.Change(path(hash), JsonDocuments.hash(current), null)));
        return "Deleted skin " + label(current).orElse(hash.substring(0, 12)) + "; tx " + tx;
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

    /** Whether a skin file's text carries Mojang's signature; clients draw the default skin for one that does not. */
    static boolean signed(String text) {
        if (text == null) return false;
        try {
            JsonElement signature = JsonDocuments.object(text).get("signature");
            return signature != null && signature.isJsonPrimitive() && signature.getAsJsonPrimitive().isString();
        } catch (IOException | RuntimeException unreadable) { return false; }
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
