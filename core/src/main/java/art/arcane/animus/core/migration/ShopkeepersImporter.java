package art.arcane.animus.core.migration;

import art.arcane.animus.core.migration.ImportReport.Status;
import art.arcane.animus.core.npc.DefinitionBatch;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.shop.ShopBinding;
import art.arcane.animus.core.shop.ShopDocument;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
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
 * Converts a Shopkeepers {@code save.yml} into Animus shops. Every shopkeeper becomes the shop
 * {@code shopkeepers:shop-<id>} with one trade entry per offer. Admin shops have no stock limit. Player shops become
 * owned shops whose stock is their containers: selling shops take the currency items Shopkeepers would ask for,
 * buying shops pay them out, trading shops swap their items. Citizens shopkeepers bind to the Animus NPC converted from
 * their Citizens NPC; mob shopkeepers become new NPCs of that mob at the shop; sign and virtual shopkeepers get a shop
 * and no NPC. An offer whose items cannot be written exactly shows but does not trade, with its data kept in
 * {@code imported}.
 * <p>
 * {@link #convert} is a pure function of the save, the optional {@code config.yml} and the optional Citizens
 * {@code saves.yml}; animus-web's browser converter reproduces its files byte for byte. A preview stages them under
 * {@code imports/shopkeepers/<id>/}. Nothing is installed until {@link #apply}, which also binds every Citizens
 * shopkeeper whose NPC is installed and returns the rest as pending.
 */
public final class ShopkeepersImporter {
    public static final int VERSION = 1;
    public static final String NAMESPACE = "shopkeepers", IMPORTS = "imports/shopkeepers";
    static final String SHOP_PATH = "shops/shopkeepers/shop-(?:0|[1-9][0-9]{0,9})\\.json", NPC_PATH = "npcs/shopkeepers/shopkeeper-(?:0|[1-9][0-9]{0,9})\\.json";
    private static final int MAXIMUM_SHOPKEEPERS = 10_000, PER_PAGE = 45, MAXIMUM_STACK = 64;
    private static final long MAXIMUM_SOURCE = 16L * 1024 * 1024;
    private static final Set<String> HANDLED = Set.of("type", "uniqueId", "name", "world", "x", "y", "z", "yaw", "open", "object", "tradePerm", "recipes", "offers",
            "owner uuid", "owner", "containers", "chestx", "chesty", "chestz", "snapshots");
    private static final Set<String> OWNERSHIP = Set.of("notifyOnTrades", "forHire", "hirecost", "members", "expiration", "expirationNotifications", "ownedSince");
    private static final Set<String> PLAYER_TYPES = Set.of("sell", "buy", "trade", "book");

    /** A conversion: its import id, the staged files by relative path in path order, and the summary {@code report.json} holds. */
    public record Conversion(String id, Map<String, String> files, JsonObject summary) {
        public Conversion { files = java.util.Collections.unmodifiableMap(new TreeMap<>(files)); summary = summary.deepCopy(); }
        @Override public JsonObject summary() { return summary.deepCopy(); }
    }

    public record Preview(String id, Path directory, JsonObject summary) {
        public Preview { summary = summary.deepCopy(); }
        @Override public JsonObject summary() { return summary.deepCopy(); }
        public int shops() { return summary.get("shops").getAsInt(); }
        public int npcs() { return summary.get("npcs").getAsInt(); }
        public int issues() { return summary.getAsJsonArray("issues").size(); }
        public JsonObject offers() { return summary.getAsJsonObject("offers"); }
        public JsonArray bindings() { return summary.getAsJsonArray("bindings"); }
        /** Aggregate counts only, safe to print for a private save. */
        public List<String> aggregate() {
            JsonObject offers = offers(), items = summary.getAsJsonObject("items"), links = summary.getAsJsonObject("links");
            return List.of("Shops: " + shops() + " " + summary.get("types") + "; objects " + summary.get("objects") + "; new mob NPCs: " + npcs(),
                    "Offers: " + offers.get("total") + " total, " + offers.get("trade") + " trade, " + offers.get("static") + " shown only, " + offers.get("skipped") + " kept in source.yml",
                    "Items: " + items.get("total") + " total, " + items.get("withComponents") + " with components, " + items.get("preservedOnly") + " preserved only",
                    "Citizens links: " + links.get("inCitizensSave") + " in the Citizens save, " + links.get("notInCitizensSave") + " not in it, " + links.get("unchecked") + " unchecked",
                    "Issues: " + issues());
        }
    }

    /** What apply installed: its transaction, the Citizens NPCs it bound, and the bindings still waiting for their NPC. */
    public record Applied(String transaction, int bound, JsonArray pending, List<String> problems) {
        public Applied { pending = pending.deepCopy(); problems = List.copyOf(problems); }
        @Override public JsonArray pending() { return pending.deepCopy(); }
        public int waiting() { return pending.size(); }
    }

    private record Currency(Optional<ShopkeepersItems.Item> low, Optional<ShopkeepersItems.Item> high, int highValue, int highMinimum, boolean log, JsonObject summary) {}

    /** Stages a preview; the same sources give the same id and reuse the staged preview. */
    public Preview preview(Path save, Optional<Path> config, Optional<Path> citizens, DocumentStore store) throws IOException {
        String text = read(save);
        Optional<String> configText = config.isPresent() ? Optional.of(read(config.get())) : Optional.empty();
        Optional<String> citizensText = citizens.isPresent() ? Optional.of(read(citizens.get())) : Optional.empty();
        String id = id(text, configText, citizensText);
        Path imports = store.resolve(IMPORTS);
        Files.createDirectories(imports);
        Path staged = imports.resolve(id);
        if (Files.exists(staged)) return read(staged, id);
        Conversion conversion = convert(text, configText, citizensText);
        Path temporary = Files.createTempDirectory(imports, ".preview-");
        boolean published = false;
        try {
            for (Map.Entry<String, String> file : conversion.files().entrySet()) DocumentStore.atomic(temporary.resolve(file.getKey()), file.getValue());
            Files.move(temporary, staged, StandardCopyOption.ATOMIC_MOVE);
            published = true;
            return new Preview(id, staged, conversion.summary());
        } finally {
            if (!published) delete(temporary);
        }
    }

    public Preview read(Path directory, String id) throws IOException {
        try {
            JsonObject summary = JsonDocuments.object(JsonDocuments.read(directory.resolve("report.json")));
            if (summary.get("importerVersion").getAsInt() > VERSION || !summary.get("importId").getAsString().equals(id)) throw new IOException("Unsupported or mismatched Shopkeepers import report");
            return new Preview(id, directory, summary);
        } catch (RuntimeException invalid) { throw new IOException("Invalid Shopkeepers import report: " + directory.resolve("report.json"), invalid); }
    }

    static String id(String save, Optional<String> config, Optional<String> citizens) {
        return JsonDocuments.hash("animus-shopkeepers-v" + VERSION + "\n" + JsonDocuments.hash(save) + "\n" + JsonDocuments.hash(config.orElse(null))
                + "\n" + JsonDocuments.hash(citizens.orElse(null)) + "\n");
    }

    /** The staged files for these sources. Throws when the save is not a Shopkeepers save at all. */
    public static Conversion convert(String save, Optional<String> config, Optional<String> citizens) throws IOException {
        JsonObject root = CitizensImporter.parseYaml(save);
        String id = id(save, config, citizens);
        Map<String, String> files = new TreeMap<>();
        files.put("source.yml", save);
        config.ifPresent((String text) -> files.put("config.yml", text));
        JsonArray issues = new JsonArray();
        Currency currency = currency(config.isPresent() ? Optional.of(CitizensImporter.parseYaml(config.get())) : Optional.empty(), issues);
        Map<String, String> citizensIds = new LinkedHashMap<>();
        Map<String, List<String>> citizensUuids = new HashMap<>();
        if (citizens.isPresent()) {
            JsonObject saves = CitizensImporter.parseYaml(citizens.get());
            JsonObject npcs = saves.has("npc") && saves.get("npc").isJsonObject() ? saves.getAsJsonObject("npc") : new JsonObject();
            for (Map.Entry<String, JsonElement> npc : npcs.entrySet()) {
                if (!npc.getKey().matches("0|[1-9][0-9]{0,9}") || Long.parseLong(npc.getKey()) > Integer.MAX_VALUE || !npc.getValue().isJsonObject()) continue;
                JsonElement uuid = npc.getValue().getAsJsonObject().get("uuid");
                String value = uuid != null && uuid.isJsonPrimitive() ? uuid.getAsString()
                        : UUID.nameUUIDFromBytes(("animus:citizens:" + npc.getKey()).getBytes(StandardCharsets.UTF_8)).toString();
                citizensIds.put(npc.getKey(), value);
                citizensUuids.computeIfAbsent(value, (String ignored) -> new ArrayList<>()).add(npc.getKey());
            }
        }
        TreeMap<String, Integer> types = new TreeMap<>(), objects = new TreeMap<>(), dataVersions = new TreeMap<>();
        int[] offers = new int[4], items = new int[3], links = new int[3];
        JsonArray bindings = new JsonArray();
        JsonObject shopMap = new JsonObject(), manifest = new JsonObject();
        int shops = 0, npcCount = 0;
        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            String key = entry.getKey();
            if (key.equals("data-version")) continue;
            if (!key.matches("0|[1-9][0-9]{0,9}") || Long.parseLong(key) > Integer.MAX_VALUE) { issues.add(key + ": not a shopkeeper id; kept in source.yml"); continue; }
            if (!entry.getValue().isJsonObject()) { issues.add(key + ": not a shopkeeper mapping; kept in source.yml"); continue; }
            if (shops >= MAXIMUM_SHOPKEEPERS) throw new IOException("More than " + MAXIMUM_SHOPKEEPERS + " shopkeepers");
            int number = Integer.parseInt(key);
            JsonObject record = entry.getValue().getAsJsonObject();
            ImportReport report = new ImportReport();
            String type = string(record, "type").orElse("").toLowerCase(Locale.ROOT);
            if (type.equals("player")) { type = "sell"; report.field("/type", Status.CONVERTED_WITH_CHANGES, "the old player type is a selling shop"); }
            else report.field("/type", Status.CONVERTED);
            if (!type.equals("admin") && !PLAYER_TYPES.contains(type)) { issues.add(key + ": shop type " + (type.isEmpty() ? "(none)" : type) + " is not converted; kept in source.yml"); continue; }
            JsonObject object = record.has("object") && record.get("object").isJsonObject() ? record.getAsJsonObject("object") : new JsonObject();
            String objectType = string(object, "type").orElse("virtual").toLowerCase(Locale.ROOT);
            types.merge(type, 1, Integer::sum);
            objects.merge(objectType, 1, Integer::sum);
            String shopId = NAMESPACE + ":shop-" + number;
            String shopPath = "shops/shopkeepers/shop-" + number + ".json";
            JsonObject shop = new JsonObject();
            shop.addProperty("schemaVersion", ShopDocument.SCHEMA_VERSION);
            shop.addProperty("revision", 1);
            shop.addProperty("id", shopId);
            String name = string(record, "name").map(String::strip).filter((String text) -> !text.isEmpty()).orElse("");
            report.field("/name", Status.CONVERTED);
            Optional<String> ownerName = string(record, "owner").filter((String text) -> !text.isBlank());
            String title = !name.isEmpty() ? name : ownerName.map((String owner) -> owner + "'s shop").orElse("Shopkeeper " + number);
            shop.addProperty("title", title.length() > 256 ? title.substring(0, 256) : title);
            if (type.equals("admin") && record.has("tradePerm")) {
                Optional<String> node = string(record, "tradePerm").map(String::trim);
                if (node.isPresent() && node.get().length() <= 128 && node.get().matches("[A-Za-z0-9_-]+(\\.[A-Za-z0-9_*-]+)*")) {
                    shop.addProperty("permission", node.get());
                    report.field("/tradePerm", Status.CONVERTED);
                } else report.field("/tradePerm", Status.REQUIRES_REVIEW, "not a permission node Animus accepts; the shop is open to everyone; set permission by hand");
            }
            JsonArray modes = new JsonArray();
            modes.add("trade");
            shop.add("modes", modes);
            shop.addProperty("defaultMode", "trade");
            shop.addProperty("match", "strict");
            if (currency.log()) shop.addProperty("log", true);
            boolean open = !record.has("open") || !record.get("open").isJsonPrimitive() || !record.get("open").getAsJsonPrimitive().isBoolean() || record.get("open").getAsBoolean();
            if (record.has("open")) report.field("/open", open ? Status.CONVERTED : Status.CONVERTED_WITH_CHANGES, open ? null : "the shop was closed; its offers show but do not trade until you change them to trade entries");
            boolean player = PLAYER_TYPES.contains(type);
            boolean stocked = false;
            if (player) {
                Optional<UUID> owner = string(record, "owner uuid").flatMap(ShopkeepersImporter::uuid);
                JsonArray containers = containers(record, report);
                if (owner.isPresent()) {
                    JsonObject owned = new JsonObject();
                    owned.addProperty("uuid", owner.get().toString());
                    ownerName.ifPresent((String text) -> owned.addProperty("name", text));
                    shop.add("owner", owned);
                    report.field("/owner uuid", Status.CONVERTED);
                    if (record.has("owner")) report.field("/owner", Status.CONVERTED);
                } else report.field("/owner uuid", Status.REQUIRES_REVIEW, "no valid owner; the shop's offers show but do not trade");
                if (owner.isPresent() && !containers.isEmpty()) { shop.add("containers", containers); stocked = true; }
                else if (owner.isPresent()) report.note("the shop has no container; its offers show but do not trade until one is added");
            }
            List<JsonObject> entries = new ArrayList<>();
            String list = type.equals("admin") ? "recipes" : "offers";
            JsonObject source = record.has(list) && record.get(list).isJsonObject() ? record.getAsJsonObject(list) : new JsonObject();
            if (record.has(list) && !record.get(list).isJsonObject() && !record.get(list).isJsonNull()) report.field("/" + list, Status.UNSUPPORTED_PRESERVED, "not a mapping of offers; kept in source.yml");
            boolean tradable = open && (!player || stocked);
            Set<String> entryIds = new HashSet<>();
            int position = 0;
            for (Map.Entry<String, JsonElement> offer : source.entrySet()) {
                offers[0]++;
                position++;
                String path = "/" + list + "/" + pointer(offer.getKey());
                if (entries.size() >= PER_PAGE * ShopDocument.MAXIMUM_PAGES) { offers[3]++; report.field(path, Status.UNSUPPORTED_PRESERVED, "more than " + PER_PAGE * ShopDocument.MAXIMUM_PAGES + " offers"); continue; }
                String entryId = offer.getKey().matches("[0-9]{1,9}") ? "offer-" + offer.getKey() : "offer-n" + position;
                for (int suffix = 2; entryIds.contains(entryId); suffix++) entryId = "offer-n" + position + "-" + suffix;
                entryIds.add(entryId);
                Optional<JsonObject> converted = offer(type, entryId, offer.getValue(), path, currency, tradable, report, items, dataVersions);
                if (converted.isEmpty()) { offers[3]++; continue; }
                if (converted.get().get("type").getAsString().equals("trade")) offers[1]++; else offers[2]++;
                entries.add(converted.get());
            }
            JsonArray pages = new JsonArray();
            for (int start = 0; start < Math.max(1, entries.size()); start += PER_PAGE) {
                List<JsonObject> chunk = entries.subList(Math.min(start, entries.size()), Math.min(entries.size(), start + PER_PAGE));
                JsonObject page = new JsonObject();
                page.addProperty("name", "Page " + (pages.size() + 1));
                page.addProperty("rows", Math.min(6, (chunk.size() + 8) / 9 + 1));
                if (!chunk.isEmpty()) {
                    JsonObject grid = new JsonObject();
                    for (int slot = 0; slot < chunk.size(); slot++) grid.add(Integer.toString(slot), chunk.get(slot));
                    page.add("trade", grid);
                }
                pages.add(page);
            }
            shop.add("pages", pages);
            JsonObject imported = new JsonObject();
            imported.addProperty("source", "Shopkeepers");
            imported.addProperty("shopkeeper", number);
            string(record, "uniqueId").ifPresent((String text) -> imported.addProperty("uniqueId", text));
            imported.addProperty("type", type);
            JsonObject location = new JsonObject();
            for (String axis : List.of("world", "x", "y", "z", "yaw")) {
                if (record.has(axis)) { location.add(axis, record.get(axis).deepCopy()); report.field("/" + axis, Status.CONVERTED); }
            }
            imported.add("location", location);
            imported.add("object", object.deepCopy());
            if (!open) imported.addProperty("open", false);
            for (Map.Entry<String, JsonElement> field : record.entrySet()) {
                if (!OWNERSHIP.contains(field.getKey())) continue;
                imported.add(field.getKey(), field.getValue().deepCopy());
                report.field("/" + field.getKey(), Status.UNSUPPORTED_PRESERVED, "kept in the shop's imported section; Animus does not use it");
            }
            shop.add("imported", imported);
            report.field("/uniqueId", Status.CONVERTED);
            if (record.has("snapshots")) report.field("/snapshots", Status.UNSUPPORTED_PRESERVED, "shopkeeper snapshots are kept in source.yml only");
            for (String field : record.keySet())
                if (!HANDLED.contains(field) && !OWNERSHIP.contains(field)) report.field("/" + pointer(field), Status.UNSUPPORTED_PRESERVED, "kept in source.yml");
            report.field("/object/type", Status.CONVERTED);
            Optional<JsonObject> npc = Optional.empty();
            switch (objectType) {
                case "citizen", "npc" -> {
                    JsonObject binding = binding(number, shopId, object, citizens.isPresent(), citizensIds, citizensUuids, report);
                    String state = binding.get("status").getAsString();
                    links[state.equals("in-citizens-save") ? 0 : state.equals("unchecked") ? 2 : 1]++;
                    bindings.add(binding);
                    for (String field : object.keySet()) if (!Set.of("type", "npcId").contains(field))
                        report.field("/object/" + pointer(field), Status.UNSUPPORTED_PRESERVED, "Citizens keeps this NPC's data; kept in the shop's imported section");
                }
                case "sign", "hanging-sign" -> {
                    for (String field : object.keySet()) if (!field.equals("type")) report.field("/object/" + pointer(field), Status.UNSUPPORTED_PRESERVED, "kept in the shop's imported section");
                    report.note("a sign shopkeeper gets no NPC; open the shop with /animus shop open " + shopId + " or bind it to an NPC");
                }
                case "virtual" -> report.note("a virtual shopkeeper has no object; open the shop with /animus shop open " + shopId);
                default -> {
                    npc = mob(id, number, shopId, objectType, record, object, report, issues);
                    if (npc.isPresent()) npcCount++;
                }
            }
            String text = JsonDocuments.write(shop);
            files.put(shopPath, text);
            manifest.addProperty(shopPath, JsonDocuments.hash(text));
            JsonObject mapped = new JsonObject();
            mapped.addProperty("shopkeeper", number);
            mapped.addProperty("document", shopPath);
            mapped.addProperty("entries", entries.size());
            if (npc.isPresent()) {
                String npcPath = "npcs/shopkeepers/shopkeeper-" + number + ".json";
                String npcText = JsonDocuments.write(npc.get());
                files.put(npcPath, npcText);
                manifest.addProperty(npcPath, JsonDocuments.hash(npcText));
                mapped.addProperty("npc", npcPath);
            }
            shopMap.add(shopId, mapped);
            JsonObject reportJson = report.json();
            reportJson.addProperty("shopkeeper", number);
            reportJson.addProperty("shop", shopId);
            files.put("reports/" + number + ".json", JsonDocuments.write(reportJson));
            shops++;
        }
        JsonObject summary = new JsonObject();
        summary.addProperty("schemaVersion", 1);
        summary.addProperty("importerVersion", VERSION);
        summary.addProperty("importId", id);
        summary.addProperty("sourceSha256", JsonDocuments.hash(save));
        config.ifPresent((String text) -> summary.addProperty("configSha256", JsonDocuments.hash(text)));
        citizens.ifPresent((String text) -> summary.addProperty("citizensSha256", JsonDocuments.hash(text)));
        summary.addProperty("notice", "Data conversion only. Shops install under shops/shopkeepers/ and stay closed on their NPCs until the shops review is accepted. Try each shop in game before relying on it.");
        summary.add("dataVersion", root.has("data-version") && root.get("data-version").isJsonPrimitive() ? root.get("data-version").deepCopy() : JsonNull.INSTANCE);
        summary.addProperty("shops", shops);
        summary.addProperty("npcs", npcCount);
        summary.add("types", counts(types));
        summary.add("objects", counts(objects));
        JsonObject offerCounts = new JsonObject();
        offerCounts.addProperty("total", offers[0]);
        offerCounts.addProperty("trade", offers[1]);
        offerCounts.addProperty("static", offers[2]);
        offerCounts.addProperty("skipped", offers[3]);
        summary.add("offers", offerCounts);
        JsonObject itemCounts = new JsonObject();
        itemCounts.addProperty("total", items[0]);
        itemCounts.addProperty("withComponents", items[1]);
        itemCounts.addProperty("preservedOnly", items[2]);
        itemCounts.add("dataVersions", counts(dataVersions));
        summary.add("items", itemCounts);
        summary.add("currency", currency.summary());
        JsonObject linkCounts = new JsonObject();
        linkCounts.addProperty("inCitizensSave", links[0]);
        linkCounts.addProperty("notInCitizensSave", links[1]);
        linkCounts.addProperty("unchecked", links[2]);
        summary.add("links", linkCounts);
        summary.add("bindings", bindings);
        summary.add("shopMap", shopMap);
        summary.add("issues", issues);
        summary.add("documents", manifest);
        files.put("report.json", JsonDocuments.write(summary));
        return new Conversion(id, files, summary);
    }

    private static Optional<JsonObject> offer(String type, String entryId, JsonElement value, String path, Currency currency, boolean tradable, ImportReport report,
                                              int[] items, TreeMap<String, Integer> dataVersions) {
        if (value == null || !value.isJsonObject()) { report.field(path, Status.UNSUPPORTED_PRESERVED, "not an offer mapping; kept in source.yml"); return Optional.empty(); }
        JsonObject offer = value.getAsJsonObject();
        if (type.equals("book")) {
            report.field(path, Status.UNSUPPORTED_PRESERVED, "a book offer copies a written book from the shop's chest by title; Animus trades exact items only, so it is kept in source.yml");
            return Optional.empty();
        }
        List<JsonObject> needs = new ArrayList<>(), gives = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        boolean exact = true;
        ShopkeepersItems.Item icon;
        if (type.equals("admin") || type.equals("trade")) {
            Optional<ShopkeepersItems.Item> result = item(offer.get("resultItem"), items, dataVersions);
            Optional<ShopkeepersItems.Item> first = item(offer.get("item1"), items, dataVersions);
            Optional<ShopkeepersItems.Item> second = item(offer.get("item2"), items, dataVersions);
            if (result.isEmpty() || first.isEmpty()) { report.field(path, Status.UNSUPPORTED_PRESERVED, "the offer has no result item or no first item; kept in source.yml"); return Optional.empty(); }
            icon = result.get();
            for (ShopkeepersItems.Item need : second.isPresent() ? List.of(first.get(), second.get()) : List.of(first.get())) needs.add(goods(need.item(), need.amount()));
            gives.add(goods(icon.item(), icon.amount()));
            for (ShopkeepersItems.Item part : second.isPresent() ? List.of(result.get(), first.get(), second.get()) : List.of(result.get(), first.get())) {
                exact &= part.exact();
                notes.addAll(part.notes());
            }
        } else {
            Optional<ShopkeepersItems.Item> sold = item(offer.get("item"), items, dataVersions);
            Optional<Integer> price = ShopkeepersItems.integer(offer.get("price"));
            if (sold.isEmpty() || price.isEmpty() || price.get() < 1) { report.field(path, Status.UNSUPPORTED_PRESERVED, "the offer has no item or no price of at least 1; kept in source.yml"); return Optional.empty(); }
            if (currency.low().isEmpty()) { report.field(path, Status.UNSUPPORTED_PRESERVED, "the currency item in config.yml is not an item; kept in source.yml"); return Optional.empty(); }
            icon = sold.get();
            exact = sold.get().exact() && currency.low().get().exact() && currency.high().map(ShopkeepersItems.Item::exact).orElse(true);
            notes.addAll(sold.get().notes());
            if (type.equals("sell")) {
                int remaining = price.get();
                if (currency.high().isPresent() && price.get() > currency.highMinimum()) {
                    int high = Math.min(price.get() / currency.highValue(), MAXIMUM_STACK);
                    if (high > 0) { needs.add(goods(currency.high().get().item(), high)); remaining -= high * currency.highValue(); }
                }
                if (remaining > MAXIMUM_STACK) { report.field(path, Status.UNSUPPORTED_PRESERVED, "price " + price.get() + " needs more than one stack of each currency item, which Shopkeepers also skips"); return Optional.empty(); }
                if (remaining > 0) needs.add(goods(currency.low().get().item(), remaining));
                gives.add(goods(icon.item(), icon.amount()));
            } else {
                if (price.get() > MAXIMUM_STACK) { report.field(path, Status.UNSUPPORTED_PRESERVED, "price " + price.get() + " is more than one stack of the currency item, which Shopkeepers also skips"); return Optional.empty(); }
                needs.add(goods(icon.item(), icon.amount()));
                gives.add(goods(currency.low().get().item(), price.get()));
            }
        }
        JsonObject entry = new JsonObject();
        entry.addProperty("id", entryId);
        boolean trade = exact && tradable;
        entry.addProperty("type", trade ? "trade" : "static");
        entry.add("item", icon.item().toJson());
        JsonArray needList = new JsonArray(), giveList = new JsonArray();
        needs.forEach(needList::add);
        gives.forEach(giveList::add);
        if (trade) {
            entry.add("needs", needList);
            entry.add("gives", giveList);
        } else {
            JsonObject kept = new JsonObject();
            kept.addProperty("source", "Shopkeepers");
            kept.add("needs", needList);
            kept.add("gives", giveList);
            entry.add("imported", kept);
        }
        if (!exact) report.field(path, Status.REQUIRES_REVIEW, "item data Animus cannot write exactly is kept in imported, so the offer shows but does not trade: " + String.join("; ", notes));
        else if (!tradable) report.field(path, Status.CONVERTED_WITH_CHANGES, "converted as a display entry; the shop cannot trade yet");
        else report.field(path, notes.isEmpty() ? Status.CONVERTED : Status.CONVERTED_WITH_CHANGES, notes.isEmpty() ? null : String.join("; ", notes));
        return Optional.of(entry);
    }

    private static Optional<ShopkeepersItems.Item> item(JsonElement value, int[] items, TreeMap<String, Integer> dataVersions) {
        Optional<ShopkeepersItems.Item> item = ShopkeepersItems.convert(value);
        if (item.isEmpty()) return item;
        items[0]++;
        if (item.get().components()) items[1]++;
        if (!item.get().exact()) items[2]++;
        item.get().dataVersion().ifPresent((Integer version) -> dataVersions.merge(Integer.toString(version), 1, Integer::sum));
        return item;
    }

    private static JsonObject goods(NpcItem item, int amount) {
        JsonObject goods = new JsonObject();
        goods.add("item", new NpcItem(item.id(), 1, item.components(), Optional.empty()).toJson());
        goods.addProperty("amount", amount);
        return goods;
    }

    private static JsonArray containers(JsonObject record, ImportReport report) {
        JsonArray containers = new JsonArray();
        Set<String> seen = new HashSet<>();
        if (record.has("containers") && record.get("containers").isJsonArray()) {
            boolean split = false;
            for (JsonElement value : record.getAsJsonArray("containers")) {
                if (!value.isJsonObject() || containers.size() >= ShopDocument.MAXIMUM_CONTAINERS) continue;
                JsonObject container = value.getAsJsonObject();
                Optional<String> world = string(container, "world").filter((String text) -> !text.isBlank());
                Optional<Integer> x = ShopkeepersItems.integer(container.get("x")), y = ShopkeepersItems.integer(container.get("y")), z = ShopkeepersItems.integer(container.get("z"));
                if (world.isEmpty() || x.isEmpty() || y.isEmpty() || z.isEmpty()) continue;
                if (string(container, "type").map((String text) -> !text.equals("stock-and-earnings")).orElse(false)) split = true;
                add(containers, seen, world.get(), x.get(), y.get(), z.get());
            }
            report.field("/containers", split ? Status.CONVERTED_WITH_CHANGES : Status.CONVERTED, split ? "stock and earnings containers are one pool in Animus: trades take from and pay into all of them" : null);
        } else if (record.has("chestx")) {
            Optional<String> world = string(record, "world").filter((String text) -> !text.isBlank());
            Optional<Integer> x = ShopkeepersItems.integer(record.get("chestx")), y = ShopkeepersItems.integer(record.get("chesty")), z = ShopkeepersItems.integer(record.get("chestz"));
            if (world.isPresent() && x.isPresent() && y.isPresent() && z.isPresent()) add(containers, seen, world.get(), x.get(), y.get(), z.get());
            for (String axis : List.of("chestx", "chesty", "chestz")) if (record.has(axis)) report.field("/" + axis, Status.CONVERTED);
        }
        return containers;
    }

    private static void add(JsonArray containers, Set<String> seen, String world, int x, int y, int z) {
        if (!within(x, 30_000_000) || !within(z, 30_000_000) || !within(y, 2048) || !seen.add(world + "\n" + x + "\n" + y + "\n" + z)) return;
        JsonObject container = new JsonObject();
        container.addProperty("world", world);
        container.addProperty("x", x);
        container.addProperty("y", y);
        container.addProperty("z", z);
        containers.add(container);
    }

    private static JsonObject binding(int number, String shopId, JsonObject object, boolean checked, Map<String, String> ids, Map<String, List<String>> uuids, ImportReport report) {
        JsonObject binding = new JsonObject();
        binding.addProperty("shopkeeper", number);
        binding.addProperty("shop", shopId);
        JsonElement npcId = object.get("npcId");
        Optional<UUID> uuid = npcId != null && npcId.isJsonPrimitive() ? uuid(npcId.getAsString()) : Optional.empty();
        Optional<Integer> numeric = npcId != null && npcId.isJsonPrimitive() && npcId.getAsJsonPrimitive().isNumber() ? ShopkeepersItems.integer(npcId) : Optional.empty();
        String key = null;
        if (uuid.isPresent()) {
            binding.addProperty("citizensUuid", uuid.get().toString());
            List<String> matches = uuids.getOrDefault(uuid.get().toString(), List.of());
            if (matches.size() == 1) key = matches.getFirst();
        } else if (numeric.isPresent() && numeric.get() >= 0) {
            binding.addProperty("citizensId", numeric.get());
            if (ids.containsKey(Integer.toString(numeric.get()))) key = Integer.toString(numeric.get());
        } else {
            binding.addProperty("status", "no-npc");
            report.field("/object/npcId", Status.UNSUPPORTED_PRESERVED, "the shopkeeper names no Citizens NPC; the shop opens only by command");
            return binding;
        }
        report.field("/object/npcId", Status.CONVERTED);
        if (!checked) { binding.addProperty("status", "unchecked"); return binding; }
        if (key == null) {
            binding.addProperty("status", "not-in-citizens-save");
            report.note("the Citizens NPC is not in the Citizens save given; the binding waits for an NPC with that identity");
            return binding;
        }
        binding.addProperty("status", "in-citizens-save");
        if (!binding.has("citizensId")) binding.addProperty("citizensId", Integer.parseInt(key));
        if (!binding.has("citizensUuid")) binding.addProperty("citizensUuid", ids.get(key));
        binding.addProperty("npc", "citizens-" + key);
        return binding;
    }

    private static Optional<JsonObject> mob(String importId, int number, String shopId, String objectType, JsonObject record, JsonObject object, ImportReport report, JsonArray issues) {
        String entity = "minecraft:" + objectType.replace('-', '_');
        if (!entity.matches("[a-z0-9_]+:[a-z0-9_/]+")) { issues.add(number + ": object type " + objectType + " is not an entity; the shop has no NPC"); return Optional.empty(); }
        Optional<String> world = string(record, "world").filter((String text) -> !text.isBlank());
        Optional<Integer> x = ShopkeepersItems.integer(record.get("x")), y = ShopkeepersItems.integer(record.get("y")), z = ShopkeepersItems.integer(record.get("z"));
        if (world.isEmpty() || x.isEmpty() || y.isEmpty() || z.isEmpty() || !within(x.get(), 30_000_000) || !within(y.get(), 30_000_000) || !within(z.get(), 30_000_000)) {
            issues.add(number + ": the shopkeeper has no usable location; no NPC was created");
            return Optional.empty();
        }
        JsonObject definition = new JsonObject();
        definition.addProperty("schemaVersion", 1);
        definition.addProperty("revision", 1);
        Optional<UUID> unique = string(record, "uniqueId").flatMap(ShopkeepersImporter::uuid);
        definition.addProperty("uuid", unique.orElse(UUID.nameUUIDFromBytes(("animus:shopkeepers:" + number).getBytes(StandardCharsets.UTF_8))).toString());
        definition.addProperty("id", "shopkeeper-" + number);
        definition.addProperty("group", NAMESPACE);
        String name = string(record, "name").map(String::strip).filter((String text) -> !text.isEmpty() && text.length() <= 256 && text.codePoints().noneMatch(Character::isISOControl)).orElse("Shopkeeper " + number);
        definition.addProperty("name", name);
        definition.addProperty("type", entity);
        definition.addProperty("spawned", false);
        JsonObject position = new JsonObject();
        position.addProperty("world", world.get());
        position.addProperty("x", x.get() + 0.5);
        position.addProperty("y", y.get());
        position.addProperty("z", z.get() + 0.5);
        JsonElement yaw = record.get("yaw");
        boolean finite = yaw != null && yaw.isJsonPrimitive() && yaw.getAsJsonPrimitive().isNumber() && Double.isFinite(yaw.getAsDouble()) && Math.abs(yaw.getAsDouble()) <= 30_000_000;
        position.add("yaw", finite ? yaw.deepCopy() : new JsonPrimitive(0));
        position.addProperty("pitch", 0);
        definition.add("position", position);
        JsonObject migration = new JsonObject();
        migration.addProperty("importId", importId);
        migration.addProperty("source", "Shopkeepers");
        migration.addProperty("shopkeeper", number);
        migration.addProperty("originalSpawned", true);
        definition.add("migration", migration);
        JsonObject modules = new JsonObject();
        modules.add(ShopBinding.MODULE, module(number, shopId));
        JsonObject review = object.deepCopy();
        review.remove("type");
        if (!review.isEmpty()) {
            modules.add("shopkeepers:review", review);
            for (String field : review.keySet()) report.field("/object/" + pointer(field), Status.REQUIRES_REVIEW, "mob settings are kept in the NPC's shopkeepers:review section");
        }
        definition.add("modules", modules);
        report.note("a new " + entity + " NPC stands at the shopkeeper's place, despawned until the spawn review is accepted");
        return Optional.of(definition);
    }

    /** The {@code animus:shop} section an imported shopkeeper binding writes; it opens nothing until reviewed. */
    static JsonObject module(int number, String shopId) {
        JsonObject module = new JsonObject();
        module.addProperty("shop", shopId);
        module.addProperty("openOn", "right");
        module.addProperty("reviewAccepted", false);
        JsonObject imported = new JsonObject();
        imported.addProperty("source", "Shopkeepers");
        imported.addProperty("shopkeeper", number);
        module.add("imported", imported);
        return module;
    }

    private static Currency currency(Optional<JsonObject> config, JsonArray issues) {
        JsonObject settings = config.orElse(new JsonObject());
        Optional<ShopkeepersItems.Item> low = settings.has("currency-item") ? ShopkeepersItems.convert(settings.get("currency-item")) : ShopkeepersItems.convert(new JsonPrimitive("minecraft:emerald"));
        if (low.isEmpty()) issues.add("config.yml: currency-item is not an item; selling and buying offers are kept in source.yml");
        Optional<ShopkeepersItems.Item> high = settings.has("high-currency-item") ? ShopkeepersItems.convert(settings.get("high-currency-item")) : ShopkeepersItems.convert(new JsonPrimitive("minecraft:emerald_block"));
        int value = settings.has("high-currency-value") ? ShopkeepersItems.integer(settings.get("high-currency-value")).orElse(9) : 9;
        int minimum = settings.has("high-currency-min-cost") ? ShopkeepersItems.integer(settings.get("high-currency-min-cost")).orElse(20) : 20;
        if (value <= 0) high = Optional.empty();
        String log = settings.has("trade-log-storage") ? ShopkeepersItems.scalar(settings.get("trade-log-storage")).orElse("DISABLED").toUpperCase(Locale.ROOT) : "DISABLED";
        JsonObject summary = new JsonObject();
        summary.addProperty("config", config.isPresent());
        summary.add("item", low.<JsonElement>map((ShopkeepersItems.Item item) -> item.item().toJson()).orElse(JsonNull.INSTANCE));
        summary.add("highItem", high.<JsonElement>map((ShopkeepersItems.Item item) -> item.item().toJson()).orElse(JsonNull.INSTANCE));
        summary.addProperty("highValue", value);
        summary.addProperty("highMinimumCost", minimum);
        summary.addProperty("tradeLog", log.toLowerCase(Locale.ROOT));
        return new Currency(low, high, value, minimum, !log.equals("DISABLED"), summary);
    }

    /**
     * Installs the staged shops and new NPCs and binds each Citizens shopkeeper whose NPC is installed, in one
     * transaction. A shop or NPC file that already exists with other content stops the whole apply.
     */
    public Applied apply(DocumentStore store, String id, Collection<NpcDefinition> installed) throws IOException {
        if (!id.matches("[a-f0-9]{64}")) throw new IOException("Invalid import ID");
        Path staged = store.resolve(IMPORTS + "/" + id);
        Preview preview = read(staged, id);
        String receiptPath = IMPORTS + "/" + id + "/receipt.json";
        if (store.read(receiptPath) != null) throw new IOException("This Shopkeepers import is already applied");
        List<DocumentStore.Change> changes = new ArrayList<>();
        Set<UUID> uuids = new HashSet<>();
        Set<String> ids = new HashSet<>();
        for (NpcDefinition definition : installed) { uuids.add(definition.uuid()); ids.add(definition.id()); }
        for (Map.Entry<String, JsonElement> document : preview.summary().getAsJsonObject("documents").entrySet()) {
            String relative = document.getKey();
            if (!relative.matches(SHOP_PATH) && !relative.matches(NPC_PATH)) throw new IOException("Invalid bundle path: " + relative);
            String content = JsonDocuments.read(staged.resolve(relative));
            if (!JsonDocuments.hash(content).equals(document.getValue().getAsString())) throw new IOException("Preview changed: " + relative + "; make a new preview");
            String before = store.read(relative);
            if (relative.startsWith("shops/")) {
                String shopId = NAMESPACE + ":" + relative.substring("shops/shopkeepers/".length(), relative.length() - 5);
                ShopDocument.Result checked = ShopDocument.parse(shopId, content);
                if (!checked.problems().isEmpty()) throw new IOException(relative + ": " + String.join("; ", checked.problems()));
                if (before != null && before.equals(content)) continue;
                if (before != null) throw new IOException("Shop collision: " + relative + " already exists with other content");
            } else {
                NpcDefinition definition = NpcDefinition.parse(content);
                if (!definition.path().equals(relative) || before != null || !uuids.add(definition.uuid()) || !ids.add(definition.id())) throw new IOException("NPC collision: " + relative);
                if (definition.spawned() || !definition.json().getAsJsonObject("migration").get("importId").getAsString().equals(id)) throw new IOException("Imported NPC must stay dormant and name this import: " + relative);
            }
            changes.add(new DocumentStore.Change(relative, "missing", content));
        }
        JsonArray pending = new JsonArray();
        List<String> problems = new ArrayList<>();
        List<NpcDefinition> edited = new ArrayList<>();
        Map<String, String> expected = new HashMap<>();
        int bound = bindings(store, preview.bindings(), installed, edited, expected, pending, problems);
        JsonObject receipt = new JsonObject();
        receipt.addProperty("importId", id);
        receipt.addProperty("shops", preview.shops());
        receipt.addProperty("npcs", preview.npcs());
        receipt.addProperty("bound", bound);
        changes.add(new DocumentStore.Change(receiptPath, "missing", JsonDocuments.write(receipt)));
        return new Applied(DefinitionBatch.commit(store, edited, expected, changes), bound, pending, problems);
    }

    /** Binds pending Citizens shopkeepers to NPCs installed since; empty when none of them is installed yet. */
    public static Optional<Applied> bind(DocumentStore store, JsonArray pending, Collection<NpcDefinition> installed) throws IOException {
        List<NpcDefinition> edited = new ArrayList<>();
        Map<String, String> expected = new HashMap<>();
        JsonArray still = new JsonArray();
        List<String> problems = new ArrayList<>();
        int bound = bindings(store, pending, installed, edited, expected, still, problems);
        if (edited.isEmpty()) return Optional.empty();
        return Optional.of(new Applied(DefinitionBatch.commit(store, edited, expected, List.of()), bound, still, problems));
    }

    private static int bindings(DocumentStore store, JsonArray requested, Collection<NpcDefinition> installed, List<NpcDefinition> changes, Map<String, String> expected,
                                JsonArray pending, List<String> problems) throws IOException {
        Map<String, NpcDefinition> byUuid = new HashMap<>();
        Map<Integer, NpcDefinition> byCitizensId = new HashMap<>();
        for (NpcDefinition definition : installed) {
            byUuid.put(definition.uuid().toString(), definition);
            JsonObject json = definition.json();
            if (json.has("migration") && json.get("migration").isJsonObject() && json.getAsJsonObject("migration").has("citizensId")) {
                Optional<Integer> citizensId = ShopkeepersItems.integer(json.getAsJsonObject("migration").get("citizensId"));
                citizensId.ifPresent((Integer value) -> byCitizensId.putIfAbsent(value, definition));
            }
        }
        Set<String> edited = new HashSet<>();
        int bound = 0;
        for (JsonElement value : requested) {
            JsonObject binding = value.getAsJsonObject();
            if (binding.has("status") && binding.get("status").getAsString().equals("no-npc")) continue;
            NpcDefinition target = binding.has("citizensUuid") ? byUuid.get(binding.get("citizensUuid").getAsString()) : null;
            if (target == null && binding.has("citizensId")) target = byCitizensId.get(binding.get("citizensId").getAsInt());
            if (target == null) { pending.add(binding.deepCopy()); continue; }
            JsonObject json = target.json();
            JsonObject modules = json.has("modules") && json.get("modules").isJsonObject() ? json.getAsJsonObject("modules") : new JsonObject();
            if (modules.has(ShopBinding.MODULE) || !edited.add(target.path())) {
                problems.add(target.id() + " already opens a shop; shopkeeper " + binding.get("shopkeeper").getAsInt() + " (" + binding.get("shop").getAsString() + ") was not bound to it");
                continue;
            }
            if (target.revision() == Long.MAX_VALUE) { problems.add(target.id() + ": revision exhausted"); continue; }
            modules.add(ShopBinding.MODULE, module(binding.get("shopkeeper").getAsInt(), binding.get("shop").getAsString()));
            json.add("modules", modules);
            json.addProperty("revision", target.revision() + 1);
            changes.add(NpcDefinition.parse(JsonDocuments.write(json)));
            expected.put(target.path(), store.hash(target.path()));
            bound++;
        }
        return bound;
    }

    private static JsonObject counts(TreeMap<String, Integer> counts) {
        JsonObject json = new JsonObject();
        counts.forEach(json::addProperty);
        return json;
    }

    private static Optional<UUID> uuid(String text) {
        try {
            UUID uuid = UUID.fromString(text);
            return uuid.toString().equals(text) ? Optional.of(uuid) : Optional.empty();
        } catch (IllegalArgumentException invalid) { return Optional.empty(); }
    }

    private static Optional<String> string(JsonObject source, String key) {
        JsonElement value = source.get(key);
        return value != null && value.isJsonPrimitive() ? Optional.of(value.getAsString()) : Optional.empty();
    }

    private static boolean within(int value, int bound) { return value >= -bound && value <= bound; }

    private static String pointer(String key) { return key.replace("~", "~0").replace("/", "~1"); }

    private static String read(Path file) throws IOException {
        if (Files.isSymbolicLink(file)) throw new IOException("Symlinks are not read: " + file);
        if (Files.size(file) > MAXIMUM_SOURCE) throw new IOException(file + " exceeds " + MAXIMUM_SOURCE + " bytes");
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static void delete(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> paths = Files.walk(root)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); }
    }
}
