package art.arcane.animus.core.migration;

import art.arcane.animus.core.migration.ImportReport.Status;
import art.arcane.animus.core.shop.ShopDocument;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Converts a dtlTraders data folder into Animus shop documents. Version 6 files are {@code shops/<name>/<name>.yml},
 * version 5 keeps every shop in {@code guis.yml}; with a Citizens {@code saves.yml}, version 3 traders that still hold
 * their own stock become {@code converted-<npc>} shops and every trader link is checked. The binary {@code data.dat}
 * is copied as it is and, when its whole structure reads, its per-player counts become shop limit state.
 * <p>
 * A preview stages everything under {@code imports/shops/<id>/} with the originals, the converted shops, the limit
 * counts and a report classifying every source field. Nothing is installed until {@link #apply}.
 */
public final class ShopImporter {
    public static final int VERSION = 1;
    private static final long MAXIMUM_FILE = 4L * 1024 * 1024, MAXIMUM_DATA = 64L * 1024 * 1024;
    private static final int MAXIMUM_SHOPS = 512, MAXIMUM_LIMIT_PLAYERS = 1_000_000;
    private static final String SHOP_FILE = "shops/dtltraders/[a-z0-9_-]{1,64}\\.json";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    public record Preview(String id, Path directory, int shops, int entries, int notes, JsonObject summary) {
        public Preview { summary = summary.deepCopy(); }
        @Override public JsonObject summary() { return summary.deepCopy(); }
        public int issues() { return summary.getAsJsonArray("issues").size(); }
        public String limitStatus() { return summary.getAsJsonObject("limitData").get("status").getAsString(); }
    }

    /** Stages a preview; the same sources give the same id and reuse the staged preview. */
    public Preview preview(Path folder, Optional<Path> saves, DocumentStore store) throws IOException {
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Not a dtlTraders data folder: " + folder);
        Map<String, Path> sources = sources(folder);
        StringBuilder identity = new StringBuilder("animus-dtltraders-v" + VERSION + "\n");
        for (Map.Entry<String, Path> source : sources.entrySet()) identity.append(source.getKey()).append('=').append(hash(source.getValue())).append('\n');
        String savesText = null;
        if (saves.isPresent()) {
            if (Files.size(saves.get()) > MAXIMUM_DATA) throw new IOException("saves.yml exceeds " + MAXIMUM_DATA + " bytes");
            savesText = JsonDocuments.read(saves.get());
            identity.append("saves=").append(JsonDocuments.hash(savesText)).append('\n');
        }
        String id = JsonDocuments.hash(identity.toString());
        Path imports = store.resolve("imports/shops");
        Files.createDirectories(imports);
        Path staged = imports.resolve(id);
        if (Files.exists(staged)) return read(staged, id);
        Path temporary = Files.createTempDirectory(imports, ".preview-");
        boolean published = false;
        try {
            JsonObject summary = new JsonObject();
            summary.addProperty("schemaVersion", 1);
            summary.addProperty("importerVersion", VERSION);
            summary.addProperty("importId", id);
            summary.addProperty("notice", "Data conversion only. Converted shops install under shops/dtltraders/. Run each shop in game before relying on it.");
            JsonObject files = new JsonObject();
            for (Map.Entry<String, Path> source : sources.entrySet()) {
                Path copy = temporary.resolve("originals").resolve(source.getKey());
                Files.createDirectories(copy.getParent());
                Files.copy(source.getValue(), copy, StandardCopyOption.COPY_ATTRIBUTES);
                files.addProperty(source.getKey(), hash(source.getValue()));
            }
            summary.add("originals", files);
            JsonObject manifest = new JsonObject();
            JsonObject shops = new JsonObject();
            JsonArray issues = new JsonArray();
            Map<Integer, String[]> limitKeys = new HashMap<>();
            Set<String> ids = new HashSet<>();
            int entries = 0, notes = 0;
            for (Map.Entry<String, Path> source : sources.entrySet()) {
                if (!source.getKey().endsWith(".yml") || !(source.getKey().startsWith("shops/") || source.getKey().equals("guis.yml"))) continue;
                JsonObject root = CitizensImporter.parseYaml(JsonDocuments.read(source.getValue()));
                for (Map.Entry<String, JsonElement> shop : root.entrySet()) {
                    if (!shop.getValue().isJsonObject()) { issues.add(source.getKey() + ": " + shop.getKey() + " is not a shop mapping"); continue; }
                    if (ids.size() >= MAXIMUM_SHOPS) throw new IOException("More than " + MAXIMUM_SHOPS + " shops");
                    ImportReport report = new ImportReport();
                    String shopId = unique(TraderMapping.shopId(shop.getKey()), ids);
                    JsonObject document = convertShop(shopId, shop.getKey(), shop.getValue().getAsJsonObject(), report, limitKeys);
                    entries += stage(temporary, shopId, document, source.getKey(), shop.getKey(), report, shops, manifest, issues);
                    notes += report.notes();
                }
            }
            JsonArray links = new JsonArray();
            if (savesText != null) {
                JsonObject root = CitizensImporter.parseYaml(savesText);
                JsonObject records = new JsonObject();
                JsonObject npcs = root.has("npc") && root.get("npc").isJsonObject() ? root.getAsJsonObject("npc") : new JsonObject();
                for (Map.Entry<String, JsonElement> npc : npcs.entrySet()) {
                    if (!npc.getValue().isJsonObject()) continue;
                    JsonObject record = npc.getValue().getAsJsonObject();
                    JsonObject traits = record.has("traits") && record.get("traits").isJsonObject() ? record.getAsJsonObject("traits") : new JsonObject();
                    if (!traits.has("trader") && !traits.has("wallet")) continue;
                    JsonObject kept = new JsonObject();
                    for (String trait : List.of("trader", "wallet")) if (traits.has(trait)) kept.add(trait, traits.get(trait).deepCopy());
                    records.add(npc.getKey(), kept);
                    String name = record.has("name") && record.get("name").isJsonPrimitive() ? record.get("name").getAsString() : "Citizen " + npc.getKey();
                    if (!traits.has("trader")) continue;
                    JsonObject link = new JsonObject();
                    link.addProperty("citizensId", npc.getKey());
                    link.addProperty("npc", name);
                    JsonElement trader = traits.get("trader");
                    if (trader.isJsonObject() && TraderMapping.legacy(trader.getAsJsonObject())) {
                        String legacyName = TraderMapping.convertedName(name);
                        ImportReport report = new ImportReport();
                        String shopId = TraderMapping.shopId(legacyName);
                        if (ids.contains(shopId)) { link.addProperty("status", "converted-name-taken"); link.addProperty("shop", shopId); links.add(link); continue; }
                        ids.add(shopId);
                        JsonObject document = convertLegacy(shopId, legacyName, trader.getAsJsonObject(), report);
                        entries += stage(temporary, shopId, document, "saves.yml", "npc." + npc.getKey() + ".traits.trader", report, shops, manifest, issues);
                        notes += report.notes();
                        link.addProperty("shop", shopId);
                        link.addProperty("status", "converted-from-version-3");
                    } else {
                        TraderMapping.Result mapped = TraderMapping.map(trader, name);
                        if (mapped.module().isEmpty()) { link.addProperty("status", "no-shop"); }
                        else {
                            String shopId = mapped.module().get().get("shop").getAsString();
                            link.addProperty("shop", shopId);
                            link.addProperty("status", shops.has(shopId) ? "linked" : "missing-shop");
                            if (!shops.has(shopId)) issues.add("Citizens NPC " + npc.getKey() + " (" + name + ") links to " + shopId + ", which this folder does not contain; the NPC gets no shop until one exists");
                        }
                    }
                    links.add(link);
                }
                DocumentStore.atomic(temporary.resolve("originals/saves-trader-records.json"), JsonDocuments.write(records));
            }
            summary.add("links", links);
            JsonObject data = new JsonObject();
            if (sources.containsKey("data.dat")) {
                JsonObject limits = limits(Files.readAllBytes(sources.get("data.dat")), limitKeys, data);
                if (limits != null) {
                    String text = JsonDocuments.write(limits);
                    DocumentStore.atomic(temporary.resolve("limits.json"), text);
                    manifest.addProperty("limits.json", JsonDocuments.hash(text));
                }
            } else data.addProperty("status", "absent");
            summary.add("limitData", data);
            summary.addProperty("shops", shops.size());
            summary.addProperty("entries", entries);
            summary.add("shopMap", shops);
            summary.add("issues", issues);
            summary.add("documents", manifest);
            DocumentStore.atomic(temporary.resolve("report.json"), JsonDocuments.write(summary));
            Files.move(temporary, staged, StandardCopyOption.ATOMIC_MOVE);
            published = true;
            return new Preview(id, staged, shops.size(), entries, notes, summary);
        } finally {
            if (!published) delete(temporary);
        }
    }

    public Preview read(Path directory, String id) throws IOException {
        try {
            JsonObject summary = JsonDocuments.object(JsonDocuments.read(directory.resolve("report.json")));
            if (summary.get("importerVersion").getAsInt() > VERSION || !summary.get("importId").getAsString().equals(id)) throw new IOException("Unsupported or mismatched shop import report");
            return new Preview(id, directory, summary.get("shops").getAsInt(), summary.get("entries").getAsInt(), 0, summary);
        } catch (RuntimeException invalid) { throw new IOException("Invalid shop import report: " + directory.resolve("report.json"), invalid); }
    }

    /**
     * Installs the staged shops and limit counts as one transaction that {@code rollback} can undo. A shop file that
     * already exists with other content stops the whole apply.
     */
    public String apply(DocumentStore store, String id) throws IOException {
        if (!id.matches("[a-f0-9]{64}")) throw new IOException("Invalid import ID");
        if (store.read("imports/shops/" + id + "/receipt.json") != null) return "already-applied";
        return apply(store, id, UUID.randomUUID().toString());
    }

    /** The shop files {@link #apply} installs, by path, read from the staged preview; nothing is installed. */
    public Map<String, String> documents(DocumentStore store, String id) throws IOException {
        if (!id.matches("[a-f0-9]{64}")) throw new IOException("Invalid import ID");
        Path staged = store.resolve("imports/shops/" + id);
        Map<String, String> documents = new TreeMap<>();
        for (String relative : read(staged, id).summary().getAsJsonObject("documents").keySet())
            if (relative.matches(SHOP_FILE)) documents.put(relative, JsonDocuments.read(staged.resolve(relative)));
        return documents;
    }

    /**
     * Installs the staged shops under {@code transaction}. When the import is already installed, returns the transaction
     * its receipt names, so a retried apply records the real id.
     */
    public String apply(DocumentStore store, String id, String transaction) throws IOException {
        if (!id.matches("[a-f0-9]{64}")) throw new IOException("Invalid import ID");
        Path staged = store.resolve("imports/shops/" + id);
        Preview preview = read(staged, id);
        String receiptPath = "imports/shops/" + id + "/receipt.json";
        String existing = store.read(receiptPath);
        if (existing != null) {
            JsonElement recorded = JsonDocuments.object(existing).get("transaction");
            if (recorded == null || !recorded.isJsonPrimitive()) throw new IOException("This shop import was installed by an older Animus that did not record its transaction; run preview again");
            return recorded.getAsString();
        }
        if (!preview.summary().getAsJsonArray("issues").isEmpty() && preview.shops() == 0) throw new IOException("Import has no shops; inspect report.json");
        List<DocumentStore.Change> changes = new ArrayList<>();
        for (Map.Entry<String, JsonElement> document : preview.summary().getAsJsonObject("documents").entrySet()) {
            String relative = document.getKey();
            String content = JsonDocuments.read(staged.resolve(relative));
            if (!JsonDocuments.hash(content).equals(document.getValue().getAsString())) throw new IOException("Preview changed: " + relative + "; make a new preview");
            if (relative.equals("limits.json")) { changes.add(new DocumentStore.Change("state/shops/imports/" + id + ".json", "missing", content)); continue; }
            if (!relative.matches(SHOP_FILE)) throw new IOException("Invalid bundle path: " + relative);
            String before = store.read(relative);
            if (before != null && before.equals(content)) continue;
            if (before != null) throw new IOException("Shop collision: " + relative + " already exists with other content");
            changes.add(new DocumentStore.Change(relative, "missing", content));
        }
        JsonObject receipt = new JsonObject();
        receipt.addProperty("importId", id);
        receipt.addProperty("shops", preview.shops());
        receipt.addProperty("transaction", transaction);
        changes.add(new DocumentStore.Change(receiptPath, "missing", JsonDocuments.write(receipt)));
        return store.commit(transaction, changes);
    }

    private static int stage(Path temporary, String shopId, JsonObject document, String file, String key, ImportReport report, JsonObject shops,
                             JsonObject manifest, JsonArray issues) throws IOException {
        String path = shopId.substring(shopId.indexOf(':') + 1);
        String relative = "shops/" + TraderMapping.NAMESPACE + "/" + path + ".json";
        String text = JsonDocuments.write(document);
        ShopDocument.Result checked = ShopDocument.parse(shopId, document);
        DocumentStore.atomic(temporary.resolve(relative), text);
        manifest.addProperty(relative, JsonDocuments.hash(text));
        JsonObject entry = new JsonObject();
        entry.addProperty("source", file);
        entry.addProperty("key", key);
        entry.addProperty("document", relative);
        JsonArray problems = new JsonArray();
        checked.problems().forEach(problems::add);
        entry.add("validationErrors", problems);
        if (!checked.problems().isEmpty()) issues.add(relative + ": " + String.join("; ", checked.problems()));
        int entries = checked.shop().map((ShopDocument shop) -> shop.pages().stream().mapToInt((ShopDocument.Page page) -> page.grids().values().stream().mapToInt(Map::size).sum()).sum()).orElse(0);
        entry.addProperty("entries", entries);
        shops.add(shopId, entry);
        DocumentStore.atomic(temporary.resolve("reports/" + path + ".json"), JsonDocuments.write(report.json()));
        return entries;
    }

    private static String unique(String id, Set<String> ids) {
        String candidate = id;
        for (int suffix = 2; ids.contains(candidate); suffix++) candidate = id + "-" + suffix;
        ids.add(candidate);
        return candidate;
    }

    /** A version 6 or version 5 shop mapping. */
    static JsonObject convertShop(String shopId, String name, JsonObject source, ImportReport report, Map<Integer, String[]> limitKeys) {
        JsonObject shop = header(shopId);
        String title = string(source, "title").orElse(name);
        shop.addProperty("title", title);
        report.field("/title", Status.CONVERTED);
        permission(source, "permission").ifPresent((String node) -> shop.addProperty("permission", node));
        report.field("/permission", Status.CONVERTED);
        JsonArray modes = new JsonArray();
        for (String mode : List.of("buy", "sell", "trade")) {
            if (flag(source, mode + "-shop-enabled", true)) modes.add(mode);
            report.field("/" + mode + "-shop-enabled", Status.CONVERTED);
        }
        if (modes.isEmpty()) { modes.add("buy"); report.note("every mode was off; buy is enabled so the shop can load"); }
        shop.add("modes", modes);
        String preferred = string(source, "default-shop").map((String text) -> text.toLowerCase(Locale.ROOT)).orElse("buy");
        if (modes.contains(new JsonPrimitive(preferred))) shop.addProperty("defaultMode", preferred);
        report.field("/default-shop", Status.CONVERTED);
        shop.addProperty("match", "legacy");
        if (flag(source, "close-after-purchase", false)) shop.addProperty("closeAfterPurchase", true);
        report.field("/close-after-purchase", Status.CONVERTED);
        boolean confirm = flag(source, "confirmation-window-enabled", false) || flag(source, "confirmation-window", false);
        if (confirm) shop.addProperty("confirm", true);
        for (String key : List.of("confirmation-window-enabled", "confirmation-window")) if (source.has(key)) report.field("/" + key, Status.CONVERTED);
        boolean log = flag(source, "logging-enabled", false);
        if (source.has("logging-enabled")) report.field("/logging-enabled", Status.CONVERTED);
        if (source.has("command")) report.field("/command", Status.UNSUPPORTED_PRESERVED, "per-shop open commands are not created; use /animus shop open or a behavior with animus:open-shop");
        JsonArray pages = new JsonArray();
        JsonObject pageSection = source.has("pages") && source.get("pages").isJsonObject() ? source.getAsJsonObject("pages") : new JsonObject();
        TreeMap<Integer, Map.Entry<String, JsonElement>> ordered = new TreeMap<>();
        for (Map.Entry<String, JsonElement> page : pageSection.entrySet()) {
            int number = page.getKey().matches("page-[0-9]{1,4}") ? Integer.parseInt(page.getKey().substring(5)) : 10_000 + ordered.size();
            ordered.put(number, page);
        }
        Set<String> entryIds = new HashSet<>();
        for (Map.Entry<String, JsonElement> page : ordered.values()) {
            if (pages.size() >= ShopDocument.MAXIMUM_PAGES) { report.field("/pages/" + page.getKey(), Status.UNSUPPORTED_PRESERVED, "more than " + ShopDocument.MAXIMUM_PAGES + " pages"); continue; }
            if (!page.getValue().isJsonObject()) { report.field("/pages/" + page.getKey(), Status.UNSUPPORTED_PRESERVED, "page is not a mapping"); continue; }
            JsonObject converted = convertPage(shopId, "/pages/" + page.getKey(), page.getValue().getAsJsonObject(), pages.size(), report, entryIds, limitKeys);
            if (converted.has("logged")) { log = true; converted.remove("logged"); }
            pages.add(converted);
        }
        if (pages.isEmpty()) {
            JsonObject empty = new JsonObject();
            empty.addProperty("name", "Page 1");
            pages.add(empty);
            report.note("the shop has no pages; an empty page was added");
        }
        if (log) shop.addProperty("log", true);
        shop.add("pages", pages);
        JsonObject imported = new JsonObject();
        imported.addProperty("source", "dtlTraders");
        imported.addProperty("shopName", name);
        shop.add("imported", imported);
        return shop;
    }

    private static JsonObject convertPage(String shopId, String path, JsonObject source, int index, ImportReport report, Set<String> entryIds, Map<Integer, String[]> limitKeys) {
        JsonObject page = new JsonObject();
        int size = source.has("size") && source.get("size").isJsonPrimitive() ? source.get("size").getAsInt() : 27;
        if (size <= 0 || size % 9 != 0 || size > 54) { report.field(path + "/size", Status.CONVERTED_WITH_CHANGES, "size " + size + " is not 9 to 54 in steps of 9; dtlTraders and Animus use 27"); size = 27; }
        else report.field(path + "/size", Status.CONVERTED);
        page.addProperty("rows", size / 9);
        String name = string(source, "page-name").filter((String text) -> !text.isBlank()).orElse("Page " + (index + 1));
        page.addProperty("name", name);
        report.field(path + "/page-name", Status.CONVERTED);
        permission(source, "page-permission").ifPresent((String node) -> page.addProperty("permission", node));
        report.field(path + "/page-permission", Status.CONVERTED);
        boolean logged = false;
        for (String grid : List.of("buy", "sell", "trade")) {
            String key = grid + "-items";
            if (!source.has(key) || !source.get(key).isJsonObject()) continue;
            JsonObject entries = new JsonObject();
            for (Map.Entry<String, JsonElement> item : source.getAsJsonObject(key).entrySet()) {
                String itemPath = path + "/" + key + "/" + item.getKey();
                if (!item.getKey().matches("item-[0-9]{1,2}") || Integer.parseInt(item.getKey().substring(5)) >= size) {
                    report.field(itemPath, Status.UNSUPPORTED_PRESERVED, "slot is outside the page");
                    continue;
                }
                if (!item.getValue().isJsonObject()) { report.field(itemPath, Status.UNSUPPORTED_PRESERVED, "entry is not a mapping"); continue; }
                Optional<JsonObject> entry = convertEntry(shopId, itemPath, grid, item.getValue().getAsJsonObject(), report, entryIds, limitKeys);
                if (entry.isEmpty()) continue;
                if (entry.get().has("logged")) { logged = true; entry.get().remove("logged"); }
                entries.add(item.getKey().substring(5), entry.get());
            }
            if (!entries.isEmpty()) page.add(grid, entries);
        }
        if (logged) page.addProperty("logged", true);
        return page;
    }

    private static Optional<JsonObject> convertEntry(String shopId, String path, String grid, JsonObject source, ImportReport report, Set<String> entryIds, Map<Integer, String[]> limitKeys) {
        String type = string(source, "type").map((String text) -> text.toLowerCase(Locale.ROOT)).orElse("static");
        report.field(path + "/type", Status.CONVERTED);
        JsonObject entry = new JsonObject();
        int numeric = source.has("id") && source.get("id").isJsonPrimitive() ? source.get("id").getAsInt() : -1;
        String entryId = numeric >= 0 ? "dtl-" + numeric : "slot-" + grid + "-" + path.substring(path.lastIndexOf('-') + 1);
        for (int suffix = 2; entryIds.contains(entryId); suffix++) entryId = (numeric >= 0 ? "dtl-" + numeric : "slot-" + grid) + "-" + suffix;
        entryIds.add(entryId);
        entry.addProperty("id", entryId);
        report.field(path + "/id", Status.CONVERTED);
        boolean commands = source.has("commands") && source.get("commands").isJsonObject();
        String animusType = switch (type) {
            case "static" -> "static";
            case "trade" -> "trade";
            case "tradable" -> commands ? "command" : grid.equals("sell") ? "sell" : "buy";
            default -> null;
        };
        if (animusType == null) { report.field(path, Status.UNSUPPORTED_PRESERVED, "entry type " + type + " is not converted"); return Optional.empty(); }
        if (animusType.equals("trade") && !grid.equals("trade")) { report.field(path, Status.UNSUPPORTED_PRESERVED, "a trade entry outside the trade grid is not converted"); return Optional.empty(); }
        if (animusType.equals("command") && !grid.equals("buy")) { report.field(path, Status.UNSUPPORTED_PRESERVED, "a command entry outside the buy grid is not converted"); return Optional.empty(); }
        if (!animusType.equals("static") && !animusType.equals("trade") && grid.equals("trade")) animusType = "static";
        entry.addProperty("type", animusType);
        Optional<DtlItems.Converted> item = DtlItems.convert(source.get("item"), path + "/item", report);
        if (item.isEmpty()) return Optional.empty();
        if (item.get().amount() > 99) report.field(path + "/item/amount", Status.CONVERTED_WITH_CHANGES, "amounts above 99 show as 99; the price still covers 99");
        else report.field(path + "/item", item.get().review() ? Status.REQUIRES_REVIEW : Status.CONVERTED);
        entry.add("item", item.get().item().toJson());
        JsonObject display = new JsonObject();
        List<String> lore = new ArrayList<>();
        if (flag(source, "show-description", true) && source.has("description") && source.get("description").isJsonArray())
            for (JsonElement line : source.getAsJsonArray("description")) lore.add(DtlItems.text(line));
        report.field(path + "/description", Status.CONVERTED);
        report.field(path + "/show-description", Status.CONVERTED);
        if (flag(source, "custom-display-text-enabled", false)) report.field(path + "/custom-display-text", Status.UNSUPPORTED_PRESERVED, "custom display text uses dtlTraders message keys; Animus shows its own price and limit lines");
        string(source, "display-name").filter((String text) -> !text.isBlank()).ifPresent((String text) -> display.addProperty("name", DtlItems.text(new JsonPrimitive(text))));
        if (source.has("display-lore") && source.get("display-lore").isJsonArray())
            for (JsonElement line : source.getAsJsonArray("display-lore")) lore.add(DtlItems.text(line));
        for (String key : List.of("display-name", "display-lore")) if (source.has(key)) report.field(path + "/" + key, Status.CONVERTED);
        if (!lore.isEmpty()) { JsonArray lines = new JsonArray(); lore.stream().limit(32).forEach(lines::add); display.add("lore", lines); }
        if (source.has("show-price")) { display.addProperty("showPrice", flag(source, "show-price", true)); report.field(path + "/show-price", Status.CONVERTED); }
        if (source.has("show-trade-limit")) { display.addProperty("showLimit", flag(source, "show-trade-limit", true)); report.field(path + "/show-trade-limit", Status.CONVERTED); }
        for (String key : List.of("show-limit-time", "show-needed-items", "show-obtainable-items", "show-broadcast-message", "show-lore"))
            if (source.has(key)) report.field(path + "/" + key, Status.CONVERTED_WITH_CHANGES, "Animus always lists these lines when it shows prices or limits");
        if (!display.isEmpty()) entry.add("display", display);
        if (animusType.equals("static")) return Optional.of(entry);
        BigDecimal price = price(source, "trade-price").or(() -> price(source, "price")).orElse(BigDecimal.ZERO);
        if (!animusType.equals("trade") || price.signum() > 0) entry.add("price", new JsonPrimitive(price));
        report.field(path + "/trade-price", Status.CONVERTED);
        if (flag(source, "stack-sale-enabled", false)) entry.addProperty("allowStack", true);
        if (source.has("stack-sale-enabled")) report.field(path + "/stack-sale-enabled", Status.CONVERTED);
        if (animusType.equals("sell") && flag(source, "sell-all-when-not-enough-items", false)) entry.addProperty("sellPartial", true);
        if (source.has("sell-all-when-not-enough-items")) report.field(path + "/sell-all-when-not-enough-items", Status.CONVERTED);
        if (flag(source, "custom-input-enabled", false)) report.field(path + "/custom-input-enabled", Status.UNSUPPORTED_PRESERVED, "custom amounts typed by the player are not available; a right click buys the listed amount");
        long limit = number(source, "trade-limit").orElse(-1L);
        long reset = number(source, "limit-reset-seconds").or(() -> number(source, "trade-limit-seconds")).orElse(-1L);
        if (limit > 0) {
            JsonObject limits = new JsonObject();
            limits.addProperty("perPlayer", limit);
            if (reset > 0) {
                limits.addProperty("resetSeconds", reset);
                report.field(path + "/limit-reset-seconds", Status.CONVERTED_WITH_CHANGES, "each player's window starts at their first purchase instead of one shared server-uptime clock");
            }
            entry.add("limit", limits);
            if (numeric >= 0) limitKeys.put(numeric, new String[]{shopId, entryId});
        }
        report.field(path + "/trade-limit", Status.CONVERTED);
        permission(source, "permission").ifPresent((String node) -> entry.addProperty("permission", node));
        report.field(path + "/permission", Status.CONVERTED);
        string(source, "broadcast-message").filter((String text) -> !text.isBlank()).ifPresent((String text) -> entry.addProperty("broadcast", placeholders(text)));
        if (source.has("broadcast-message")) report.field(path + "/broadcast-message", Status.CONVERTED);
        if (flag(source, "close-after-purchase", false) || flag(source, "close-inventory", false)) entry.addProperty("closeAfterPurchase", true);
        for (String key : List.of("close-after-purchase", "close-inventory")) if (source.has(key)) report.field(path + "/" + key, Status.CONVERTED);
        if (flag(source, "drop-item-on-full-inventory", false)) entry.addProperty("dropWhenFull", true);
        if (source.has("drop-item-on-full-inventory")) report.field(path + "/drop-item-on-full-inventory", Status.CONVERTED);
        if (flag(source, "logging-enabled", false)) entry.addProperty("logged", true);
        if (source.has("logging-enabled")) report.field(path + "/logging-enabled", Status.CONVERTED_WITH_CHANGES, "Animus logs per shop; one logged entry turns on the shop log");
        if (animusType.equals("trade")) {
            JsonArray needs = goods(source.get("needed-items"), path + "/needed-items", report), gives = goods(source.get("obtainable-items"), path + "/obtainable-items", report);
            if (needs.isEmpty() || gives.isEmpty()) { report.field(path, Status.UNSUPPORTED_PRESERVED, "a trade needs both needed and obtainable items"); return Optional.empty(); }
            entry.add("needs", needs);
            entry.add("gives", gives);
        }
        JsonArray run = new JsonArray();
        for (String key : List.of("on-buy-sell-commands", "on-trade-commands")) {
            if (!source.has(key)) continue;
            if (source.get(key).isJsonArray()) for (JsonElement line : source.getAsJsonArray(key)) run.add(command(line.getAsString(), "console"));
            report.field(path + "/" + key, Status.CONVERTED);
        }
        if (commands) {
            String mode = string(source, "run-mode").orElse("BUY_AND_RUN");
            if (!mode.equalsIgnoreCase("BUY_AND_RUN")) report.field(path + "/run-mode", Status.REQUIRES_REVIEW, "run mode " + mode + " becomes run on purchase; tokens that run later are not created");
            else report.field(path + "/run-mode", Status.CONVERTED);
            for (Map.Entry<String, JsonElement> line : source.getAsJsonObject("commands").entrySet()) {
                if (!line.getValue().isJsonObject()) continue;
                JsonObject spec = line.getValue().getAsJsonObject();
                String executor = string(spec, "executor").orElse("CONSOLE").toUpperCase(Locale.ROOT);
                String text = string(spec, "command").orElse("");
                if (text.isBlank()) continue;
                if (executor.equals("OP")) report.field(path + "/commands/" + line.getKey() + "/executor", Status.CONVERTED_WITH_CHANGES, "OP commands run from the console");
                else report.field(path + "/commands/" + line.getKey(), Status.CONVERTED);
                run.add(command(text, executor.equals("PLAYER") ? "player" : "console"));
            }
        }
        if (!run.isEmpty()) {
            JsonArray bounded = new JsonArray();
            for (int index = 0; index < Math.min(run.size(), ShopDocument.MAXIMUM_COMMANDS); index++) bounded.add(run.get(index));
            if (run.size() > ShopDocument.MAXIMUM_COMMANDS) report.note(path + ": only the first " + ShopDocument.MAXIMUM_COMMANDS + " commands are kept");
            entry.add("commands", bounded);
        }
        discount(source, path, report, price).ifPresent((JsonObject discount) -> entry.add("discount", discount));
        return Optional.of(entry);
    }

    private static Optional<JsonObject> discount(JsonObject source, String path, ImportReport report, BigDecimal price) {
        boolean nested = source.has("Discount") && source.get("Discount").isJsonObject();
        boolean flat = Stream.of("discount-enabled", "discount-price", "discount-start", "discount-end", "discount-duration", "discount-percentage").anyMatch(source::has);
        if (!nested && !flat) return Optional.empty();
        JsonObject section = nested ? source.getAsJsonObject("Discount") : source;
        String prefix = section == source ? path : path + "/Discount";
        for (String key : section.keySet()) if (key.startsWith("discount-") || key.equals("duration") || key.startsWith("show-discount")) report.field(prefix + "/" + key, Status.CONVERTED);
        if (!flag(section, "discount-enabled", false)) return Optional.empty();
        Optional<BigDecimal> discounted = price(section, "discount-price");
        if (discounted.isEmpty() && section.has("discount-percentage")) {
            report.field(prefix + "/discount-percentage", Status.REQUIRES_REVIEW, "how dtlTraders applies the percentage is not confirmed; set discount.price by hand");
            return Optional.empty();
        }
        if (discounted.isEmpty() || discounted.get().compareTo(price) >= 0) return Optional.empty();
        JsonObject discount = new JsonObject();
        discount.add("price", new JsonPrimitive(discounted.get()));
        Optional<String> start = date(section, "discount-start", prefix, report), end = date(section, "discount-end", prefix, report);
        if (end.isEmpty() && start.isPresent()) {
            Optional<Long> seconds = number(section, "discount-duration").or(() -> number(section, "duration"));
            if (seconds.isPresent() && seconds.get() > 0) {
                end = Optional.of(java.time.Instant.parse(start.get()).plusSeconds(seconds.get()).toString());
                report.field(prefix + "/discount-duration", Status.REQUIRES_REVIEW, "the duration was read as seconds; check the end date");
            }
        }
        start.ifPresent((String text) -> discount.addProperty("start", text));
        end.ifPresent((String text) -> discount.addProperty("end", text));
        return Optional.of(discount);
    }

    private static Optional<String> date(JsonObject section, String key, String path, ImportReport report) {
        Optional<String> text = string(section, key);
        if (text.isEmpty() || text.get().isBlank()) return Optional.empty();
        try {
            String instant = LocalDateTime.parse(text.get(), DATE).atZone(ZoneId.systemDefault()).toInstant().toString();
            report.field(path + "/" + key, Status.CONVERTED_WITH_CHANGES, "read in this machine's time zone (" + ZoneId.systemDefault() + ")");
            return Optional.of(instant);
        } catch (DateTimeParseException invalid) {
            report.field(path + "/" + key, Status.UNSUPPORTED_PRESERVED, "not a dd/MM/yyyy HH:mm:ss date");
            return Optional.empty();
        }
    }

    private static JsonArray goods(JsonElement section, String path, ImportReport report) {
        JsonArray goods = new JsonArray();
        if (section == null || !section.isJsonObject()) return goods;
        for (Map.Entry<String, JsonElement> entry : section.getAsJsonObject().entrySet()) {
            String itemPath = path + "/" + entry.getKey();
            JsonElement item = entry.getValue().isJsonObject() && entry.getValue().getAsJsonObject().has("item") ? entry.getValue().getAsJsonObject().get("item") : entry.getValue();
            Optional<DtlItems.Converted> converted = DtlItems.convert(item, itemPath + "/item", report);
            if (converted.isEmpty() || goods.size() >= 27) continue;
            report.field(itemPath, converted.get().review() ? Status.REQUIRES_REVIEW : Status.CONVERTED);
            JsonObject good = new JsonObject();
            good.add("item", converted.get().item().toJson());
            good.addProperty("amount", converted.get().amount());
            goods.add(good);
        }
        return goods;
    }

    /** A version 3 trader's own stock. Its old sell list is what the NPC sells, so it becomes the buy grid. */
    static JsonObject convertLegacy(String shopId, String name, JsonObject trader, ImportReport report) {
        JsonObject shop = header(shopId);
        shop.addProperty("title", name);
        shop.addProperty("match", "legacy");
        JsonArray modes = new JsonArray();
        JsonObject buy = new JsonObject(), sell = new JsonObject();
        legacyGrid(trader.get("sell"), "/sell", buy, "buy", report);
        legacyGrid(trader.get("buy"), "/buy", sell, "sell", report);
        if (!buy.isEmpty()) modes.add("buy");
        if (!sell.isEmpty()) modes.add("sell");
        if (modes.isEmpty()) modes.add("buy");
        shop.add("modes", modes);
        for (String key : trader.keySet()) if (!Set.of("sell", "buy", "converted-to-v5", "guiName").contains(key))
            report.field("/" + key, Status.UNSUPPORTED_PRESERVED, "version 3 setting kept in the import originals");
        JsonObject page = new JsonObject();
        page.addProperty("name", name);
        int highest = 0;
        for (JsonObject grid : List.of(buy, sell)) for (String slot : grid.keySet()) highest = Math.max(highest, Integer.parseInt(slot));
        page.addProperty("rows", Math.clamp(highest / 9 + 1, 1, 6));
        if (!buy.isEmpty()) page.add("buy", buy);
        if (!sell.isEmpty()) page.add("sell", sell);
        JsonArray pages = new JsonArray();
        pages.add(page);
        shop.add("pages", pages);
        JsonObject imported = new JsonObject();
        imported.addProperty("source", "dtlTraders 3");
        shop.add("imported", imported);
        return shop;
    }

    private static void legacyGrid(JsonElement list, String path, JsonObject grid, String type, ImportReport report) {
        if (list == null || !list.isJsonArray()) return;
        int next = 0, index = -1;
        for (JsonElement element : list.getAsJsonArray()) {
            index++;
            String itemPath = path + "/" + index;
            String text = element.isJsonPrimitive() ? element.getAsString() : element.isJsonObject() && element.getAsJsonObject().size() == 1 ? element.getAsJsonObject().keySet().iterator().next() : null;
            if (text == null) { report.field(itemPath, Status.UNSUPPORTED_PRESERVED, "not an item string"); continue; }
            String[] tokens = text.trim().split("\\s+");
            String[] parts = tokens[0].split(":", 2);
            String id = "minecraft:" + parts[0].toLowerCase(Locale.ROOT);
            if (!art.arcane.animus.core.npc.NpcItem.validId(id)) { report.field(itemPath, Status.UNSUPPORTED_PRESERVED, "unknown item " + tokens[0]); continue; }
            boolean data = parts.length == 2 && !parts[1].equals("0");
            int amount = 1, slot = -1;
            BigDecimal price = null;
            boolean stackPrice = false;
            List<String> skipped = new ArrayList<>();
            for (int position = 1; position < tokens.length; position++) {
                String token = tokens[position];
                try {
                    if (token.startsWith("a:")) amount = Math.clamp(Integer.parseInt(token.substring(2).split(",")[0]), 1, 64);
                    else if (token.startsWith("p:")) price = new BigDecimal(token.substring(2));
                    else if (token.startsWith("s:")) slot = Integer.parseInt(token.substring(2));
                    else if (token.equals(".sp")) stackPrice = true;
                    else skipped.add(token);
                } catch (NumberFormatException invalid) { skipped.add(token); }
            }
            if (price == null) { report.field(itemPath, Status.UNSUPPORTED_PRESERVED, "no p: price; only money entries are converted"); continue; }
            if (slot < 0 || slot >= 54 || grid.has(Integer.toString(slot))) { while (grid.has(Integer.toString(next))) next++; slot = next; }
            if (slot >= 54) { report.field(itemPath, Status.UNSUPPORTED_PRESERVED, "more than 54 entries"); continue; }
            JsonObject entry = new JsonObject();
            entry.addProperty("id", "legacy-" + type + "-" + index);
            entry.addProperty("type", type);
            JsonObject item = new JsonObject();
            item.addProperty("id", id);
            item.addProperty("count", amount);
            entry.add("item", item);
            BigDecimal total = stackPrice ? price : price.multiply(BigDecimal.valueOf(amount));
            entry.add("price", new JsonPrimitive(total.setScale(Math.min(4, Math.max(0, total.scale())), RoundingMode.HALF_UP)));
            grid.add(Integer.toString(slot), entry);
            if (data) skipped.add("data value " + parts[1]);
            report.field(itemPath, skipped.isEmpty() ? Status.CONVERTED : Status.REQUIRES_REVIEW, skipped.isEmpty() ? null : "not converted: " + String.join(" ", skipped));
        }
    }

    /**
     * Reads {@code data.dat}: a version string, the next item id, per-player counts by item id, then the uptime
     * counter. Returns null and records why when any part does not read exactly to the end of the file.
     */
    static JsonObject limits(byte[] bytes, Map<Integer, String[]> keys, JsonObject status) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            String version = in.readUTF();
            int nextItem = in.readInt();
            int players = in.readInt();
            if (players < 0 || players > MAXIMUM_LIMIT_PLAYERS) throw new IOException("player count " + players + " is out of range");
            JsonArray counts = new JsonArray();
            int orphaned = 0, total = 0;
            for (int index = 0; index < players; index++) {
                UUID player = UUID.fromString(in.readUTF());
                int items = in.readInt();
                if (items < 0 || items > 100_000) throw new IOException("item count " + items + " is out of range");
                for (int item = 0; item < items; item++) {
                    int id = in.readInt(), used = in.readInt();
                    if (used <= 0) continue;
                    total++;
                    String[] target = keys.get(id);
                    if (target == null) { orphaned++; continue; }
                    JsonObject count = new JsonObject();
                    count.addProperty("shop", target[0]);
                    count.addProperty("entry", target[1]);
                    count.addProperty("player", player.toString());
                    count.addProperty("used", used);
                    counts.add(count);
                }
            }
            int timer = in.readInt();
            if (in.read() != -1) throw new IOException("data continues after the uptime counter");
            status.addProperty("status", "converted");
            status.addProperty("version", version);
            status.addProperty("nextItemId", nextItem);
            status.addProperty("players", players);
            status.addProperty("counts", total);
            status.addProperty("converted", counts.size());
            status.addProperty("orphaned", orphaned);
            status.addProperty("uptimeSeconds", timer);
            status.addProperty("note", "Counts keep their totals; each player's reset window starts at their next purchase. Counts for entries without a limit, or for items no shop contains, are left out.");
            JsonObject limits = new JsonObject();
            limits.add("counts", counts);
            return limits;
        } catch (IOException | IllegalArgumentException unreadable) {
            status.addProperty("status", "preserved-unread");
            status.addProperty("reason", unreadable instanceof EOFException ? "the file ends early" : unreadable.getMessage());
            return null;
        }
    }

    private static JsonObject header(String id) {
        JsonObject shop = new JsonObject();
        shop.addProperty("schemaVersion", ShopDocument.SCHEMA_VERSION);
        shop.addProperty("revision", 1);
        shop.addProperty("id", id);
        return shop;
    }

    private static JsonObject command(String text, String source) {
        JsonObject command = new JsonObject();
        String line = placeholders(text.startsWith("/") ? text.substring(1) : text);
        command.addProperty("command", line.length() > 1024 ? line.substring(0, 1024) : line);
        command.addProperty("as", source);
        return command;
    }

    private static String placeholders(String text) {
        return text.replace("%player%", "{player}").replace("%item%", "{item}").replace("%price%", "{price}").replace("%amount%", "{amount}");
    }

    private static Optional<String> permission(JsonObject source, String key) {
        Optional<String> text = string(source, key).map(String::trim);
        if (text.isEmpty() || text.get().isEmpty() || text.get().equalsIgnoreCase("none") || text.get().equalsIgnoreCase("null")) return Optional.empty();
        return text.get().matches("[A-Za-z0-9_-]+(\\.[A-Za-z0-9_*-]+)*") && text.get().length() <= 128 ? text : Optional.empty();
    }

    private static Optional<String> string(JsonObject source, String key) {
        JsonElement value = source.get(key);
        return value != null && value.isJsonPrimitive() ? Optional.of(value.getAsString()) : Optional.empty();
    }

    private static boolean flag(JsonObject source, String key, boolean fallback) {
        JsonElement value = source.get(key);
        if (value == null || !value.isJsonPrimitive()) return fallback;
        return value.getAsJsonPrimitive().isBoolean() ? value.getAsBoolean() : Boolean.parseBoolean(value.getAsString());
    }

    private static Optional<Long> number(JsonObject source, String key) {
        try { return string(source, key).map((String text) -> new BigDecimal(text).longValue()); }
        catch (NumberFormatException invalid) { return Optional.empty(); }
    }

    private static Optional<BigDecimal> price(JsonObject source, String key) {
        try {
            return string(source, key).map(BigDecimal::new).filter((BigDecimal value) -> value.signum() >= 0)
                    .map((BigDecimal value) -> value.setScale(Math.min(4, Math.max(0, value.stripTrailingZeros().scale())), RoundingMode.HALF_UP).stripTrailingZeros())
                    .map((BigDecimal value) -> value.scale() < 0 ? value.setScale(0) : value);
        } catch (NumberFormatException | ArithmeticException invalid) { return Optional.empty(); }
    }

    private static Map<String, Path> sources(Path folder) throws IOException {
        Map<String, Path> sources = new LinkedHashMap<>();
        for (String name : List.of("guis.yml", "config.yml", "messages.yml", "data.dat")) {
            Path file = folder.resolve(name);
            if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) sources.put(name, checked(file, name.equals("data.dat") ? MAXIMUM_DATA : MAXIMUM_FILE));
        }
        Path shops = folder.resolve("shops");
        if (Files.isDirectory(shops, LinkOption.NOFOLLOW_LINKS)) {
            try (Stream<Path> folders = Files.list(shops)) {
                for (Path shop : folders.sorted(Comparator.naturalOrder()).toList()) {
                    if (!Files.isDirectory(shop, LinkOption.NOFOLLOW_LINKS)) continue;
                    Path file = shop.resolve(shop.getFileName() + ".yml");
                    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                    if (sources.size() > MAXIMUM_SHOPS) throw new IOException("More than " + MAXIMUM_SHOPS + " shop files");
                    String relative = "shops/" + shop.getFileName() + "/" + file.getFileName();
                    if (!relative.matches("shops/[A-Za-z0-9 _.-]+/[A-Za-z0-9 _.-]+\\.yml")) throw new IOException("Unexpected shop file name: " + relative);
                    sources.put(relative, checked(file, MAXIMUM_FILE));
                }
            }
        }
        if (sources.keySet().stream().noneMatch((String name) -> name.equals("guis.yml") || name.startsWith("shops/"))) throw new IOException("No shops/<name>/<name>.yml or guis.yml in " + folder);
        return sources;
    }

    private static Path checked(Path file, long maximum) throws IOException {
        if (Files.isSymbolicLink(file)) throw new IOException("Symlinks are not read: " + file);
        if (Files.size(file) > maximum) throw new IOException(file + " exceeds " + maximum + " bytes");
        return file;
    }

    private static String hash(Path file) throws IOException {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IOException(impossible); }
    }

    private static void delete(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> paths = Files.walk(root)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); }
    }
}
