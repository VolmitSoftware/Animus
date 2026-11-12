package art.arcane.animus.core.gui;

import art.arcane.animus.core.shop.ShopCatalog;
import art.arcane.animus.core.storage.JsonDocumentTree;
import art.arcane.animus.core.storage.JsonDocuments;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * Every hologram under {@code gui/holograms/}, holographic menu under {@code gui/menus/} and text animation under
 * {@code gui/text-animations/}, read off the server thread. A file at {@code <namespace>/<path>.json} has the id
 * {@code namespace:path}. A document with any problem is left out with its problems; one that runs commands needs
 * its id or namespace on the command allow-list; one that shows a missing animation is refused.
 */
public record HoloCatalog(Map<String, HologramDocument> holograms, Map<String, MenuDocument> menus, Map<String, TextAnimation> animations, List<String> problems) {
    public static final HoloCatalog EMPTY = new HoloCatalog(Map.of(), Map.of(), Map.of(), List.of());
    public static final String ID = "[a-z0-9_-]+:[a-z0-9_/-]+";

    public HoloCatalog { holograms = Map.copyOf(holograms); menus = Map.copyOf(menus); animations = Map.copyOf(animations); problems = List.copyOf(problems); }

    public Optional<HologramDocument> hologram(String id) { return Optional.ofNullable(holograms.get(id)); }
    public Optional<MenuDocument> menu(String id) { return Optional.ofNullable(menus.get(id)); }
    public Optional<TextAnimation> animation(String id) { return Optional.ofNullable(animations.get(id)); }

    public static HoloCatalog load(Path data, Predicate<String> commandsAllowed) throws IOException {
        List<String> problems = new ArrayList<>();
        Map<String, TextAnimation> animations = new TreeMap<>();
        for (Loaded loaded : read(data, GuiKind.ANIMATION, problems)) {
            TextAnimation.Result result = TextAnimation.parse(loaded.id(), loaded.json());
            result.problems().forEach((String problem) -> problems.add(loaded.label() + " " + problem));
            result.animation().ifPresent((TextAnimation animation) -> animations.put(animation.id(), animation));
        }
        Map<String, HologramDocument> holograms = new TreeMap<>();
        for (Loaded loaded : read(data, GuiKind.HOLOGRAM, problems)) {
            HologramDocument.Result result = HologramDocument.parse(loaded.id(), loaded.json());
            result.problems().forEach((String problem) -> problems.add(loaded.label() + " " + problem));
            result.document().ifPresent((HologramDocument document) -> {
                Optional<String> refused = refusal(document.id(), document.hasCommands(), document.animations(), animations.keySet(), commandsAllowed);
                if (refused.isPresent()) problems.add(loaded.label() + " " + refused.get());
                else holograms.put(document.id(), document);
            });
        }
        Map<String, MenuDocument> menus = new TreeMap<>();
        for (Loaded loaded : read(data, GuiKind.MENU, problems)) {
            MenuDocument.Result result = MenuDocument.parse(loaded.id(), loaded.json());
            result.problems().forEach((String problem) -> problems.add(loaded.label() + " " + problem));
            result.document().ifPresent((MenuDocument document) -> {
                Optional<String> refused = refusal(document.id(), document.hasCommands(), document.animations(), animations.keySet(), commandsAllowed);
                if (refused.isPresent()) problems.add(loaded.label() + " " + refused.get());
                else menus.put(document.id(), document);
            });
        }
        return new HoloCatalog(holograms, menus, animations, problems);
    }

    /**
     * This catalog plus each document of {@code previous} that no longer loads while its file is still there, so a
     * broken edit keeps the last valid version. Deleted files drop their documents; command refusals are never kept.
     */
    public HoloCatalog retaining(HoloCatalog previous, Path data, Predicate<String> commandsAllowed) {
        Map<String, TextAnimation> keptAnimations = new TreeMap<>(animations);
        for (Map.Entry<String, TextAnimation> entry : previous.animations.entrySet())
            if (!keptAnimations.containsKey(entry.getKey()) && ShopCatalog.present(data, GuiKind.ANIMATION.folder(), entry.getKey()))
                keptAnimations.put(entry.getKey(), entry.getValue());
        Map<String, HologramDocument> keptHolograms = new TreeMap<>(holograms);
        for (Map.Entry<String, HologramDocument> entry : previous.holograms.entrySet())
            if (!keptHolograms.containsKey(entry.getKey()) && ShopCatalog.present(data, GuiKind.HOLOGRAM.folder(), entry.getKey())
                    && (!entry.getValue().hasCommands() || commandsAllowed.test(entry.getKey())))
                keptHolograms.put(entry.getKey(), entry.getValue());
        Map<String, MenuDocument> keptMenus = new TreeMap<>(menus);
        for (Map.Entry<String, MenuDocument> entry : previous.menus.entrySet())
            if (!keptMenus.containsKey(entry.getKey()) && ShopCatalog.present(data, GuiKind.MENU.folder(), entry.getKey())
                    && (!entry.getValue().hasCommands() || commandsAllowed.test(entry.getKey())))
                keptMenus.put(entry.getKey(), entry.getValue());
        return new HoloCatalog(keptHolograms, keptMenus, keptAnimations, problems);
    }

    /**
     * Checks one document as {@link #load} and {@code ShopCatalog} would, against the animations already on disk and
     * those the same import accepted. Imports call this before writing anything.
     */
    public static GuiValidator validator(Path data, Predicate<String> commandsAllowed) {
        return (GuiKind kind, String id, String json, Map<GuiKind, Set<String>> pending) -> {
            if (!id.matches(ID)) return List.of("$: id '" + id + "' is not namespace:path with lowercase letters, digits, _, - and /");
            List<String> problems = new ArrayList<>();
            switch (kind) {
                case INVENTORY -> {
                    GuiDocument.Result result = GuiDocument.parse(id, json);
                    problems.addAll(result.problems());
                    result.document().filter((GuiDocument document) -> document.hasCommands() && !commandsAllowed.test(document.id()))
                            .ifPresent((GuiDocument document) -> problems.add(commandsRefusal(id)));
                }
                case ANIMATION -> problems.addAll(TextAnimation.parse(id, json).problems());
                case HOLOGRAM -> {
                    HologramDocument.Result result = HologramDocument.parse(id, json);
                    problems.addAll(result.problems());
                    result.document().flatMap((HologramDocument document) -> refusal(id, document.hasCommands(), document.animations(), known(data, id, document.animations(), pending.getOrDefault(GuiKind.ANIMATION, Set.of())), commandsAllowed)).ifPresent(problems::add);
                }
                case MENU -> {
                    MenuDocument.Result result = MenuDocument.parse(id, json);
                    problems.addAll(result.problems());
                    result.document().flatMap((MenuDocument document) -> refusal(id, document.hasCommands(), document.animations(), known(data, id, document.animations(), pending.getOrDefault(GuiKind.ANIMATION, Set.of())), commandsAllowed)).ifPresent(problems::add);
                }
            }
            return List.copyOf(problems);
        };
    }

    /** The referenced animations that exist as files or that the same import accepted. */
    private static Set<String> known(Path data, String from, Set<String> referenced, Set<String> pending) {
        Set<String> found = new java.util.HashSet<>();
        for (String reference : referenced) {
            String id = GuiIds.relative(from, reference);
            int colon = id.indexOf(':');
            if (pending.contains(id)) found.add(id);
            else if (colon > 0 && java.nio.file.Files.isRegularFile(data.resolve(GuiKind.ANIMATION.folder()).resolve(id.substring(0, colon)).resolve(id.substring(colon + 1) + ".json")))
                found.add(id);
        }
        return found;
    }

    private static Optional<String> refusal(String id, boolean commands, Set<String> referenced, Set<String> animations, Predicate<String> commandsAllowed) {
        if (commands && !commandsAllowed.test(id)) return Optional.of(commandsRefusal(id));
        for (String reference : referenced) {
            String resolved = GuiIds.relative(id, reference);
            if (!animations.contains(resolved)) return Optional.of("$: shows text animation '" + resolved + "', which is not loaded");
        }
        return Optional.empty();
    }

    private static String commandsRefusal(String id) {
        return "$: runs commands, so add " + id + " to commandBehaviors or its namespace to commandNamespaces under [behaviors.actions] in config.toml";
    }

    private record Loaded(String id, String json, String label) {}

    private static List<Loaded> read(Path data, GuiKind kind, List<String> problems) throws IOException {
        Path root = data.resolve(kind.folder());
        List<Loaded> loaded = new ArrayList<>();
        for (Path file : JsonDocumentTree.files(root)) {
            Path relative = root.relativize(file);
            String text = relative.toString().replace('\\', '/');
            String label = kind.folder() + "/" + text;
            if (relative.getNameCount() < 2) { problems.add(label + ": place documents in a namespace folder, such as town/" + text); continue; }
            String id = relative.getName(0) + ":" + relative.subpath(1, relative.getNameCount()).toString().replace('\\', '/').replaceFirst("\\.json$", "");
            if (!id.matches(ID)) { problems.add(label + ": file names use lowercase letters, digits, _, - and /"); continue; }
            String json;
            try { json = JsonDocuments.read(file); }
            catch (IOException unreadable) { problems.add(label + ": " + unreadable.getMessage()); continue; }
            loaded.add(new Loaded(id, json, label));
        }
        return loaded;
    }
}
