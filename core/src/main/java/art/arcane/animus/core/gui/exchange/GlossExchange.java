package art.arcane.animus.core.gui.exchange;

import art.arcane.animus.core.command.CommandActor;
import art.arcane.animus.core.gui.GlossBridge;
import art.arcane.animus.core.gui.GuiKind;
import art.arcane.animus.core.gui.GuiValidator;
import art.arcane.animus.core.shop.ShopDocument;
import art.arcane.animus.core.storage.JsonDocumentTree;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Copies Gloss documents of the adopted kinds into Animus's {@code gui/} folders and back out. Every document is
 * validated before anything is written, a different existing file is kept unless {@code replace=true}, documents
 * with Animus-only actions never go to Gloss, and every skipped or refused document is listed with its reason.
 */
public final class GlossExchange {
    private static final Set<String> REFERENCE_FIELDS = Set.of("id", "target", "gui", "shop");
    private static final Pattern ANIMATION = Pattern.compile("\\|animation\\.([^|]*)\\|");
    private static final String NAME = "[A-Za-z0-9_-]{1,64}";
    private final Path data;
    private final GuiValidator validator;
    private final Supplier<? extends GlossBridge<?>> bridge;
    private final Executor files;
    private final Consumer<String> log;

    public GlossExchange(Path data, GuiValidator validator) { this(data, validator, () -> GlossBridge.absent("Gloss is not installed"), Runnable::run, (String line) -> {}); }

    /**
     * @param bridge the current Gloss bridge; only its folder is used here
     * @param files runs file work off the server thread
     */
    public GlossExchange(Path data, GuiValidator validator, Supplier<? extends GlossBridge<?>> bridge, Executor files, Consumer<String> log) {
        this.data = Objects.requireNonNull(data);
        this.validator = Objects.requireNonNull(validator);
        this.bridge = Objects.requireNonNull(bridge);
        this.files = Objects.requireNonNull(files);
        this.log = Objects.requireNonNull(log);
    }

    /** One Animus document ready for Gloss. {@code json} is normalized JSON text. */
    public record Exported(GuiKind kind, String animusId, String glossId, String json, long updatedAt) {}

    /** What an import or export did. Lines read {@code <kind> <id>[: reason]}. */
    public record Report(List<String> written, List<String> unchanged, List<String> refused, List<String> skipped, List<String> notes) {
        public Report { written = List.copyOf(written); unchanged = List.copyOf(unchanged); refused = List.copyOf(refused); skipped = List.copyOf(skipped); notes = List.copyOf(notes); }

        public Report plus(Report other) {
            Builder builder = new Builder();
            for (Report part : List.of(this, other)) {
                part.written.forEach(builder::written); part.unchanged.forEach(builder::unchanged); part.refused.forEach(builder::refused);
                part.skipped.forEach(builder::skipped); part.notes.forEach(builder::note);
            }
            return builder.build();
        }

        public String summary() {
            return written.size() + " written, " + unchanged.size() + " unchanged, " + refused.size() + " refused, " + skipped.size() + " skipped";
        }

        public static final class Builder {
            private final List<String> written = new ArrayList<>(), unchanged = new ArrayList<>(), refused = new ArrayList<>(), skipped = new ArrayList<>(), notes = new ArrayList<>();
            public Builder written(String line) { written.add(line); return this; }
            public Builder unchanged(String line) { unchanged.add(line); return this; }
            public Builder refused(String line) { refused.add(line); return this; }
            public Builder skipped(String line) { skipped.add(line); return this; }
            public Builder note(String line) { notes.add(line); return this; }
            public Report build() { return new Report(written, unchanged, refused, skipped, notes); }
        }
    }

    public Report importDocuments(GlossSources.Batch batch, String namespace, boolean replace) throws IOException {
        Report.Builder skipped = new Report.Builder();
        batch.skipped().forEach(skipped::skipped);
        return skipped.build().plus(importDocuments(batch.documents(), namespace, replace));
    }

    /** Validates every document first, then writes the accepted ones into {@code gui/<kind>/<namespace>/}. */
    public Report importDocuments(List<GlossDocument> documents, String namespace, boolean replace) throws IOException {
        if (!namespace.matches("[a-z0-9_-]{1,64}")) throw new IOException("namespace must use lowercase letters, digits, _ and -");
        Report.Builder report = new Report.Builder();
        Map<String, String> claimed = new HashMap<>();
        Map<Path, String> accepted = new LinkedHashMap<>();
        Map<Path, String> labels = new HashMap<>();
        for (GlossDocument document : documents) {
            String source = document.kind().word() + " " + document.id();
            Optional<String> mapped = GlossIds.toAnimus(document.id(), namespace);
            if (mapped.isEmpty()) { report.refused(source + ": the id cannot become an Animus id"); continue; }
            String id = mapped.get(), label = document.kind().word() + " " + id;
            String key = document.kind() + " " + id, first = claimed.putIfAbsent(key, document.id());
            if (first != null) { report.refused(source + ": maps to " + id + ", which " + first + " already took"); continue; }
            if (!id.substring(id.indexOf(':') + 1).equals(document.id())) report.note(source + " is imported as " + id);
            JsonObject parsed;
            try { parsed = JsonDocuments.object(document.json()); }
            catch (IOException invalid) { report.refused(label + ": " + invalid.getMessage()); continue; }
            String text = JsonDocuments.write(parsed);
            List<String> problems = validator.problems(document.kind(), id, text);
            if (!problems.isEmpty()) { report.refused(label + ": " + String.join("; ", problems.stream().limit(8).toList())); continue; }
            Path file = file(document.kind(), id);
            switch (GlossFolders.compare(file, text)) {
                case SAME -> report.unchanged(label);
                case DIFFERENT -> { if (replace) { accepted.put(file, text); labels.put(file, label); } else report.refused(label + ": a different file already exists; add replace=true to overwrite it"); }
                case MISSING -> { accepted.put(file, text); labels.put(file, label); }
            }
        }
        for (Map.Entry<Path, String> write : accepted.entrySet()) { GlossFolders.write(write.getKey(), write.getValue()); report.written(labels.get(write.getKey())); }
        return report.build();
    }

    /** Collects Animus documents for Gloss into {@code out}, optionally one kind and one Animus id. */
    public Report collect(Optional<GuiKind> kind, Optional<String> animusId, List<Exported> out) throws IOException {
        Report.Builder report = new Report.Builder();
        for (GuiKind each : GuiKind.values()) {
            if (kind.isPresent() && kind.get() != each) continue;
            Path root = data.resolve(each.folder());
            for (Path file : JsonDocumentTree.files(root)) {
                Path relative = root.relativize(file);
                if (relative.getNameCount() < 2) continue;
                String id = relative.getName(0) + ":" + relative.subpath(1, relative.getNameCount()).toString().replace('\\', '/').replaceFirst("\\.json$", "");
                if (animusId.isPresent() && !animusId.get().equals(id)) continue;
                String label = each.word() + " " + id;
                if (!id.matches(ShopDocument.ID)) { report.refused(label + ": not a valid Animus id"); continue; }
                JsonObject parsed;
                try { parsed = JsonDocuments.object(JsonDocuments.read(file)); }
                catch (IOException invalid) { report.refused(label + ": " + invalid.getMessage()); continue; }
                Optional<String> animusAction = animusAction(parsed);
                if (animusAction.isPresent()) { report.skipped(label + ": uses the Animus-only action " + animusAction.get()); continue; }
                Optional<String> glossId = GlossIds.toGloss(each, id);
                if (glossId.isEmpty()) { report.refused(label + ": has no valid Gloss id; Gloss ids start with a letter or digit"); continue; }
                List<String> references = new ArrayList<>();
                references(parsed, null, references);
                if (!references.isEmpty()) report.note(label + " names " + String.join(", ", references.stream().distinct().limit(8).toList()) + "; Gloss does not resolve namespaced ids");
                out.add(new Exported(each, id, glossId.get(), JsonDocuments.write(parsed), Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toMillis()));
            }
        }
        if (animusId.isPresent() && out.isEmpty() && report.build().skipped().isEmpty() && report.build().refused().isEmpty()) report.refused(animusId.get() + ": no such document");
        return report.build();
    }

    private Path file(GuiKind kind, String id) {
        int colon = id.indexOf(':');
        return data.resolve(kind.folder()).resolve(id.substring(0, colon)).resolve(id.substring(colon + 1) + ".json");
    }

    static Optional<String> animusAction(JsonElement value) {
        if (value.isJsonArray()) for (JsonElement item : value.getAsJsonArray()) { Optional<String> found = animusAction(item); if (found.isPresent()) return found; }
        if (!value.isJsonObject()) return Optional.empty();
        JsonElement type = value.getAsJsonObject().get("type");
        if (type != null && type.isJsonPrimitive() && type.getAsJsonPrimitive().isString() && type.getAsString().startsWith("animus:")) return Optional.of(type.getAsString());
        for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) { Optional<String> found = animusAction(entry.getValue()); if (found.isPresent()) return found; }
        return Optional.empty();
    }

    private static void references(JsonElement value, String field, List<String> found) {
        if (value.isJsonArray()) { for (JsonElement item : value.getAsJsonArray()) references(item, null, found); return; }
        if (value.isJsonObject()) { for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) references(entry.getValue(), entry.getKey(), found); return; }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return;
        String text = value.getAsString();
        if (field != null && REFERENCE_FIELDS.contains(field) && text.contains(":")) found.add(text);
        Matcher animation = ANIMATION.matcher(text);
        while (animation.find()) if (animation.group(1).contains(":")) found.add("|animation." + animation.group(1) + "|");
    }

    /** {@code /animus gui import ...} and {@code /animus gui export ...}; {@code reload} runs after an import wrote files. */
    public void command(CommandActor actor, String[] arguments, Runnable reload) {
        String usage = "gui import gloss <kind|all> [id|all] [namespace=<ns>] [replace=true] | gui import file <name> [namespace=<ns>] [replace=true]"
                + " | gui export gloss <kind|all> [id|all] [replace=true] | gui export file <name> [format=folder|bundle]";
        if (arguments.length < 3) { actor.tell(usage); return; }
        boolean importing = arguments[1].equalsIgnoreCase("import");
        String target = arguments[2].toLowerCase(Locale.ROOT);
        List<String> positional = new ArrayList<>();
        Map<String, String> options = new HashMap<>();
        for (int index = 3; index < arguments.length; index++) {
            int equals = arguments[index].indexOf('=');
            if (equals > 0) options.put(arguments[index].substring(0, equals).toLowerCase(Locale.ROOT), arguments[index].substring(equals + 1));
            else positional.add(arguments[index]);
        }
        for (String option : options.keySet())
            if (!Set.of("namespace", "replace", "format").contains(option)) { actor.tell("Unknown option " + option + "=; " + usage); return; }
        boolean replace = Boolean.parseBoolean(options.getOrDefault("replace", "false"));
        String namespace = options.getOrDefault("namespace", GlossIds.DEFAULT_NAMESPACE);
        GlossBridge<?> gloss = bridge.get();
        if (target.equals("gloss") && gloss.unavailable() != null) { actor.tell(gloss.unavailable()); return; }
        Optional<GuiKind> kind = Optional.empty();
        Optional<String> id = Optional.empty();
        if (target.equals("gloss")) {
            if (positional.isEmpty() || positional.size() > 2) { actor.tell(usage); return; }
            if (!positional.get(0).equalsIgnoreCase("all")) {
                kind = GuiKind.word(positional.get(0).toLowerCase(Locale.ROOT));
                if (kind.isEmpty()) { actor.tell("Unknown kind " + positional.get(0) + "; use inventory, menu, hologram, animation or all"); return; }
            }
            if (positional.size() == 2 && !positional.get(1).equalsIgnoreCase("all")) {
                if (kind.isEmpty()) { actor.tell("Name a kind to pick one id"); return; }
                id = Optional.of(positional.get(1));
            }
        } else if (target.equals("file")) {
            if (positional.size() != 1 || !positional.get(0).matches(NAME + "(\\.json)?")) { actor.tell("Name a folder or .json bundle directly under " + (importing ? "imports/inbox/" : "exports/gui/") + "; " + usage); return; }
        } else { actor.tell(usage); return; }
        Optional<GuiKind> chosenKind = kind;
        Optional<String> chosenId = id;
        String name = positional.isEmpty() ? "" : positional.get(0);
        String format = options.getOrDefault("format", name.endsWith(".json") ? "bundle" : "folder").toLowerCase(Locale.ROOT);
        if (!Set.of("folder", "bundle").contains(format)) { actor.tell("format is folder or bundle"); return; }
        try {
            files.execute(() -> {
                try {
                    Report report;
                    if (importing) {
                        GlossSources.Batch batch = target.equals("gloss")
                                ? new GlossSources.Batch(GlossFolders.readLive(gloss.folder(), chosenKind, chosenId), List.of())
                                : GlossSources.read(data.resolve("imports/inbox").resolve(name));
                        if (batch.documents().isEmpty() && batch.skipped().isEmpty()) { answer(actor, "Nothing to import."); return; }
                        report = importDocuments(batch, namespace, replace);
                    } else {
                        List<Exported> exported = new ArrayList<>();
                        report = collect(chosenKind, chosenId, exported);
                        if (target.equals("gloss")) report = report.plus(GlossFolders.writeLive(gloss.folder(), exported, replace));
                        else if (format.equals("bundle")) {
                            GlossBundle.write(data.resolve("exports/gui").resolve(name.endsWith(".json") ? name : name + ".json"), exported);
                            Report.Builder written = new Report.Builder();
                            for (Exported document : exported) written.written(document.kind().word() + " " + document.animusId() + " as " + document.glossId());
                            report = report.plus(written.build());
                        } else report = report.plus(GlossFolders.writeExport(data.resolve("exports/gui").resolve(name.replaceFirst("\\.json$", "")), exported));
                    }
                    tell(actor, (importing ? "GUI import: " : "GUI export: ") + report.summary(), report);
                    if (importing && !report.written().isEmpty()) reload.run();
                } catch (IOException | RuntimeException failure) { answer(actor, (importing ? "GUI import: " : "GUI export: ") + failure.getMessage()); }
            });
        } catch (RejectedExecutionException full) { actor.tell("Animus file queue is full; retry shortly."); }
    }

    /** Tab completion for the words after {@code gui import} or {@code gui export}. */
    public List<String> complete(String[] arguments) {
        if (arguments.length == 3) return matching(List.of("gloss", "file"), arguments[2]);
        if (arguments.length == 4 && arguments[2].equalsIgnoreCase("gloss")) return matching(List.of("all", "inventory", "menu", "hologram", "animation"), arguments[3]);
        if (arguments.length >= 4) return matching(arguments[1].equalsIgnoreCase("import") ? List.of("namespace=", "replace=true") : List.of("replace=true", "format=folder", "format=bundle"), arguments[arguments.length - 1]);
        return List.of();
    }

    private void tell(CommandActor actor, String summary, Report report) {
        List<String> lines = new ArrayList<>();
        lines.add(summary);
        report.written().stream().limit(20).forEach((String line) -> lines.add("Wrote " + line));
        report.refused().stream().limit(20).forEach((String line) -> lines.add("Refused " + line));
        report.skipped().stream().limit(20).forEach((String line) -> lines.add("Skipped " + line));
        report.notes().stream().limit(20).forEach((String line) -> lines.add("Note: " + line));
        if (!actor.reply(() -> lines.forEach(actor::tell))) lines.forEach(log);
    }

    private void answer(CommandActor actor, String message) { if (!actor.reply(() -> actor.tell(message))) log.accept(message); }

    private static List<String> matching(List<String> options, String prefix) {
        String lowered = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter((String option) -> option.startsWith(lowered)).toList();
    }
}
