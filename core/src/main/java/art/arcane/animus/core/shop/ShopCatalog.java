package art.arcane.animus.core.shop;

import art.arcane.animus.core.gui.GuiDocument;
import art.arcane.animus.core.storage.JsonDocumentTree;
import art.arcane.animus.core.storage.JsonDocuments;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * Every chest GUI under {@code gui/inventories/} and every shop under {@code shops/}, read off the server thread. A
 * file at {@code <namespace>/<path>.json} has the id {@code namespace:path}. A document with any problem is left out
 * and its problems are listed; one that runs commands needs its id or namespace on the command allow-list.
 */
public record ShopCatalog(Map<String, GuiDocument> guis, Map<String, ShopDocument> shops, List<String> problems) {
    public static final ShopCatalog EMPTY = new ShopCatalog(Map.of(), Map.of(), List.of());
    public static final String GUI_FOLDER = "gui/inventories", SHOP_FOLDER = "shops";

    public ShopCatalog { guis = Map.copyOf(guis); shops = Map.copyOf(shops); problems = List.copyOf(problems); }

    public Optional<GuiDocument> gui(String id) { return Optional.ofNullable(guis.get(id)); }
    public Optional<ShopDocument> shop(String id) { return Optional.ofNullable(shops.get(id)); }

    public static ShopCatalog load(Path data, Predicate<String> commandsAllowed) throws IOException {
        Map<String, GuiDocument> guis = new TreeMap<>();
        Map<String, ShopDocument> shops = new TreeMap<>();
        List<String> problems = new ArrayList<>();
        Path guiRoot = data.resolve(GUI_FOLDER);
        for (Path file : JsonDocumentTree.files(guiRoot)) {
            Optional<String> id = id(guiRoot, file, problems);
            if (id.isEmpty()) continue;
            GuiDocument.Result result = GuiDocument.parse(id.get(), read(file, problems));
            String label = GUI_FOLDER + "/" + guiRoot.relativize(file).toString().replace('\\', '/');
            result.problems().forEach((String problem) -> problems.add(label + " " + problem));
            result.document().ifPresent((GuiDocument document) -> {
                if (document.hasCommands() && !commandsAllowed.test(document.id())) problems.add(label + " $: runs commands, so add " + document.id()
                        + " to commandBehaviors or its namespace to commandNamespaces under [behaviors.actions] in config.toml");
                else guis.put(document.id(), document);
            });
        }
        Path shopRoot = data.resolve(SHOP_FOLDER);
        for (Path file : JsonDocumentTree.files(shopRoot)) {
            Optional<String> id = id(shopRoot, file, problems);
            if (id.isEmpty()) continue;
            ShopDocument.Result result = ShopDocument.parse(id.get(), read(file, problems));
            String label = SHOP_FOLDER + "/" + shopRoot.relativize(file).toString().replace('\\', '/');
            result.problems().forEach((String problem) -> problems.add(label + " " + problem));
            result.shop().ifPresent((ShopDocument shop) -> {
                if (shop.hasCommands() && !commandsAllowed.test(shop.id())) problems.add(label + " $: runs commands, so add " + shop.id()
                        + " to commandBehaviors or its namespace to commandNamespaces under [behaviors.actions] in config.toml");
                else shops.put(shop.id(), shop);
            });
        }
        return new ShopCatalog(guis, shops, problems);
    }

    /**
     * This catalog plus each document of {@code previous} that no longer loads while its file is still there, so a
     * broken edit keeps the last valid version. Deleted files drop their documents; command refusals are never kept.
     */
    public ShopCatalog retaining(ShopCatalog previous, Path data, Predicate<String> commandsAllowed) {
        Map<String, GuiDocument> keptGuis = new TreeMap<>(guis);
        for (Map.Entry<String, GuiDocument> entry : previous.guis.entrySet())
            if (!keptGuis.containsKey(entry.getKey()) && present(data, GUI_FOLDER, entry.getKey()) && (!entry.getValue().hasCommands() || commandsAllowed.test(entry.getKey())))
                keptGuis.put(entry.getKey(), entry.getValue());
        Map<String, ShopDocument> keptShops = new TreeMap<>(shops);
        for (Map.Entry<String, ShopDocument> entry : previous.shops.entrySet())
            if (!keptShops.containsKey(entry.getKey()) && present(data, SHOP_FOLDER, entry.getKey()) && (!entry.getValue().hasCommands() || commandsAllowed.test(entry.getKey())))
                keptShops.put(entry.getKey(), entry.getValue());
        return new ShopCatalog(keptGuis, keptShops, problems);
    }

    /** The {@code namespace:path} document's file exists under {@code folder}. */
    public static boolean present(Path data, String folder, String id) {
        int colon = id.indexOf(':');
        if (colon < 1) return false;
        return java.nio.file.Files.isRegularFile(data.resolve(folder).resolve(id.substring(0, colon)).resolve(id.substring(colon + 1) + ".json"),
                java.nio.file.LinkOption.NOFOLLOW_LINKS);
    }

    private static String read(Path file, List<String> problems) {
        try { return JsonDocuments.read(file); }
        catch (IOException unreadable) { problems.add(file + ": " + unreadable.getMessage()); return "{}"; }
    }

    private static Optional<String> id(Path root, Path file, List<String> problems) {
        Path relative = root.relativize(file);
        String text = relative.toString().replace('\\', '/');
        if (relative.getNameCount() < 2) { problems.add(text + ": place documents in a namespace folder, such as town/" + text); return Optional.empty(); }
        String namespace = relative.getName(0).toString();
        String path = relative.subpath(1, relative.getNameCount()).toString().replace('\\', '/').replaceFirst("\\.json$", "");
        String id = namespace + ":" + path;
        if (!id.matches(ShopDocument.ID)) { problems.add(text + ": file names use lowercase letters, digits, _, - and /"); return Optional.empty(); }
        return Optional.of(id);
    }
}
