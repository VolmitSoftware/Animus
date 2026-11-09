package art.arcane.animus.core.editor;

import art.arcane.animus.core.config.EditorNotifySettings;
import art.arcane.animus.core.editor.transport.EditorRequest;
import art.arcane.animus.core.editor.transport.EditorResponse;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;

/**
 * The editor's import endpoints under {@code /v1/imports}: upload, stage, and the apply, accept and rollback steps of
 * a staged import. Every read and write runs on the editor's file lane, as the other document endpoints do.
 */
final class EditorImportRoutes {
    private final EditorApi api;

    EditorImportRoutes(EditorApi api) { this.api = api; }

    /** Kinds an import writes: NPCs and skins, generated behaviors, and the shops and layouts of dtlTraders and Shopkeepers. */
    private static final Set<DocumentKind> IMPORTED = EnumSet.of(DocumentKind.NPC, DocumentKind.SKIN, DocumentKind.BEHAVIOR, DocumentKind.SHOP, DocumentKind.INVENTORY, DocumentKind.SERVER_BEHAVIORS);

    private EditorImports imports() { return new EditorImports(api.parts().documents().store(), api.parts().npcs(), api.parts().importHost()); }

    /** Imports reach every NPC group, so a group-limited token cannot stage or change one. */
    private static void ungrouped(EditorTokens.Token token) throws EditorError {
        if (!token.groups().isEmpty()) throw new EditorError(EditorError.Code.GROUP_DENIED, "Imports can reach every NPC group; use a token without groups.");
    }

    /** {@code GET /v1/imports}: where the import stands. */
    CompletionStage<EditorResponse> status(EditorRequest request, EditorTokens.Token token, String[] segments) throws EditorError {
        return api.files(token, () -> EditorResponse.json(200, imports().status()));
    }

    /** {@code POST /v1/imports?file=...&name=...}: the file's bytes, or over the relay a base64 JSON string. */
    CompletionStage<EditorResponse> upload(EditorRequest request, EditorTokens.Token token, String[] segments) throws EditorError {
        ungrouped(token);
        Map<String, String> query = EditorReplies.query(request);
        for (String key : query.keySet()) if (!key.equals("file") && !key.equals("name")) throw EditorMessages.invalid("Unknown query parameter " + key + ".");
        EditorImports.Upload kind = EditorImports.Upload.parse(query.getOrDefault("file", "citizens"))
                .orElseThrow(() -> EditorMessages.invalid("file= is citizens, shopkeepers, shopkeepers-config or dtltraders."));
        Optional<String> name = Optional.ofNullable(query.get("name"));
        String target = EditorImports.target(kind, name);
        byte[] upload;
        try {
            JsonElement raw = request.body().orElseThrow(() -> EditorMessages.invalid("Upload the file as the request body."));
            if (!raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isString()) throw EditorMessages.invalid("The upload is the file's bytes, or over the relay a base64 JSON string.");
            upload = Base64.getDecoder().decode(raw.getAsString());
        } catch (IllegalArgumentException invalid) { throw EditorMessages.invalid("The upload is not base64."); }
        if (upload.length == 0) throw EditorMessages.invalid("The uploaded file is empty.");
        if (upload.length > api.parts().settings().importBodyBytes()) throw new EditorError(EditorError.Code.TOO_LARGE, "Imports are limited to " + api.parts().settings().importBodyBytes() + " bytes.");
        byte[] bytes = upload;
        return api.files(token, () -> {
            try {
                JsonObject review = imports().upload(kind, name, bytes);
                api.audit("import-upload", Optional.of(token.id()), token.admin(), request, List.of(target), "uploaded");
                return EditorResponse.json(200, review);
            } catch (EditorError refused) {
                api.audit("import-upload", Optional.of(token.id()), token.admin(), request, List.of(target), refused.code().wireName());
                throw refused;
            }
        });
    }

    /** {@code POST /v1/imports/stage}: converts the uploaded saves and stages one reviewed plan. Nothing is installed. */
    CompletionStage<EditorResponse> stage(EditorRequest request, EditorTokens.Token token, String[] segments) throws EditorError {
        ungrouped(token);
        JsonObject body = request.body().isPresent() ? EditorMessages.body(request) : new JsonObject();
        EditorMessages.fields(body, "$", Set.of("mode", "label", "worlds"));
        String mode = body.has("mode") ? EditorMessages.string(body, "mode", "reject|replace|duplicate", "$.mode") : "reject";
        Optional<String> label = body.has("label") ? Optional.of(EditorMessages.string(body, "label", "[a-z0-9_-]{1,32}", "$.label")) : Optional.empty();
        if (mode.equals("duplicate") != label.isPresent()) throw EditorMessages.invalid("$.label: a duplicate import needs a copy label, and only a duplicate takes one.");
        Map<String, String> worlds = new LinkedHashMap<>();
        if (body.has("worlds")) {
            if (!body.get("worlds").isJsonObject()) throw EditorMessages.invalid("$.worlds: expected an object of old world names to new ones.");
            JsonObject map = body.getAsJsonObject("worlds");
            if (map.size() > 64) throw EditorMessages.invalid("$.worlds: at most 64 world mappings.");
            for (String from : map.keySet()) {
                if (!from.matches("[A-Za-z0-9_.-]{1,64}")) throw EditorMessages.invalid("$.worlds: world names use letters, digits, _, . and -.");
                worlds.put(from, EditorMessages.string(map, from, "[A-Za-z0-9_.-]{1,64}", "$.worlds." + from));
            }
        }
        return api.files(token, () -> {
            try {
                JsonObject review = imports().stage(mode, label, worlds);
                api.audit("import-stage", Optional.of(token.id()), token.admin(), request, staged(review), "staged");
                return EditorResponse.json(200, review);
            } catch (EditorError refused) {
                api.audit("import-stage", Optional.of(token.id()), token.admin(), request, List.of(), refused.code().wireName());
                throw refused;
            }
        });
    }

    /** {@code POST /v1/imports/{importId}/apply|accept|rollback}: the drop-in steps, with a catalog event for what changed. */
    CompletionStage<EditorResponse> step(EditorRequest request, EditorTokens.Token token, String[] segments) throws EditorError {
        String id = segments[1], step = segments[2];
        if (!id.matches("[a-f0-9]{64}")) throw new EditorError(EditorError.Code.NOT_FOUND, "No staged import " + id + ".");
        ungrouped(token);
        api.scopes(token, IMPORTED, List.of());
        List<String> categories = new ArrayList<>();
        if (step.equals("accept")) {
            JsonObject body = EditorMessages.body(request);
            EditorMessages.fields(body, "$", Set.of("categories"));
            if (!body.has("categories") || !body.get("categories").isJsonArray() || body.getAsJsonArray("categories").isEmpty())
                throw EditorMessages.invalid("$.categories: expected a non-empty array of review categories.");
            JsonArray list = body.getAsJsonArray("categories");
            for (int index = 0; index < list.size(); index++) {
                JsonElement category = list.get(index);
                if (!category.isJsonPrimitive() || !category.getAsJsonPrimitive().isString()) throw EditorMessages.invalid("$.categories[" + index + "]: expected a string.");
                categories.add(category.getAsString());
            }
        }
        EditorCommandGuard.requireForImport(token, step, categories, api.parts().host().commandGrants());
        return api.files(token, () -> {
            Map<String, String> before = hashes();
            JsonObject review;
            try {
                review = switch (step) {
                    case "apply" -> imports().apply(id);
                    case "accept" -> imports().accept(id, categories);
                    default -> imports().rollback(id);
                };
            } catch (EditorError refused) {
                api.audit("import-" + step, Optional.of(token.id()), token.admin(), request, List.of("import:" + id), refused.code().wireName());
                throw refused;
            }
            List<String> documents = new ArrayList<>(List.of("import:" + id));
            EditorNotifySettings.Kind kind = step.equals("rollback") ? EditorNotifySettings.Kind.ROLLBACK : EditorNotifySettings.Kind.IMPORT;
            String verb = step.equals("rollback") ? "rolled back an import of" : step.equals("apply") ? "applied an import of" : "accepted an import of";
            importPublished(before, hashes(), step.equals("rollback"), token, kind, verb).stream().limit(64).forEach(documents::add);
            api.audit("import-" + step, Optional.of(token.id()), token.admin(), request, documents, step.equals("rollback") ? "rolled-back" : step.equals("apply") ? "applied" : "accepted");
            return EditorResponse.json(200, review);
        });
    }

    private static List<String> staged(JsonObject review) {
        List<String> ids = new ArrayList<>();
        for (String part : List.of("citizens", "shopkeepers", "dtltraders"))
            if (review.has(part)) ids.add("import:" + review.getAsJsonObject(part).get("importId").getAsString());
        return ids;
    }

    /** Reloads what an import step changed and sends one catalog event; returns the changed documents as {@code kind:id}. */
    private List<String> importPublished(Map<String, String> before, Map<String, String> after, boolean rollback, EditorTokens.Token token,
                                         EditorNotifySettings.Kind notice, String verb) throws IOException {
        JsonArray catalog = new JsonArray(), deleted = new JsonArray();
        List<String> touched = new ArrayList<>();
        Set<DocumentKind> kinds = EnumSet.noneOf(DocumentKind.class);
        for (Map.Entry<String, String> entry : after.entrySet()) {
            if (entry.getValue().equals(before.get(entry.getKey()))) continue;
            String[] name = entry.getKey().split("\n", 3);
            DocumentKind kind = DocumentKind.parse(name[0]).orElseThrow();
            kinds.add(kind);
            touched.add(kind.wireName() + ":" + name[1]);
            catalog.add(EditorChanges.catalogEntry(kind, name[1], name[2], EditorDocuments.revision(api.parts().documents().store().read(name[2])), entry.getValue()));
        }
        for (String key : before.keySet()) {
            if (after.containsKey(key)) continue;
            String[] name = key.split("\n", 3);
            DocumentKind kind = DocumentKind.parse(name[0]).orElseThrow();
            kinds.add(kind);
            touched.add(kind.wireName() + ":" + name[1]);
            JsonObject reference = new JsonObject();
            reference.addProperty("kind", kind.wireName());
            reference.addProperty("id", name[1]);
            deleted.add(reference);
        }
        if (kinds.isEmpty()) return touched;
        api.announce(api.parts().host().applied(kinds, EditorApi.removedBehaviors(deleted), rollback), token, notice, verb, touched, Optional.empty());
        JsonObject event = new JsonObject();
        event.addProperty("type", "catalog");
        event.add("documents", catalog);
        if (!deleted.isEmpty()) event.add("deleted", deleted);
        api.parts().events().publish(event);
        return touched;
    }

    private Map<String, String> hashes() throws IOException {
        Map<String, String> hashes = new HashMap<>();
        for (EditorDocuments.Location location : api.parts().documents().list(IMPORTED)) {
            String content = api.parts().documents().store().read(location.path());
            if (content != null) hashes.put(location.kind().wireName() + "\n" + location.id() + "\n" + location.path(), "sha256:" + JsonDocuments.hash(content));
        }
        return hashes;
    }
}
