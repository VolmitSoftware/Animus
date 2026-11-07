package art.arcane.animus.core.command;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Names in the data folder that commands take, for tab completion: inbox entries, import previews and applications,
 * rollback receipts and stored skins. One directory read each, capped, newest first where order matters; an unreadable
 * folder offers nothing.
 */
final class CompletionFiles {
    static final int READ = 200;
    static final int OFFERED = 30;
    private static final Pattern UUID_TEXT = Pattern.compile("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");
    private static final Pattern HASH = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern PLAIN_NAME = Pattern.compile("[A-Za-z0-9_.-]{1,128}");

    private CompletionFiles() {}

    /** Plain file names under {@code imports/inbox/}, such as {@code saves.yml}. */
    static List<String> inboxFiles(Path data) { return names(data.resolve("imports/inbox"), (Path entry) -> Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS), false, ""); }

    /** Folders under {@code imports/inbox/}, such as a dtlTraders data folder. */
    static List<String> inboxFolders(Path data) { return names(data.resolve("imports/inbox"), (Path entry) -> Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS), false, ""); }

    /** Every entry under {@code imports/inbox/}, files and folders. */
    static List<String> inbox(Path data) { return names(data.resolve("imports/inbox"), (Path entry) -> true, false, ""); }

    /** Import preview ids, the 64-character folders under {@code imports/}, newest first. */
    static List<String> previews(Path data) {
        return names(data.resolve("imports"), (Path entry) -> Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) && HASH.matcher(entry.getFileName().toString()).matches(), true, "");
    }

    /** Application ids staged for the previews, newest first. */
    static List<String> applications(Path data) {
        List<String> found = new ArrayList<>();
        for (String preview : previews(data)) {
            found.addAll(names(data.resolve("imports/" + preview + "/applications"), (Path entry) -> Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS), true, ""));
            if (found.size() >= OFFERED) break;
        }
        return found.stream().distinct().limit(OFFERED).toList();
    }

    /** Transaction ids with a rollback receipt under {@code backups/}, newest first. */
    static List<String> transactions(Path data) {
        return names(data.resolve("backups"), (Path entry) -> UUID_TEXT.matcher(stem(entry)).matches(), true, ".json");
    }

    /** Stored skin hashes, {@code skins/<sha256>.json}. */
    static List<String> skins(Path data) { return names(data.resolve("skins"), (Path entry) -> HASH.matcher(stem(entry)).matches(), true, ".json"); }

    private static String stem(Path entry) {
        String name = entry.getFileName().toString();
        return name.endsWith(".json") ? name.substring(0, name.length() - 5) : name;
    }

    private static List<String> names(Path folder, Predicate<Path> accepted, boolean newestFirst, String suffix) {
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) return List.of();
        record Entry(String name, FileTime modified) {}
        List<Entry> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder)) {
            for (Path entry : stream) {
                if (entries.size() >= READ) break;
                String name = entry.getFileName().toString();
                if (!PLAIN_NAME.matcher(name).matches() || name.startsWith(".") || !accepted.test(entry)) continue;
                if (!suffix.isEmpty() && !name.endsWith(suffix)) continue;
                FileTime modified = newestFirst ? Files.getLastModifiedTime(entry, LinkOption.NOFOLLOW_LINKS) : FileTime.fromMillis(0);
                entries.add(new Entry(suffix.isEmpty() ? name : name.substring(0, name.length() - suffix.length()), modified));
            }
        } catch (IOException | RuntimeException unreadable) { return List.of(); }
        Comparator<Entry> order = newestFirst ? Comparator.comparing(Entry::modified).reversed() : Comparator.comparing(Entry::name, String.CASE_INSENSITIVE_ORDER);
        return entries.stream().sorted(order).map(Entry::name).limit(OFFERED).toList();
    }
}
