package art.arcane.animus.core.shop;

import art.arcane.animus.core.inventory.MatchRule;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A shop file at {@code shops/<namespace>/<path>.json}: pages of buy, sell and trade grids. Every problem is
 * reported as {@code $.path: message} and refuses the whole shop.
 * <p>
 * A shop with {@code containers} is owned: its {@code owner} keeps the stock in those blocks. Such a shop trades items
 * for items only; each trade takes its gives from the containers and puts its needs into them.
 */
public record ShopDocument(String id, long revision, String title, Optional<String> permission, List<Mode> modes, Mode defaultMode,
                           MatchRule match, boolean closeAfterPurchase, boolean confirm, boolean log, List<Page> pages,
                           Optional<Owner> owner, List<Container> containers) {
    public static final int SCHEMA_VERSION = 1, MAXIMUM_PAGES = 64, MAXIMUM_COMMANDS = 16, MAXIMUM_CONTAINERS = 8;
    /** The player who owns an owned shop. {@code name} is the last known name, for messages only. */
    public record Owner(UUID uuid, Optional<String> name) {}
    /** A block whose inventory holds an owned shop's stock. */
    public record Container(String world, int x, int y, int z) {}
    public static final String ID = "[a-z0-9_-]{1,64}:[a-z0-9_/-]{1,191}";
    private static final String PERMISSION = "[A-Za-z0-9_-]+(\\.[A-Za-z0-9_*-]+)*";
    private static final BigDecimal MAXIMUM_PRICE = new BigDecimal("1000000000000");

    public enum Mode {
        BUY, SELL, TRADE;
        public String key() { return name().toLowerCase(Locale.ROOT); }
    }
    public enum Type { STATIC, BUY, SELL, TRADE, COMMAND }
    public enum CommandSource { CONSOLE, PLAYER }
    public record Goods(NpcItem item, int amount) {}
    public record ShopCommand(String command, CommandSource source) {}
    public record Limit(long perPlayer, long global, long resetSeconds) {
        public static final Limit NONE = new Limit(-1, -1, -1);
        public boolean any() { return perPlayer >= 0 || global >= 0; }
    }
    public record Stock(long maximum, long restockSeconds) {}
    public record Discount(BigDecimal price, Optional<Instant> start, Optional<Instant> end) {
        public boolean active(Instant now) { return start.map((Instant from) -> !now.isBefore(from)).orElse(true) && end.map(now::isBefore).orElse(true); }
    }
    public record Display(Optional<String> name, List<String> lore, boolean showPrice, boolean showLimit) {
        public Display { lore = List.copyOf(lore); }
    }
    /** One slot of one grid. {@code item} is the icon and, for buy and sell, the goods; {@code price} is for {@code item.count()} items. */
    public record Entry(String id, Type type, NpcItem item, Display display, BigDecimal price, boolean allowStack, boolean sellPartial,
                        List<Goods> needs, List<Goods> gives, List<ShopCommand> commands, Limit limit, Optional<Stock> stock,
                        Optional<String> permission, Optional<String> broadcast, boolean closeAfterPurchase, boolean dropWhenFull,
                        Optional<Discount> discount, Optional<MatchRule> match) {
        public Entry { needs = List.copyOf(needs); gives = List.copyOf(gives); commands = List.copyOf(commands); }
        public BigDecimal price(Instant now) { return discount.filter((Discount active) -> active.active(now)).map(Discount::price).orElse(price); }
    }
    public record Page(String name, int rows, Optional<String> permission, Map<Mode, Map<Integer, Entry>> grids) {
        public Page {
            Map<Mode, Map<Integer, Entry>> copy = new EnumMap<>(Mode.class);
            for (Map.Entry<Mode, Map<Integer, Entry>> grid : grids.entrySet()) copy.put(grid.getKey(), Map.copyOf(grid.getValue()));
            grids = Map.copyOf(copy);
        }
        public Map<Integer, Entry> grid(Mode mode) { return grids.getOrDefault(mode, Map.of()); }
    }
    public record Result(Optional<ShopDocument> shop, List<String> problems) {
        public Result { problems = List.copyOf(problems); }
    }

    public ShopDocument {
        modes = List.copyOf(modes); pages = List.copyOf(pages); containers = List.copyOf(containers);
    }

    /** True when the stock lives in containers. */
    public boolean stocked() { return !containers.isEmpty(); }

    /** The entry with this id anywhere in the shop. */
    public Optional<Entry> entry(String entryId) {
        for (Page page : pages) for (Map<Integer, Entry> grid : page.grids().values()) for (Entry entry : grid.values()) if (entry.id().equals(entryId)) return Optional.of(entry);
        return Optional.empty();
    }

    public boolean hasCommands() {
        for (Page page : pages) for (Map<Integer, Entry> grid : page.grids().values()) for (Entry entry : grid.values()) if (!entry.commands().isEmpty()) return true;
        return false;
    }

    public static Result parse(String id, String json) {
        try { return parse(id, JsonDocuments.object(json)); }
        catch (IOException invalid) { return new Result(Optional.empty(), List.of("$: " + invalid.getMessage())); }
    }

    public static Result parse(String expectedId, JsonObject object) {
        Reader reader = new Reader();
        reader.fields(object, Set.of("schemaVersion", "revision", "id", "title", "permission", "modes", "defaultMode", "match",
                "closeAfterPurchase", "confirm", "log", "pages", "imported", "owner", "containers"), "$");
        Optional<Owner> owner = reader.owner(object.get("owner"), "$.owner");
        List<Container> containers = reader.containers(object.get("containers"), "$.containers");
        if (!containers.isEmpty() && !object.has("owner")) reader.problem("$.owner", "a shop with containers needs an owner");
        reader.stocked = !containers.isEmpty();
        long schema = reader.integer(object, "schemaVersion", "$", 1, Integer.MAX_VALUE, -1);
        if (schema != SCHEMA_VERSION && schema != -1) reader.problem("$.schemaVersion", "shop schemaVersion " + schema + " is not supported; Animus reads version " + SCHEMA_VERSION);
        if (!object.has("schemaVersion")) reader.problem("$.schemaVersion", "expected integer 1");
        long revision = reader.integer(object, "revision", "$", 1, Long.MAX_VALUE, 1);
        String id = reader.string(object, "id", "$", true).orElse(expectedId);
        if (!id.matches(ID)) reader.problem("$.id", "expected namespace:path in lowercase letters, digits, _, - and /");
        else if (!id.equals(expectedId)) reader.problem("$.id", "id " + id + " does not match its file, which names " + expectedId);
        String title = object.has("title") ? reader.string(object, "title", "$", false).orElse(id) : id;
        Optional<String> permission = reader.permission(object, "permission", "$");
        List<Mode> modes = new ArrayList<>();
        if (object.has("modes")) {
            JsonElement value = object.get("modes");
            if (!value.isJsonArray() || value.getAsJsonArray().isEmpty()) reader.problem("$.modes", "expected a non-empty array of buy, sell and trade");
            else for (JsonElement mode : value.getAsJsonArray()) {
                Optional<Mode> parsed = reader.mode(mode, "$.modes");
                if (parsed.isPresent() && modes.contains(parsed.get())) reader.problem("$.modes", "mode " + parsed.get().key() + " is listed twice");
                else parsed.ifPresent(modes::add);
            }
        } else modes.addAll(List.of(Mode.BUY, Mode.SELL, Mode.TRADE));
        Mode defaultMode = modes.isEmpty() ? Mode.BUY : modes.getFirst();
        if (object.has("defaultMode")) {
            Optional<Mode> parsed = reader.mode(object.get("defaultMode"), "$.defaultMode");
            if (parsed.isPresent() && !modes.contains(parsed.get())) reader.problem("$.defaultMode", "mode " + parsed.get().key() + " is not enabled in modes");
            if (parsed.isPresent()) defaultMode = parsed.get();
        }
        MatchRule match = reader.match(object, "$").orElse(MatchRule.STRICT);
        boolean close = reader.bool(object, "closeAfterPurchase", "$", false);
        boolean confirm = reader.bool(object, "confirm", "$", false);
        boolean log = reader.bool(object, "log", "$", false);
        List<Page> pages = new ArrayList<>();
        JsonElement pageList = object.get("pages");
        if (pageList == null || !pageList.isJsonArray() || pageList.getAsJsonArray().isEmpty()) reader.problem("$.pages", "expected a non-empty array of pages");
        else if (pageList.getAsJsonArray().size() > MAXIMUM_PAGES) reader.problem("$.pages", "at most " + MAXIMUM_PAGES + " pages");
        else {
            JsonArray array = pageList.getAsJsonArray();
            Set<String> entryIds = new HashSet<>();
            for (int index = 0; index < array.size(); index++) reader.page(array.get(index), "$.pages[" + index + "]", entryIds).ifPresent(pages::add);
        }
        if (!reader.problems.isEmpty()) return new Result(Optional.empty(), reader.problems);
        return new Result(Optional.of(new ShopDocument(id, revision, title, permission, modes, defaultMode, match, close, confirm, log, pages, owner, containers)), List.of());
    }

    private static final class Reader {
        final List<String> problems = new ArrayList<>();
        boolean stocked;

        Optional<Owner> owner(JsonElement value, String path) {
            if (value == null) return Optional.empty();
            if (!value.isJsonObject()) { problem(path, "expected {\"uuid\": \"...\", \"name\": \"...\"}"); return Optional.empty(); }
            JsonObject object = value.getAsJsonObject();
            fields(object, Set.of("uuid", "name"), path);
            Optional<String> text = string(object, "uuid", path, true);
            UUID uuid = null;
            try { if (text.isPresent() && UUID.fromString(text.get()).toString().equals(text.get())) uuid = UUID.fromString(text.get()); }
            catch (IllegalArgumentException invalid) { uuid = null; }
            if (text.isPresent() && uuid == null) problem(path + ".uuid", "expected canonical player UUID");
            Optional<String> name = object.has("name") ? string(object, "name", path, true) : Optional.empty();
            return uuid == null ? Optional.empty() : Optional.of(new Owner(uuid, name));
        }

        List<Container> containers(JsonElement value, String path) {
            List<Container> containers = new ArrayList<>();
            if (value == null) return containers;
            if (!value.isJsonArray() || value.getAsJsonArray().isEmpty() || value.getAsJsonArray().size() > MAXIMUM_CONTAINERS) {
                problem(path, "expected an array of 1 to " + MAXIMUM_CONTAINERS + " containers");
                return containers;
            }
            JsonArray array = value.getAsJsonArray();
            for (int index = 0; index < array.size(); index++) {
                String containerPath = path + "[" + index + "]";
                JsonElement element = array.get(index);
                if (!element.isJsonObject()) { problem(containerPath, "expected {\"world\": \"...\", \"x\": 0, \"y\": 64, \"z\": 0}"); continue; }
                JsonObject object = element.getAsJsonObject();
                fields(object, Set.of("world", "x", "y", "z"), containerPath);
                int before = problems.size();
                Optional<String> world = string(object, "world", containerPath, true);
                for (String axis : List.of("x", "y", "z")) if (!object.has(axis)) problem(containerPath + "." + axis, "expected integer");
                int x = (int) integer(object, "x", containerPath, -30_000_000, 30_000_000, 0);
                int y = (int) integer(object, "y", containerPath, -2048, 2048, 0);
                int z = (int) integer(object, "z", containerPath, -30_000_000, 30_000_000, 0);
                Container container = new Container(world.orElse(""), x, y, z);
                if (problems.size() != before) continue;
                if (containers.contains(container)) problem(containerPath, "this container is listed twice");
                else containers.add(container);
            }
            return containers;
        }

        void problem(String path, String message) { problems.add(path + ": " + message); }

        void fields(JsonObject object, Set<String> allowed, String path) {
            for (String key : object.keySet()) if (!allowed.contains(key)) problem(path + "." + key, "unknown field");
        }

        Optional<Page> page(JsonElement value, String path, Set<String> entryIds) {
            if (value == null || !value.isJsonObject()) { problem(path, "expected page object"); return Optional.empty(); }
            JsonObject object = value.getAsJsonObject();
            fields(object, Set.of("name", "rows", "permission", "buy", "sell", "trade"), path);
            String name = string(object, "name", path, false).orElse("");
            int rows = (int) integer(object, "rows", path, 1, 6, 3);
            Optional<String> permission = permission(object, "permission", path);
            Map<Mode, Map<Integer, Entry>> grids = new EnumMap<>(Mode.class);
            for (Mode mode : Mode.values()) {
                if (!object.has(mode.key())) continue;
                JsonElement grid = object.get(mode.key());
                String gridPath = path + "." + mode.key();
                if (!grid.isJsonObject()) { problem(gridPath, "expected object of slot entries"); continue; }
                Map<Integer, Entry> entries = new LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> slot : grid.getAsJsonObject().entrySet()) {
                    String slotPath = gridPath + "." + slot.getKey();
                    if (!slot.getKey().matches("[0-9]{1,2}")) { problem(slotPath, "slot keys are slot numbers"); continue; }
                    int index = Integer.parseInt(slot.getKey());
                    if (index >= rows * 9) { problem(slotPath, "slot " + index + " is outside a " + rows + "-row page (0.." + (rows * 9 - 1) + ")"); continue; }
                    entry(slot.getValue(), slotPath, mode, entryIds).ifPresent((Entry entry) -> entries.put(index, entry));
                }
                grids.put(mode, entries);
            }
            return Optional.of(new Page(name, rows, permission, grids));
        }

        Optional<Entry> entry(JsonElement value, String path, Mode mode, Set<String> entryIds) {
            if (value == null || !value.isJsonObject()) { problem(path, "expected entry object"); return Optional.empty(); }
            JsonObject object = value.getAsJsonObject();
            fields(object, Set.of("id", "type", "item", "display", "price", "allowStack", "sellPartial", "needs", "gives", "commands", "limit",
                    "stock", "permission", "broadcast", "closeAfterPurchase", "dropWhenFull", "discount", "match", "imported"), path);
            int before = problems.size();
            String id = string(object, "id", path, true).orElse("");
            if (!id.isEmpty() && !id.matches("[a-z0-9_-]{1,64}")) problem(path + ".id", "expected 1-64 lowercase letters, digits, _ or -");
            else if (!id.isEmpty() && !entryIds.add(id)) problem(path + ".id", "entry id " + id + " is used twice in this shop");
            Type type = Type.STATIC;
            Optional<String> typed = string(object, "type", path, true);
            if (typed.isPresent()) {
                try { type = Type.valueOf(typed.get().toUpperCase(Locale.ROOT)); }
                catch (IllegalArgumentException unknown) { problem(path + ".type", "expected static, buy, sell, trade or command"); }
            }
            if (type == Type.SELL && mode != Mode.SELL) problem(path + ".type", "sell entries belong in a sell grid");
            if (type == Type.TRADE && mode != Mode.TRADE) problem(path + ".type", "trade entries belong in a trade grid");
            if ((type == Type.BUY || type == Type.COMMAND) && mode != Mode.BUY) problem(path + ".type", type.name().toLowerCase(Locale.ROOT) + " entries belong in a buy grid");
            NpcItem item = NpcItem.parse(object.get("item"), path + ".item", problems).orElse(null);
            Display display = display(object.get("display"), path + ".display");
            BigDecimal price = object.has("price") ? price(object.get("price"), path + ".price") : BigDecimal.ZERO;
            if ((type == Type.BUY || type == Type.SELL || type == Type.COMMAND) && !object.has("price")) problem(path + ".price", "expected a price; use 0 for free");
            boolean allowStack = bool(object, "allowStack", path, false);
            boolean sellPartial = bool(object, "sellPartial", path, false);
            if (sellPartial && type != Type.SELL) problem(path + ".sellPartial", "applies only to sell entries");
            List<Goods> needs = goods(object.get("needs"), path + ".needs", type == Type.TRADE);
            List<Goods> gives = goods(object.get("gives"), path + ".gives", type == Type.TRADE);
            if (type != Type.TRADE && (object.has("needs") || object.has("gives"))) problem(path, "needs and gives apply only to trade entries");
            List<ShopCommand> commands = commands(object.get("commands"), path + ".commands");
            if (type == Type.COMMAND && commands.isEmpty()) problem(path + ".commands", "command entries need at least one command");
            if (type == Type.STATIC && !commands.isEmpty()) problem(path + ".commands", "static entries cannot run commands");
            Limit limit = limit(object.get("limit"), path + ".limit");
            if (type == Type.STATIC && limit.any()) problem(path + ".limit", "static entries have no limits");
            Optional<Stock> stock = stock(object.get("stock"), path + ".stock");
            if (stock.isPresent() && type != Type.BUY && type != Type.COMMAND && type != Type.TRADE) problem(path + ".stock", "stock applies to buy, command and trade entries");
            Optional<String> permission = permission(object, "permission", path);
            Optional<String> broadcast = object.has("broadcast") ? string(object, "broadcast", path, false) : Optional.empty();
            if (broadcast.isPresent() && broadcast.get().length() > 512) problem(path + ".broadcast", "at most 512 characters");
            boolean close = bool(object, "closeAfterPurchase", path, false);
            boolean drop = bool(object, "dropWhenFull", path, false);
            Optional<Discount> discount = discount(object.get("discount"), path + ".discount");
            Optional<MatchRule> match = match(object, path);
            if (stocked) {
                if (type != Type.TRADE && type != Type.STATIC) problem(path + ".type", "a container shop has only trade and static entries");
                if (object.has("price") && price.signum() > 0) problem(path + ".price", "a container shop trades items for items; remove the price");
                if (stock.isPresent()) problem(path + ".stock", "a container shop's stock is its containers");
            }
            if (problems.size() != before || item == null) return Optional.empty();
            return Optional.of(new Entry(id, type, item, display, price, allowStack, sellPartial, needs, gives, commands, limit, stock, permission,
                    broadcast, close, drop, discount, match));
        }

        Display display(JsonElement value, String path) {
            if (value == null) return new Display(Optional.empty(), List.of(), true, true);
            if (!value.isJsonObject()) { problem(path, "expected object"); return new Display(Optional.empty(), List.of(), true, true); }
            JsonObject object = value.getAsJsonObject();
            fields(object, Set.of("name", "lore", "showPrice", "showLimit"), path);
            Optional<String> name = object.has("name") ? string(object, "name", path, false) : Optional.empty();
            List<String> lore = new ArrayList<>();
            if (object.has("lore")) {
                JsonElement lines = object.get("lore");
                if (!lines.isJsonArray() || lines.getAsJsonArray().size() > 32) problem(path + ".lore", "expected at most 32 strings");
                else for (JsonElement line : lines.getAsJsonArray()) {
                    if (!line.isJsonPrimitive() || !line.getAsJsonPrimitive().isString() || line.getAsString().length() > 256) problem(path + ".lore", "lines are strings of at most 256 characters");
                    else lore.add(line.getAsString());
                }
            }
            return new Display(name, lore, bool(object, "showPrice", path, true), bool(object, "showLimit", path, true));
        }

        List<Goods> goods(JsonElement value, String path, boolean required) {
            List<Goods> goods = new ArrayList<>();
            if (value == null) { if (required) problem(path, "expected a non-empty array of items"); return goods; }
            if (!value.isJsonArray() || (required && value.getAsJsonArray().isEmpty()) || value.getAsJsonArray().size() > 27) { problem(path, "expected an array of 1 to 27 items"); return goods; }
            JsonArray array = value.getAsJsonArray();
            for (int index = 0; index < array.size(); index++) {
                String itemPath = path + "[" + index + "]";
                JsonElement element = array.get(index);
                if (!element.isJsonObject()) { problem(itemPath, "expected {\"item\": {...}, \"amount\": n}"); continue; }
                JsonObject object = element.getAsJsonObject();
                fields(object, Set.of("item", "amount"), itemPath);
                Optional<NpcItem> item = NpcItem.parse(object.get("item"), itemPath + ".item", problems);
                int amount = (int) integer(object, "amount", itemPath, 1, 2304, item.map(NpcItem::count).orElse(1));
                item.ifPresent((NpcItem parsed) -> goods.add(new Goods(parsed, amount)));
            }
            return goods;
        }

        List<ShopCommand> commands(JsonElement value, String path) {
            List<ShopCommand> commands = new ArrayList<>();
            if (value == null) return commands;
            if (!value.isJsonArray() || value.getAsJsonArray().size() > MAXIMUM_COMMANDS) { problem(path, "expected an array of at most " + MAXIMUM_COMMANDS + " commands"); return commands; }
            JsonArray array = value.getAsJsonArray();
            for (int index = 0; index < array.size(); index++) {
                String commandPath = path + "[" + index + "]";
                JsonElement element = array.get(index);
                if (!element.isJsonObject()) { problem(commandPath, "expected {\"command\": \"...\", \"as\": \"console\"}"); continue; }
                JsonObject object = element.getAsJsonObject();
                fields(object, Set.of("command", "as"), commandPath);
                Optional<String> command = string(object, "command", commandPath, true);
                if (command.isPresent() && (command.get().startsWith("/") || command.get().length() > 1024 || command.get().codePoints().anyMatch(Character::isISOControl)))
                    problem(commandPath + ".command", "write at most 1024 characters without a leading slash or control characters");
                String source = object.has("as") ? string(object, "as", commandPath, true).orElse("console") : "console";
                if (!Set.of("console", "player").contains(source)) problem(commandPath + ".as", "expected console or player");
                command.ifPresent((String text) -> commands.add(new ShopCommand(text, source.equals("player") ? CommandSource.PLAYER : CommandSource.CONSOLE)));
            }
            return commands;
        }

        Limit limit(JsonElement value, String path) {
            if (value == null) return Limit.NONE;
            if (!value.isJsonObject()) { problem(path, "expected object"); return Limit.NONE; }
            JsonObject object = value.getAsJsonObject();
            fields(object, Set.of("perPlayer", "global", "resetSeconds"), path);
            long perPlayer = integer(object, "perPlayer", path, 1, 1_000_000_000, -1);
            long global = integer(object, "global", path, 1, 1_000_000_000, -1);
            long reset = integer(object, "resetSeconds", path, 1, 31_536_000L * 10, -1);
            if (perPlayer < 0 && global < 0) problem(path, "set perPlayer, global or both");
            return new Limit(perPlayer, global, reset);
        }

        Optional<Stock> stock(JsonElement value, String path) {
            if (value == null) return Optional.empty();
            if (!value.isJsonObject()) { problem(path, "expected object"); return Optional.empty(); }
            JsonObject object = value.getAsJsonObject();
            fields(object, Set.of("maximum", "restockSeconds"), path);
            long maximum = integer(object, "maximum", path, 1, 1_000_000_000, 1);
            if (!object.has("maximum")) problem(path + ".maximum", "expected integer 1..1000000000");
            long restock = integer(object, "restockSeconds", path, 1, 31_536_000L * 10, -1);
            return Optional.of(new Stock(maximum, restock));
        }

        Optional<Discount> discount(JsonElement value, String path) {
            if (value == null) return Optional.empty();
            if (!value.isJsonObject()) { problem(path, "expected object"); return Optional.empty(); }
            JsonObject object = value.getAsJsonObject();
            fields(object, Set.of("price", "start", "end"), path);
            if (!object.has("price")) problem(path + ".price", "expected a price");
            BigDecimal price = object.has("price") ? price(object.get("price"), path + ".price") : BigDecimal.ZERO;
            Optional<Instant> start = instant(object, "start", path), end = instant(object, "end", path);
            if (start.isPresent() && end.isPresent() && !end.get().isAfter(start.get())) problem(path + ".end", "must be after start");
            return Optional.of(new Discount(price, start, end));
        }

        Optional<Instant> instant(JsonObject object, String field, String path) {
            if (!object.has(field)) return Optional.empty();
            Optional<String> text = string(object, field, path, true);
            try { return text.map(Instant::parse); }
            catch (DateTimeParseException invalid) { problem(path + "." + field, "expected an ISO-8601 instant such as 2026-12-01T00:00:00Z"); return Optional.empty(); }
        }

        BigDecimal price(JsonElement value, String path) {
            try {
                if (value == null || !value.isJsonPrimitive() || value.getAsJsonPrimitive().isBoolean()) throw new NumberFormatException();
                BigDecimal price = new BigDecimal(value.getAsString());
                if (price.signum() < 0 || price.compareTo(MAXIMUM_PRICE) > 0 || price.scale() > 4) throw new NumberFormatException();
                return price;
            } catch (NumberFormatException | ArithmeticException invalid) {
                problem(path, "expected a price from 0 to 1000000000000 with at most 4 decimal places");
                return BigDecimal.ZERO;
            }
        }

        Optional<Mode> mode(JsonElement value, String path) {
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) { problem(path, "expected buy, sell or trade"); return Optional.empty(); }
            try { return Optional.of(Mode.valueOf(value.getAsString().toUpperCase(Locale.ROOT))); }
            catch (IllegalArgumentException unknown) { problem(path, "expected buy, sell or trade"); return Optional.empty(); }
        }

        Optional<MatchRule> match(JsonObject object, String path) {
            if (!object.has("match")) return Optional.empty();
            Optional<String> text = string(object, "match", path, true);
            if (text.isEmpty()) return Optional.empty();
            return switch (text.get()) {
                case "strict" -> Optional.of(MatchRule.STRICT);
                case "legacy" -> Optional.of(MatchRule.LEGACY);
                default -> { problem(path + ".match", "expected strict or legacy"); yield Optional.empty(); }
            };
        }

        Optional<String> permission(JsonObject object, String field, String path) {
            if (!object.has(field) || object.get(field).isJsonNull()) return Optional.empty();
            Optional<String> text = string(object, field, path, true);
            if (text.isPresent() && (text.get().length() > 128 || !text.get().matches(PERMISSION))) { problem(path + "." + field, "expected a permission node such as town.shop.baker"); return Optional.empty(); }
            return text;
        }

        Optional<String> string(JsonObject object, String field, String path, boolean nonBlank) {
            JsonElement value = object.get(field);
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || (nonBlank && value.getAsString().isBlank())) {
                problem(path + "." + field, nonBlank ? "expected nonblank string" : "expected string");
                return Optional.empty();
            }
            if (value.getAsString().length() > 1024) { problem(path + "." + field, "at most 1024 characters"); return Optional.empty(); }
            return Optional.of(value.getAsString());
        }

        long integer(JsonObject object, String field, String path, long minimum, long maximum, long fallback) {
            if (!object.has(field)) return fallback;
            JsonElement value = object.get(field);
            try {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new ArithmeticException();
                long number = value.getAsBigDecimal().longValueExact();
                if (number < minimum || number > maximum) throw new ArithmeticException();
                return number;
            } catch (ArithmeticException | NumberFormatException invalid) {
                problem(path + "." + field, "expected integer " + minimum + ".." + maximum);
                return fallback;
            }
        }

        boolean bool(JsonObject object, String field, String path, boolean fallback) {
            if (!object.has(field)) return fallback;
            JsonElement value = object.get(field);
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) { problem(path + "." + field, "expected boolean"); return fallback; }
            return value.getAsBoolean();
        }
    }
}
