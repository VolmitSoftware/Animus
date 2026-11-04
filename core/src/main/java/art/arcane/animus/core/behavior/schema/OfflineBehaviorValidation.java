package art.arcane.animus.core.behavior.schema;

import art.arcane.animus.api.Capabilities;
import art.arcane.animus.api.Diagnostic;
import art.arcane.animus.core.behavior.catalog.BehaviorCatalog;
import art.arcane.animus.core.behavior.catalog.BehaviorCheckpoints;
import art.arcane.animus.core.behavior.catalog.PackManifest;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.storage.JsonDocumentTree;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Checks a data directory, an exported pack or a single pack without a server, using the same catalog and compiler as
 * the plugin and the built-in capability registry. Reads only; writes no checkpoints, locks or state.
 */
public final class OfflineBehaviorValidation {
    private static final int MAXIMUM_LINES = 200;
    /** What a server knows that this check does not. Reported with every result. */
    public static final List<String> NOT_CHECKED = List.of(
            "Java module capabilities, their arguments and their triggers: only built-in animus: capabilities are known offline.",
            "Whether modules a pack requires are installed and active.",
            "The command allow-list for animus:command, and the server's platform (on Folia most built-in actions are unavailable).",
            "Revisions against programs a server has already accepted: a changed file there still needs a higher revision.",
            "Arguments filled from variables, which are checked when the action runs.",
            "Worlds, coordinates, reusable routes, skins, permissions, sounds, particles, items and whether a player is present.");
    public record Report(boolean valid, List<String> lines) {
        public Report { lines = List.copyOf(lines); }
    }
    private OfflineBehaviorValidation() {}

    public static Report validate(Path directory, OfflineCapabilities capabilities) throws IOException {
        List<String> lines = new ArrayList<>();
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return new Report(false, List.of("Not a directory: " + directory));
        int errors = 0;
        Path manifest = directory.resolve("pack.json");
        if (Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
            try { PackManifest.parse(Files.readString(manifest), String.valueOf(directory.getFileName())); }
            catch (IllegalArgumentException invalid) { errors++; add(lines, "pack.json | pack-manifest-invalid | " + invalid.getMessage()); }
        }
        BehaviorCatalog catalog = new BehaviorCatalog(directory, BehaviorCatalog.Limits.defaults(), capabilities.resolver(), BehaviorCheckpoints.disabled());
        BehaviorCatalog.Reload reload = catalog.reload(0, Set.of());
        BehaviorCatalog.Snapshot snapshot = reload.snapshot();
        if (!reload.published()) errors++;
        for (Diagnostic problem : reload.diagnostics()) errors += report(lines, "catalog", problem);
        int accepted = 0;
        Set<String> modules = new TreeSet<>();
        for (BehaviorCatalog.Entry entry : snapshot.entries().values()) {
            if (entry.state() != BehaviorCatalog.SourceState.VALID && entry.diagnostics().stream().noneMatch(OfflineBehaviorValidation::error)) {
                errors++; add(lines, entry.path() + " | behavior-" + entry.state().name().toLowerCase(java.util.Locale.ROOT) + " | $ | not accepted");
            }
            for (Diagnostic problem : entry.diagnostics()) errors += report(lines, entry.path(), problem);
            if (entry.accepted().isPresent() && entry.state() == BehaviorCatalog.SourceState.VALID) {
                accepted++;
                for (BehaviorCatalog.Requirement requirement : entry.accepted().orElseThrow().requirements())
                    if (!OfflineCapabilities.builtin(requirement.key())) modules.add(requirement.kind().name().toLowerCase(java.util.Locale.ROOT) + " " + requirement.key());
            }
        }
        for (BehaviorCatalog.Resolution resolution : snapshot.byId().values())
            if (!resolution.available() && resolution.diagnostics().stream().noneMatch(OfflineBehaviorValidation::error)) { errors++; add(lines, resolution.id() + " | behavior-unavailable | $ | cannot run"); }
        for (BehaviorCatalog.PackStatus pack : snapshot.packs().values())
            if (pack.manifest().isPresent()) for (Map.Entry<String, PackManifest.Module> module : pack.manifest().get().modules().entrySet())
                if (module.getValue().required()) modules.add("module " + module.getKey() + " required by pack " + pack.id());
        int npcs = 0;
        for (Path file : npcFiles(directory)) {
            npcs++;
            String relative = directory.relativize(file).toString().replace('\\', '/');
            JsonObject npc;
            try { npc = JsonDocuments.object(JsonDocuments.read(file)); }
            catch (IOException invalid) { errors++; add(lines, relative + " | npc-json-invalid | $ | " + invalid.getMessage()); continue; }
            for (String problem : NpcDefinition.validate(npc)) { errors++; add(lines, relative + " | npc-invalid | " + problem); }
            if (npc.has("behaviors") && npc.get("behaviors").isJsonArray()) for (JsonElement reference : npc.getAsJsonArray("behaviors"))
                if (reference.isJsonPrimitive() && snapshot.find(reference.getAsString()).isEmpty())
                    add(lines, relative + " | npc-behavior-elsewhere | $.behaviors | " + reference.getAsString() + " is not in this directory; the server must provide it");
        }
        boolean valid = errors == 0;
        List<String> result = new ArrayList<>();
        result.add((valid ? "VALID" : "INVALID") + " | behaviors=" + snapshot.entries().size() + " | accepted=" + accepted + " | packs=" + snapshot.packs().size() + " | npcs=" + npcs + " | errors=" + errors);
        result.addAll(lines);
        for (String module : modules) result.add("Not checked offline: " + module);
        for (String note : NOT_CHECKED) result.add("Not checked offline: " + note);
        return new Report(valid, result);
    }

    private static boolean error(Diagnostic problem) { return problem.severity() == Diagnostic.Severity.ERROR; }
    private static int report(List<String> lines, String source, Diagnostic problem) {
        add(lines, source + " | " + problem.severity().name().toLowerCase(java.util.Locale.ROOT) + " | " + problem.code() + " | " + problem.field() + " | " + problem.message());
        return error(problem) ? 1 : 0;
    }
    private static void add(List<String> lines, String line) {
        if (lines.size() < MAXIMUM_LINES) lines.add(line);
        else if (lines.size() == MAXIMUM_LINES) lines.add("More problems were found; fix these and check again.");
    }
    private static List<Path> npcFiles(Path directory) throws IOException {
        List<Path> files = new ArrayList<>(JsonDocumentTree.files(directory.resolve("npcs")));
        Path packs = directory.resolve("packs");
        if (Files.isDirectory(packs, LinkOption.NOFOLLOW_LINKS)) {
            try (Stream<Path> children = Files.list(packs)) {
                for (Path pack : children.sorted().toList()) files.addAll(JsonDocumentTree.files(pack.resolve("npcs")));
            }
        }
        return files;
    }
}
