package art.arcane.animus.core.migration;

import art.arcane.animus.core.npc.GroupDocument;
import art.arcane.animus.core.npc.GroupMerge;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The {@code factor-groups} import option: every set of at least two converted NPCs whose groupable fields are identical
 * becomes one trait group, {@code groups/citizens-<8 hex of the set hash>.json}. Those fields leave the member NPCs, which
 * list the group instead, so each member's effective definition is what conversion produced. A set whose group or any
 * member would not validate is left as it was.
 */
public final class GroupFactoring {
    /** The fields a factored group takes. {@code citizens:review} stays on each NPC. */
    public static final List<String> FIELDS = List.of("modules", "behaviors", "equipment", "hologram", "nameplate", "pose", "movement", "glowing", "teamColor");
    static final String REVIEW = "citizens:review";
    public static final String GROUP_PATH = "groups/citizens-[a-f0-9]{8}\\.json";

    /** A factored group: its file, its content and the paths of its members. */
    public record Group(String id, String path, String content, List<String> members) {
        public Group { members = List.copyOf(members); }
    }

    /** The definitions by path, members rewritten, and the groups to write. */
    public record Result(Map<String, JsonObject> definitions, List<Group> groups) {
        public Result { definitions = new LinkedHashMap<>(definitions); groups = List.copyOf(groups); }
    }

    private GroupFactoring() {}

    public static Result factor(Map<String, JsonObject> definitions) {
        Map<String, JsonObject> rewritten = new LinkedHashMap<>();
        definitions.forEach((String path, JsonObject definition) -> rewritten.put(path, definition.deepCopy()));
        Map<String, List<String>> sets = new TreeMap<>();
        Map<String, JsonObject> signatures = new TreeMap<>();
        for (Map.Entry<String, JsonObject> entry : rewritten.entrySet()) {
            JsonObject signature = signature(entry.getValue());
            if (signature.isEmpty()) continue;
            String canonical = canonical(signature).toString();
            sets.computeIfAbsent(canonical, (String ignored) -> new ArrayList<>()).add(entry.getKey());
            signatures.putIfAbsent(canonical, signature);
        }
        List<Group> groups = new ArrayList<>();
        for (Map.Entry<String, List<String>> set : sets.entrySet()) {
            if (set.getValue().size() < 2) continue;
            String id = "citizens-" + JsonDocuments.hash(set.getKey()).substring(0, 8);
            if (groups.stream().anyMatch((Group group) -> group.id().equals(id))) continue;
            JsonObject document = new JsonObject();
            document.addProperty("schemaVersion", GroupDocument.SCHEMA_VERSION);
            document.addProperty("revision", 1);
            document.addProperty("id", id);
            document.addProperty("description", "Shared by " + set.getValue().size() + " imported Citizens NPCs");
            document.add("values", canonical(signatures.get(set.getKey())));
            String content = JsonDocuments.write(document);
            GroupDocument.Result parsed = GroupDocument.parse(id, content);
            if (parsed.group().isEmpty()) continue;
            Map<String, JsonObject> members = new LinkedHashMap<>();
            boolean valid = true;
            for (String path : set.getValue()) {
                JsonObject member = strip(rewritten.get(path), id);
                if (!NpcDefinition.validate(member).isEmpty() || !NpcDefinition.validate(GroupMerge.merge(member, List.of(parsed.group().get())).effective()).isEmpty()) { valid = false; break; }
                members.put(path, member);
            }
            if (!valid) continue;
            rewritten.putAll(members);
            groups.add(new Group(id, "groups/" + id + ".json", content, set.getValue()));
        }
        return new Result(rewritten, groups);
    }

    /** The staged NPC definitions of a preview as they will run: merged over the preview's factored groups. */
    static List<JsonObject> staged(CitizensImporter.Preview preview) throws IOException {
        JsonObject documents = preview.summary().getAsJsonObject("documents");
        Map<String, GroupDocument> groups = new TreeMap<>();
        for (String path : documents.keySet()) {
            if (!path.matches(GROUP_PATH)) continue;
            String id = path.substring(7, path.length() - 5);
            GroupDocument.parse(id, JsonDocuments.read(preview.directory().resolve(path))).group().ifPresent((GroupDocument group) -> groups.put(id, group));
        }
        List<JsonObject> staged = new ArrayList<>();
        for (String path : documents.keySet()) {
            if (!path.startsWith("npcs/")) continue;
            JsonObject definition = JsonDocuments.object(JsonDocuments.read(preview.directory().resolve(path)));
            List<GroupDocument> listed = new ArrayList<>();
            if (definition.has("groups") && definition.get("groups").isJsonArray())
                for (JsonElement id : definition.getAsJsonArray("groups")) if (groups.containsKey(id.getAsString())) listed.add(groups.get(id.getAsString()));
            staged.add(listed.isEmpty() ? definition : GroupMerge.merge(definition, listed).effective());
        }
        return staged;
    }

    /** The review line naming each factored group with its member count; empty without the option. */
    static Optional<String> reviewLine(JsonObject summary) {
        if (!summary.has("factoredGroups")) return Optional.empty();
        List<String> groups = new ArrayList<>();
        for (JsonElement element : summary.getAsJsonArray("factoredGroups")) {
            JsonObject group = element.getAsJsonObject();
            groups.add("groups/" + group.get("id").getAsString() + ".json (" + group.get("members").getAsInt() + " NPCs)");
        }
        return Optional.of("Factored groups: " + (groups.isEmpty() ? "none; no two NPCs share their settings" : String.join(", ", groups)));
    }

    /** The groupable fields of one definition; {@code citizens:review} is left out of {@code modules}. */
    static JsonObject signature(JsonObject definition) {
        JsonObject signature = new JsonObject();
        for (String field : FIELDS) {
            JsonElement value = definition.get(field);
            if (value == null) continue;
            if (field.equals("modules") && value.isJsonObject()) {
                JsonObject modules = value.getAsJsonObject().deepCopy();
                modules.remove(REVIEW);
                if (!modules.isEmpty()) signature.add(field, modules);
            } else signature.add(field, value.deepCopy());
        }
        return signature;
    }

    private static JsonObject strip(JsonObject definition, String group) {
        JsonObject member = definition.deepCopy();
        for (String field : FIELDS) {
            if (field.equals("modules") && member.has(field) && member.get(field).isJsonObject() && member.getAsJsonObject(field).has(REVIEW)) {
                JsonObject review = new JsonObject();
                review.add(REVIEW, member.getAsJsonObject(field).get(REVIEW));
                member.add(field, review);
            } else member.remove(field);
        }
        JsonArray groups = member.has("groups") && member.get("groups").isJsonArray() ? member.getAsJsonArray("groups") : new JsonArray();
        groups.add(group);
        member.add("groups", groups);
        return member;
    }

    /** Objects with sorted keys, so identical values have one text and one hash whatever order conversion wrote them in. */
    private static JsonElement canonical(JsonElement value) {
        if (value.isJsonObject()) {
            JsonObject sorted = new JsonObject();
            new TreeMap<>(value.getAsJsonObject().asMap()).forEach((String key, JsonElement child) -> sorted.add(key, canonical(child)));
            return sorted;
        }
        if (value.isJsonArray()) {
            JsonArray array = new JsonArray();
            for (JsonElement child : value.getAsJsonArray()) array.add(canonical(child));
            return array;
        }
        return value.deepCopy();
    }

    private static JsonObject canonical(JsonObject value) { return canonical((JsonElement) value).getAsJsonObject(); }
}
