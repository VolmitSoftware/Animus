package art.arcane.animus.core.migration;

import static art.arcane.animus.core.migration.CitizensBehaviors.*;

import art.arcane.animus.core.storage.JsonDocuments;
import art.arcane.animus.core.shop.ShopDocument;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.util.*;

/**
 * Citizens' own shops ({@code /npc shop}, saved in {@code shops.yml}) as Animus shops under {@code shops/citizens/}.
 * Each shop item becomes one entry: money for items is a buy entry, items for money a sell entry, items for items a
 * trade (with any money as its price), and money for commands a command entry. Experience results and command
 * actions become the entry's commands. What a shop entry cannot hold keeps the item on show as a static entry.
 */
final class CitizensShops {
    static final String FOLDER = "shops/citizens/";
    static final String PATH = "shops/citizens/[a-z0-9_-]{1,64}\\.json";
    private static final Map<String, Integer> ROWS = Map.of("CHEST_1X9", 1, "CHEST_2X9", 2, "CHEST_3X9", 3, "CHEST_4X9", 4);

    /**
     * @param documents shop files by path
     * @param global shop ids by Citizens global shop name
     * @param npc shop ids by the NPC UUID Citizens keys its NPC shops with
     */
    record Converted(Map<String, String> documents, Map<String, String> global, Map<String, String> npc, List<String> warnings, int entries, int tradable) {
        static final Converted NONE = new Converted(Map.of(), Map.of(), Map.of(), List.of(), 0, 0);
        Converted {
            documents = Map.copyOf(documents); global = Map.copyOf(global); npc = Map.copyOf(npc); warnings = List.copyOf(warnings);
        }
    }

    private final List<String> warnings = new ArrayList<>();
    private final Set<String> used = new HashSet<>();
    private int entries, tradable;

    private CitizensShops() {}

    /** {@code idMap} maps Citizens numeric ids to NPC UUIDs, so NPC shops are named after their NPC. */
    static Converted convert(JsonObject root, JsonObject idMap) {
        CitizensShops shops = new CitizensShops();
        Map<String, String> documents = new TreeMap<>(), global = new TreeMap<>(), npc = new TreeMap<>();
        Map<String, String> numbers = new HashMap<>();
        for (Map.Entry<String, JsonElement> id : idMap.entrySet()) numbers.put(id.getValue().getAsString(), id.getKey());
        JsonObject named = object(root.get("global"));
        if (named != null) for (Map.Entry<String, JsonElement> shop : named.entrySet()) {
            String id = shops.id(shop.getKey());
            shops.shop(id, shop.getKey(), object(shop.getValue()), "/global/" + pointer(shop.getKey())).ifPresent((String content) -> {
                documents.put(FOLDER + id.substring(id.indexOf(':') + 1) + ".json", content);
                global.put(shop.getKey(), id);
            });
        }
        JsonObject owned = object(root.get("npc"));
        if (owned != null) for (Map.Entry<String, JsonElement> shop : owned.entrySet()) {
            String number = numbers.get(shop.getKey());
            if (number == null) { shops.warnings.add("/npc/" + pointer(shop.getKey()) + ": the shop of an NPC this save does not have; not converted"); continue; }
            String id = shops.id("npc-" + number);
            shops.shop(id, "NPC " + number, object(shop.getValue()), "/npc/" + pointer(shop.getKey())).ifPresent((String content) -> {
                documents.put(FOLDER + id.substring(id.indexOf(':') + 1) + ".json", content);
                npc.put(shop.getKey(), id);
            });
        }
        return new Converted(documents, global, npc, shops.warnings, shops.entries, shops.tradable);
    }

    /**
     * The {@code animus:shop} module of an NPC whose shop trait opens a shop on right click: the named global shop, or
     * the NPC's own shop when no global shop has that name.
     */
    static Optional<JsonObject> module(JsonElement trait, String uuid, Converted shops, List<String> warnings) {
        JsonObject shop = object(trait);
        if (shop == null) return Optional.empty();
        String name = string(shop.get("rightClickShop"), "");
        JsonObject storage = object(shop.get("storage"));
        if (storage != null && !bool(storage.get("unlimited"), true))
            warnings.add("/traits/shop/storage: the NPC's own money and item storage is not kept; the shop trades without limited stock");
        if (name.isEmpty()) return Optional.empty();
        String id = shops.global().getOrDefault(name, shops.npc().get(uuid));
        if (id == null) { warnings.add("/traits/shop/rightClickShop: no shop named " + name + " converted; the NPC opens no shop"); return Optional.empty(); }
        JsonObject module = new JsonObject();
        module.addProperty("shop", id);
        module.addProperty("openOn", "right");
        return Optional.of(module);
    }

    private String id(String name) {
        String slug = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-").replaceAll("^-+|-+$", "");
        if (slug.isEmpty()) slug = "shop";
        if (slug.length() > 60) slug = slug.substring(0, 60);
        String candidate = slug;
        for (int suffix = 2; !used.add(candidate); suffix++) candidate = slug + "-" + suffix;
        return NAMESPACE + ":" + candidate;
    }

    private Optional<String> shop(String id, String name, JsonObject source, String at) {
        if (source == null) { warnings.add(at + ": not a shop; not converted"); return Optional.empty(); }
        String type = string(source.get("type"), "DEFAULT").toUpperCase(Locale.ROOT);
        int rows = ROWS.getOrDefault(type, 5);
        int size = rows * 9;
        Set<Integer> controls = rows == 1 ? Set.of(7, 8) : Set.of(size - 6, size - 4);
        List<Map.Entry<Integer, JsonObject>> ordered = new ArrayList<>();
        for (Map.Entry<String, JsonElement> page : entries(source.get("pages"))) {
            JsonObject data = object(page.getValue());
            if (data != null) ordered.add(Map.entry(Integer.parseInt(page.getKey()), data));
        }
        ordered.sort(Map.Entry.comparingByKey());
        JsonArray pages = new JsonArray();
        Set<String> modes = new LinkedHashSet<>();
        for (Map.Entry<Integer, JsonObject> page : ordered) {
            String pageAt = at + "/pages/" + page.getKey();
            JsonObject converted = new JsonObject();
            converted.addProperty("name", string(page.getValue().get("title"), "Page " + (pages.size() + 1)));
            converted.addProperty("rows", rows);
            JsonObject items = object(page.getValue().get("items"));
            if (items != null) for (Map.Entry<String, JsonElement> slot : items.entrySet()) {
                if (!slot.getKey().matches("[0-9]{1,2}") || Integer.parseInt(slot.getKey()) >= size) { warnings.add(pageAt + "/items/" + slot.getKey() + ": outside the window; not converted"); continue; }
                int index = Integer.parseInt(slot.getKey());
                if (controls.contains(index)) { warnings.add(pageAt + "/items/" + slot.getKey() + ": a page button icon; the Animus window draws its own"); continue; }
                Optional<Map.Entry<String, JsonObject>> entry = entry(object(slot.getValue()), "p" + (pages.size() + 1) + "-s" + index, pageAt + "/items/" + slot.getKey());
                if (entry.isEmpty()) continue;
                String grid = entry.get().getKey();
                if (!converted.has(grid)) converted.add(grid, new JsonObject());
                converted.getAsJsonObject(grid).add(slot.getKey(), entry.get().getValue());
                modes.add(grid);
            }
            pages.add(converted);
        }
        if (pages.isEmpty()) { warnings.add(at + ": a shop with no pages; not converted"); return Optional.empty(); }
        JsonObject document = new JsonObject();
        document.addProperty("schemaVersion", 1);
        document.addProperty("revision", 1);
        document.addProperty("id", id);
        String title = string(source.get("title"), "");
        document.addProperty("title", title.isEmpty() ? name : title);
        String permission = string(source.get("viewPermission"), "");
        if (!permission.isEmpty()) document.addProperty("permission", permission);
        document.addProperty("style", type.equals("TRADER") ? "native" : "chest");
        JsonArray listed = new JsonArray();
        for (String mode : List.of("trade", "buy", "sell")) if (modes.contains(mode)) listed.add(mode);
        if (listed.isEmpty()) listed.add("buy");
        document.add("modes", listed);
        document.add("pages", pages);
        JsonObject imported = new JsonObject();
        imported.addProperty("source", "citizens");
        imported.addProperty("name", name);
        imported.addProperty("type", type);
        document.add("imported", imported);
        return Optional.of(JsonDocuments.write(document));
    }

    /** The grid and entry for one shop item, or empty when it has nothing to show. */
    private Optional<Map.Entry<String, JsonObject>> entry(JsonObject source, String id, String at) {
        if (source == null) return Optional.empty();
        entries++;
        Actions cost = new Actions(source.get("cost"), at + "/cost", true), result = new Actions(source.get("result"), at + "/result", false);
        Optional<CitizensStacks.Stack> display = CitizensStacks.read(source.get("display"));
        List<String> blockers = new ArrayList<>();
        blockers.addAll(cost.blockers);
        blockers.addAll(result.blockers);
        JsonObject entry = new JsonObject();
        entry.addProperty("id", id);
        String grid;
        String type;
        if (blockers.isEmpty() && result.money.signum() > 0 && cost.items.size() == 1 && cost.money.signum() == 0 && result.items.isEmpty()) {
            type = "sell"; grid = "sell";
            entry.add("item", cost.items.getFirst().counted());
            entry.addProperty("price", CitizensStacks.money(result.money.doubleValue()));
        } else if (blockers.isEmpty() && result.money.signum() == 0 && !cost.items.isEmpty() && !result.items.isEmpty()) {
            type = "trade"; grid = "trade";
            entry.add("item", display.map(CitizensStacks.Stack::counted).orElse(result.items.getFirst().counted()));
            entry.add("needs", goods(cost.items));
            entry.add("gives", goods(result.items));
            if (cost.money.signum() > 0) entry.addProperty("price", CitizensStacks.money(cost.money.doubleValue()));
        } else if (blockers.isEmpty() && result.money.signum() == 0 && cost.items.isEmpty() && result.items.size() == 1) {
            type = "buy"; grid = "buy";
            entry.add("item", result.items.getFirst().counted());
            entry.addProperty("price", CitizensStacks.money(cost.money.doubleValue()));
        } else if (blockers.isEmpty() && result.money.signum() == 0 && cost.items.isEmpty() && result.items.isEmpty() && !result.commands.isEmpty()) {
            type = "command"; grid = "buy";
            entry.addProperty("price", CitizensStacks.money(cost.money.doubleValue()));
        } else {
            type = "static"; grid = "buy";
            if (blockers.isEmpty()) blockers.add("costs and results of this shape (" + cost.describe() + " for " + result.describe() + ") are not a buy, sell, trade or command offer");
        }
        if (!entry.has("item")) {
            Optional<CitizensStacks.Stack> icon = display.isPresent() ? display : result.items.isEmpty() ? (cost.items.isEmpty() ? Optional.empty() : Optional.of(cost.items.getFirst())) : Optional.of(result.items.getFirst());
            if (icon.isEmpty()) { warnings.add(at + ": no display item; not converted"); return Optional.empty(); }
            entry.add("item", icon.get().counted());
        }
        if (cost.levels > 0 && type.equals("sell")) {
            type = "static";
            blockers.add("experience costs on a sale are not supported");
        }
        entry.addProperty("type", type);
        if (cost.levels > 0 && !type.equals("static")) entry.addProperty("levels", cost.levels);
        if (type.equals("static")) {
            warnings.add(at + ": shown but not sold: " + String.join("; ", blockers));
            JsonObject kept = new JsonObject();
            kept.add("citizens", source.deepCopy());
            if (JsonDocuments.write(kept).length() <= 16_384) entry.add("imported", kept);
            return Optional.of(Map.entry(grid, entry));
        }
        tradable++;
        List<JsonObject> commands = new ArrayList<>(cost.commands);
        commands.addAll(result.commands);
        if (commands.size() > 16) { warnings.add(at + ": only the first 16 commands run"); commands = commands.subList(0, 16); }
        if (!commands.isEmpty()) {
            JsonArray array = new JsonArray();
            commands.forEach(array::add);
            entry.add("commands", array);
        }
        if (cost.permission.isPresent()) entry.addProperty("permission", cost.permission.get());
        int times = whole(source.get("timesPurchasable")), global = whole(source.get("globalTimesPurchasable"));
        int per = type.equals("buy") || type.equals("sell") ? entry.getAsJsonObject("item").get("count").getAsInt() : 1;
        if (times > 0 || global > 0) {
            JsonObject limit = new JsonObject();
            if (times > 0) limit.addProperty("perPlayer", (long) times * per);
            if (global > 0) limit.addProperty("global", (long) global * per);
            entry.add("limit", limit);
            JsonObject purchases = object(source.get("purchases"));
            if ((purchases != null && !purchases.isEmpty()) || whole(source.get("npurchases")) > 0)
                warnings.add(at + ": purchases already counted toward the limit start again from zero");
        }
        if (bool(source.get("maxRepeatsOnShiftClick"), false) && (type.equals("buy") || type.equals("sell"))) entry.addProperty("allowStack", true);
        else if (bool(source.get("maxRepeatsOnShiftClick"), false)) warnings.add(at + "/maxRepeatsOnShiftClick: a shift click buys one trade at a time");
        for (String field : List.of("isClickableExpression", "isVisibleExpression"))
            if (!string(source.get(field), "").isEmpty()) warnings.add(at + "/" + field + ": Citizens expressions are not converted; the entry shows to everyone");
        for (String field : List.of("resultMessage", "costMessage", "alreadyPurchasedMessage", "clickToConfirmMessage"))
            if (!string(source.get(field), "").isEmpty()) warnings.add(at + "/" + field + ": the shop's own messages replace this message");
        return Optional.of(Map.entry(grid, entry));
    }

    private static JsonArray goods(List<CitizensStacks.Stack> stacks) {
        JsonArray goods = new JsonArray();
        for (CitizensStacks.Stack stack : stacks.subList(0, Math.min(27, stacks.size()))) {
            JsonObject entry = new JsonObject();
            entry.add("item", stack.item());
            entry.addProperty("amount", Math.min(stack.amount(), 6400));
            goods.add(entry);
        }
        return goods;
    }

    private static int whole(JsonElement value) { return number(value) ? (int) Math.max(0, Math.min(1_000_000, Math.floor(value.getAsDouble()))) : 0; }

    /** One side of a shop item: what it costs or what it gives. */
    private static final class Actions {
        final List<CitizensStacks.Stack> items = new ArrayList<>();
        BigDecimal money = BigDecimal.ZERO;
        int levels;
        final List<JsonObject> commands = new ArrayList<>();
        Optional<String> permission = Optional.empty();
        final List<String> blockers = new ArrayList<>();

        Actions(JsonElement list, String at, boolean cost) {
            for (Map.Entry<String, JsonElement> entry : entries(list)) {
                JsonObject action = object(entry.getValue());
                if (action == null) continue;
                String path = at + "/" + entry.getKey();
                switch (string(action.get("type"), "")) {
                    case "items" -> {
                        for (Map.Entry<String, JsonElement> item : entries(action.get("items"))) {
                            Optional<CitizensStacks.Stack> stack = CitizensStacks.read(item.getValue());
                            if (stack.isEmpty()) continue;
                            if (stack.get().inexact().isPresent()) blockers.add("an item cannot be matched exactly: " + stack.get().inexact().get());
                            items.add(stack.get());
                        }
                        if (!entries(action.get("metaFilter")).isEmpty()) blockers.add("item filters by component path are not supported");
                    }
                    case "money" -> {
                        if (number(action.get("money")) && action.get("money").getAsDouble() > 0)
                            money = money.add(BigDecimal.valueOf(action.get("money").getAsDouble()));
                    }
                    case "experience" -> {
                        int levels = number(action.get("exp")) ? (int) Math.floor(action.get("exp").getAsDouble()) : 0;
                        if (levels <= 0) continue;
                        if (cost) this.levels = (int) Math.min(ShopDocument.MAXIMUM_LEVELS, (long) this.levels + levels);
                        else commands.add(command("xp add {player} " + levels + " levels", "console"));
                    }
                    case "command" -> {
                        boolean server = bool(action.get("server"), false), op = bool(action.get("op"), false);
                        for (Map.Entry<String, JsonElement> line : entries(action.get("commands"))) {
                            if (!line.getValue().isJsonPrimitive()) continue;
                            String text = line.getValue().getAsString().replace("<p>", "{player}").replace("<player>", "{player}");
                            if (text.startsWith("/")) text = text.substring(1);
                            if (text.isEmpty() || text.length() > 1024) { blockers.add("a command is empty or longer than 1024 characters"); continue; }
                            commands.add(command(text, server || op ? "console" : "player"));
                        }
                    }
                    case "permissions" -> {
                        List<String> nodes = new ArrayList<>();
                        for (Map.Entry<String, JsonElement> node : entries(action.get("permissions"))) if (node.getValue().isJsonPrimitive()) nodes.add(node.getValue().getAsString());
                        if (nodes.isEmpty()) continue;
                        if (!cost) blockers.add("granting permissions is not supported");
                        else if (nodes.size() > 1 || permission.isPresent()) blockers.add("more than one required permission");
                        else permission = Optional.of(nodes.getFirst());
                    }
                    default -> blockers.add(string(action.get("type"), "unknown") + " actions are not supported");
                }
            }
        }

        String describe() {
            List<String> parts = new ArrayList<>();
            if (!items.isEmpty()) parts.add(items.size() + " items");
            if (money.signum() > 0) parts.add("money");
            if (levels > 0) parts.add("levels");
            if (!commands.isEmpty()) parts.add("commands");
            return parts.isEmpty() ? "nothing" : String.join(" and ", parts);
        }

        private static JsonObject command(String text, String as) {
            JsonObject command = new JsonObject();
            command.addProperty("command", text);
            command.addProperty("as", as);
            return command;
        }
    }
}
