package art.arcane.animus.core.migration;

import art.arcane.animus.core.npc.NpcDisguise;
import art.arcane.animus.core.storage.DocumentStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * LibsDisguises disguises Citizens NPCs wore, by entity UUID: the files LibsDisguises saves under
 * {@code SavedDisguises/<uuid>}, and script lines that run {@code /disguiseplayer <uuid> <disguise>} (or one of its
 * aliases) for a UUID written out, in Denizen and Skript scripts. A Citizens player NPC's entity has its own UUID, the
 * NPC's with version 2, so both are looked up.
 */
public final class LibsDisguisesSaves {
    /** One disguise and the file it was found in, as the operator would name it. */
    public record Found(String disguise, String source) {}
    /** Every disguise found, and the files or lines that could not be read. */
    public record Result(Map<UUID, Found> found, List<String> problems) {
        public static final Result NONE = new Result(Map.of(), List.of());
        public Result { found = Map.copyOf(found); problems = List.copyOf(problems); }
    }

    static final Pattern COMMAND = Pattern.compile("(?i)(?<![a-z])/?(?:disguiseplayer|dplayer|displayer|disgplayer|disguiseuuid|disguuid|duuid)\\s+"
            + "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\s+([^\"'\\r\\n]+)");
    private static final Pattern PLACEHOLDER = Pattern.compile("<[^>]+>|%[^%\\s]+%");
    private static final long LARGEST = 1 << 20;
    private static final int DEPTH = 8;

    private LibsDisguisesSaves() {}

    /** Reads imports/libsdisguises/ first, then the LibsDisguises, Denizen and Skript folders under plugins/. The first disguise found for a UUID wins. */
    public static Result read(DocumentStore store, Optional<Path> plugins) throws IOException {
        Map<UUID, Found> found = new TreeMap<>();
        List<String> problems = new ArrayList<>();
        saved(store.resolve("imports/libsdisguises/SavedDisguises"), "imports/libsdisguises/SavedDisguises", found, problems);
        scripts(store.resolve("imports/libsdisguises/scripts"), "imports/libsdisguises/scripts", found, problems);
        if (plugins.isPresent()) {
            saved(plugins.get().resolve("LibsDisguises/SavedDisguises"), "plugins/LibsDisguises/SavedDisguises", found, problems);
            scripts(plugins.get().resolve("Denizen/scripts"), "plugins/Denizen/scripts", found, problems);
            scripts(plugins.get().resolve("Skript/scripts"), "plugins/Skript/scripts", found, problems);
        }
        return new Result(found, problems);
    }

    /** The disguise of the Citizens NPC {@code uuid}, under its own UUID or, for a player body, its entity's. */
    public static Optional<Found> of(Result result, UUID uuid, String type) {
        Found own = result.found().get(uuid);
        if (own != null || !type.equals("minecraft:player") || uuid.version() != 4) return Optional.ofNullable(own);
        long most = uuid.getMostSignificantBits() & ~0x0000000000004000L | 0x0000000000002000L;
        return Optional.ofNullable(result.found().get(new UUID(most, uuid.getLeastSignificantBits())));
    }

    /** {@code SavedDisguises/<uuid>}: one disguise per line, the first of which the entity wore. */
    private static void saved(Path folder, String label, Map<UUID, Found> found, List<String> problems) throws IOException {
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) return;
        try (Stream<Path> files = Files.list(folder)) {
            for (Path file : files.sorted().toList()) {
                String name = file.getFileName().toString();
                UUID uuid;
                try { uuid = UUID.fromString(name.toLowerCase(Locale.ROOT)); }
                catch (IllegalArgumentException notUuid) { continue; }
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > LARGEST) continue;
                String text = lenient(file).strip();
                if (text.isEmpty()) continue;
                if (!Character.isAlphabetic(text.charAt(0))) {
                    problems.add(label + "/" + name + ": saved in LibsDisguises' old format; disguise the NPC again on a current LibsDisguises, or set its disguise in Animus");
                    continue;
                }
                String first = text.lines().map(String::strip).filter((String line) -> !line.isEmpty()).findFirst().orElse("");
                accept(uuid, first, label + "/" + name, found, problems);
            }
        }
    }

    private static void scripts(Path folder, String label, Map<UUID, Found> found, List<String> problems) throws IOException {
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) return;
        List<Path> files;
        try (Stream<Path> walked = Files.walk(folder, DEPTH)) {
            files = walked.filter((Path file) -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                    .filter((Path file) -> file.getFileName().toString().matches("(?i).+\\.(dsc|yml|yaml|sk|txt)")).sorted().toList();
        }
        for (Path file : files) {
            if (Files.size(file) > LARGEST) continue;
            String name = label + "/" + folder.relativize(file).toString().replace('\\', '/');
            List<String> lines = lenient(file).lines().toList();
            for (int i = 0; i < lines.size(); i++) {
                Matcher command = COMMAND.matcher(lines.get(i));
                while (command.find()) accept(UUID.fromString(command.group(1).toLowerCase(Locale.ROOT)), command.group(2).strip(), name + " line " + (i + 1), found, problems);
            }
        }
    }

    /** The file as UTF-8, with any byte that is not UTF-8 read as U+FFFD, so a script saved in another charset is still read. */
    private static String lenient(Path file) throws IOException { return new String(Files.readAllBytes(file), StandardCharsets.UTF_8); }

    private static void accept(UUID uuid, String disguise, String source, Map<UUID, Found> found, List<String> problems) {
        if (found.containsKey(uuid)) return;
        if (PLACEHOLDER.matcher(disguise).find()) { problems.add(source + ": the disguise '" + disguise + "' uses placeholders, which only the script fills in; not imported"); return; }
        if (disguise.length() > NpcDisguise.MAX_LENGTH || disguise.codePoints().anyMatch(Character::isISOControl)) { problems.add(source + ": the disguise text is too long or has control characters; not imported"); return; }
        found.put(uuid, new Found(disguise, source));
    }
}
