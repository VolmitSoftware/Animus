package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.editor.DocumentKind;
import art.arcane.animus.core.editor.EditorDocuments;
import art.arcane.animus.core.editor.EditorError;
import art.arcane.animus.core.gui.GuiBinding;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.npc.GroupDocument;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.shop.ShopBinding;
import art.arcane.animus.core.shop.ShopDocument;
import art.arcane.animus.core.shop.ShopOffers;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import art.arcane.animus.core.text.ChatLine;
import art.arcane.animus.core.text.Palette;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The shop and GUI screens' file work, on the file lane: {@code shops/} and the four {@code gui/} folders, and the
 * {@code animus:shop} and {@code animus:gui} modules on NPCs. Every change to several NPCs is one transaction, refused
 * whole when any module would no longer parse; a file that something still uses is never deleted.
 */
final class ShopFiles {
    static final String VILLAGER = "minecraft:villager";
    /** How a shop binding field reads in a reply, as the shop screen labels it. */
    /** The namespace of a shop made with no NPC to name it after. */
    static final String LOOSE = "shops";
    /** How a trait group reads in a list of users. */
    static final String TRAIT_GROUP = "trait group ";
    private static final Map<String, String> LABELS = Map.of("style", "Window", "openOn", "Opens on", "profession", "Profession", "level", "Level");
    private final NpcRepository npcs;
    private final DocumentStore store;

    ShopFiles(NpcRepository npcs, DocumentStore store) { this.npcs = npcs; this.store = store; }

    /** A shop file: its title (the id without one), its offers and style when it loads, its first problem when it does not. */
    record Shop(String id, String name, String path, int offers, Optional<ShopDocument.Style> style, Optional<String> problem) {}

    /** A new shop and its chat line. */
    record Created(String line, Shop shop) {}

    /** One offer of a shop: the page it is on (from 0), its grid and slot there, and the entry. */
    record Offer(int page, ShopDocument.Mode mode, int slot, ShopDocument.Entry entry) {}

    /** A GUI file and its title or first line, when it has one. */
    record Gui(DocumentKind kind, String id, String path, Optional<String> title) {}

    @FunctionalInterface private interface Edit { boolean change(JsonObject document); }

    /**
     * Runs file work that writes shop or GUI files, then chats its line, runs {@code reload} as the console when it changed
     * something so the catalog shows it without a second line in the player's chat, and calls {@code after}. Refusals come back as red lines.
     */
    static <P, S> void save(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Load<String> work, String reload, Runnable after) {
        toolkit.later(viewer, work, (String line) -> {
            boolean changed = line.contains("; tx ");
            toolkit.say(viewer.player(), ChatLine.parse(changed ? Palette.success(line) : Palette.reply(line)));
            if (changed) viewer.host().command(Optional.empty(), reload);
            after.run();
            viewer.refresh();
        });
    }

    /** Every shop file, sorted by id. */
    List<Shop> shops() throws IOException {
        List<Shop> shops = new ArrayList<>();
        for (EditorDocuments.Location location : new EditorDocuments(store, () -> 0).list(Set.of(DocumentKind.SHOP))) {
            ShopDocument.Result result = ShopDocument.parse(location.id(), store.read(location.path()));
            Optional<ShopDocument> shop = result.shop();
            int offers = 0;
            if (shop.isPresent()) for (ShopDocument.Page page : shop.get().pages()) for (Map<Integer, ShopDocument.Entry> grid : page.grids().values()) offers += grid.size();
            shops.add(new Shop(location.id(), shop.map(ShopDocument::title).orElse(location.id()), location.path(), offers, shop.flatMap(ShopDocument::style), result.problems().stream().findFirst()));
        }
        shops.sort((Shop a, Shop b) -> a.id().compareTo(b.id()));
        return shops;
    }

    /** The shop file with this id, when there is one. */
    Optional<Shop> shop(String id) throws IOException { return shops().stream().filter((Shop shop) -> shop.id().equals(id)).findFirst(); }

    /** Every offer of a loading shop, page by page, buy, sell then trade, by slot. */
    List<Offer> offers(String id) throws IOException {
        Shop shop = shop(id).orElseThrow(() -> new IOException("No shop file for " + id));
        Optional<ShopDocument> document = ShopDocument.parse(id, store.read(shop.path())).shop();
        if (document.isEmpty()) throw new IOException(shop.problem().orElse(id + " does not load"));
        List<Offer> offers = new ArrayList<>();
        List<ShopDocument.Page> pages = document.get().pages();
        for (int page = 0; page < pages.size(); page++)
            for (ShopDocument.Mode mode : ShopDocument.Mode.values())
                for (Map.Entry<Integer, ShopDocument.Entry> slot : new TreeMap<>(pages.get(page).grid(mode)).entrySet())
                    offers.add(new Offer(page, mode, slot.getKey(), slot.getValue()));
        return offers;
    }

    /** Takes one offer out of its shop with the next revision, refused when the slot holds another entry by now. */
    String removeOffer(String id, Offer offer) throws IOException {
        Shop shop = shop(id).orElseThrow(() -> new IOException("No shop file for " + id));
        String current = store.read(shop.path());
        JsonObject document = JsonDocuments.object(current);
        JsonElement page = document.getAsJsonArray("pages").get(offer.page());
        JsonElement grid = page.isJsonObject() ? page.getAsJsonObject().get(offer.mode().key()) : null;
        JsonElement held = grid != null && grid.isJsonObject() ? grid.getAsJsonObject().get(String.valueOf(offer.slot())) : null;
        if (held == null || !held.isJsonObject() || !offer.entry().id().equals(held.getAsJsonObject().get("id").getAsString()))
            throw new IOException("The shop changed meanwhile; open Offers again");
        grid.getAsJsonObject().remove(String.valueOf(offer.slot()));
        long revision = document.has("revision") && document.get("revision").isJsonPrimitive() ? document.get("revision").getAsLong() : 0;
        document.addProperty("revision", revision + 1);
        List<String> problems = ShopDocument.parse(id, document).problems();
        if (!problems.isEmpty()) throw new IOException("The shop would not load: " + problems.getFirst());
        return "Removed " + offer.entry().id() + " from " + id + "; tx " + store.commit(List.of(new DocumentStore.Change(shop.path(), JsonDocuments.hash(current), JsonDocuments.write(document))));
    }

    /** Every file of one GUI kind, sorted by id. */
    List<Gui> guis(DocumentKind kind) throws IOException {
        List<Gui> guis = new ArrayList<>();
        for (EditorDocuments.Location location : new EditorDocuments(store, () -> 0).list(Set.of(kind))) {
            JsonObject document = EditorDocuments.source(store.read(location.path()));
            Optional<String> title = Optional.empty();
            for (String field : List.of("title", "name"))
                if (title.isEmpty() && document.has(field) && document.get(field).isJsonPrimitive()) title = Optional.of(document.get(field).getAsString());
            guis.add(new Gui(kind, location.id(), location.path(), title));
        }
        guis.sort((Gui a, Gui b) -> a.id().compareTo(b.id()));
        return guis;
    }

    /** Points every listed NPC at the shop, keeping its open click, window style and villager looks. The reply names the shop by its title. */
    String bindShop(String shop, List<String> ids) throws IOException {
        Shop known = shop(shop).orElseThrow(() -> new IOException("No shop file for " + shop));
        List<NpcRepository.Draft> drafts = binding(shop, ids);
        if (drafts.isEmpty()) return "Shop " + known.name() + " already bound";
        return "Shop " + known.name() + " on " + ToolkitFiles.named(drafts) + "; tx " + npcs.applyAll(drafts);
    }

    /** The drafts that point the listed NPCs at the shop; NPCs already on it are left out. */
    private List<NpcRepository.Draft> binding(String shop, List<String> ids) throws IOException {
        return drafts(ids, (JsonObject document) -> {
            JsonObject module = module(document, ShopBinding.MODULE).orElseGet(JsonObject::new);
            if (module.has("shop") && module.get("shop").isJsonPrimitive() && module.get("shop").getAsString().equals(shop)) return false;
            module.addProperty("shop", shop);
            put(document, ShopBinding.MODULE, Optional.of(module));
            return true;
        });
    }

    /** Whether the NPC's own shop opens on a right-click, the click that also starts a conversation. */
    static boolean opensOnRightClick(JsonObject npc) {
        return module(npc, ShopBinding.MODULE).filter((JsonObject module) -> module.has("shop"))
                .map((JsonObject module) -> !module.has("openOn") || !"left".equals(text(module, "openOn"))).orElse(false);
    }

    /**
     * Sells the held item for {@code price} as a buy offer in the first free slot of the shop's first page, as
     * {@code /animus shop stock} adds it, named the way players read it, with the price as {@code shown}, the server's
     * economy text for it. Advanced adds where the offer went.
     */
    String stock(String id, NpcItem item, BigDecimal price, String shown, boolean advanced) throws IOException {
        Shop shop = shop(id).orElseThrow(() -> new IOException("No shop file for " + id));
        String current = store.read(shop.path());
        ShopOffers.Added added = ShopOffers.add(JsonDocuments.object(current), ShopDocument.Mode.BUY, item, Optional.empty(), Optional.of(price), 1);
        String tx = store.commit(List.of(new DocumentStore.Change(shop.path(), JsonDocuments.hash(current), JsonDocuments.write(added.document()))));
        String where = advanced ? " (offer " + added.entry() + ", slot " + added.slot() + ")" : "";
        return "Added " + goods(item) + " for " + shown + where + "; tx " + tx;
    }

    /** {@code Diamond sword}, or {@code 16 Bread} for a stack: the item as the Offers list names it. */
    static String goods(NpcItem item) {
        String name = ToolScreen.itemName(new NpcItem(item.id(), 1, Optional.empty(), Optional.empty())).toLowerCase(Locale.ROOT);
        String named = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        return item.count() == 1 ? named : item.count() + " " + named;
    }

    /**
     * Sets or, when empty, removes one setting of the {@code animus:shop} binding on every listed NPC that binds
     * {@code shop}: {@code style}, {@code openOn}, or a key under {@code villager}, which only villager bodies take. Only
     * that key changes, so a villager's level keeps its profession and the other way round. The reply names the value as
     * {@code shown}, the way the screen shows it.
     */
    String shopField(String shop, List<String> ids, List<String> path, Optional<JsonElement> value, String shown) throws IOException {
        boolean villagers = path.getFirst().equals("villager");
        int[] skipped = {0};
        List<NpcRepository.Draft> drafts = drafts(ids, (JsonObject document) -> {
            Optional<JsonObject> found = module(document, ShopBinding.MODULE).filter((JsonObject module) -> bound(module, shop));
            if (found.isEmpty() || (villagers && !VILLAGER.equals(type(document)))) { skipped[0]++; return false; }
            JsonObject module = found.get(), before = module.deepCopy();
            CombatFiles.put(module, path, value);
            if (module.equals(before)) return false;
            put(document, ShopBinding.MODULE, Optional.of(module));
            return true;
        });
        if (drafts.isEmpty() && skipped[0] == ids.size())
            return villagers ? "No selected villager binds " + shop : "No selected NPC binds " + shop;
        String head = LABELS.getOrDefault(path.getLast(), path.getLast()) + " set to " + shown + " on " + ToolkitFiles.named(drafts) + (skipped[0] == 0 ? "" : ", " + skipped[0] + " skipped");
        return drafts.isEmpty() ? head : head + "; tx " + npcs.applyAll(drafts);
    }

    /** Removes the shop binding from every listed NPC that binds {@code shop}; the reply names the shop by its title while its file exists. */
    String unbindShop(String shop, List<String> ids) throws IOException {
        String title = shop(shop).map(Shop::name).orElse(shop);
        List<NpcRepository.Draft> drafts = drafts(ids, (JsonObject document) -> {
            if (module(document, ShopBinding.MODULE).filter((JsonObject module) -> bound(module, shop)).isEmpty()) return false;
            put(document, ShopBinding.MODULE, Optional.empty());
            return true;
        });
        if (drafts.isEmpty()) return "No selected NPC binds " + title;
        return "Unbound " + title + " from " + ToolkitFiles.named(drafts) + "; tx " + npcs.applyAll(drafts);
    }

    /**
     * Writes a shop with one empty page, ready for Add held item, and puts it on the NPCs it is made for, in one
     * transaction so one Undo takes both back. A typed {@code namespace:path} is the id as given; a name becomes the
     * shop's title and, in lowercase, its id in the folder of the first NPC ({@code Bakery} in town is
     * {@code town:bakery}). For an NPC in no folder the namespace is its id ({@code guard:bakery}), and with no NPC it is
     * {@value #LOOSE}. A clash gets a number.
     */
    Created create(String typed, List<String> ids) throws IOException {
        Optional<NpcDefinition> npc = ids.isEmpty() ? Optional.empty() : npcs.find(ids.getFirst());
        String name = typed.strip();
        boolean id = name.contains(":");
        String slug = id ? "" : slug(name), folder = npc.map(NpcDefinition::group).orElse("");
        String chosen = id ? name : free(!folder.isEmpty() ? BehaviorFiles.scoped(folder, slug) : npc.map(NpcDefinition::id).orElse(LOOSE) + ":" + slug);
        String path = path(DocumentKind.SHOP, chosen);
        if (store.read(path) != null) throw new IOException("Shop " + chosen + " already exists");
        JsonObject document = ShopOffers.empty(chosen);
        if (!id) document.addProperty("title", name);
        String text = JsonDocuments.write(document);
        List<String> problems = ShopDocument.parse(chosen, text).problems();
        if (!problems.isEmpty()) throw new IOException(problems.getFirst());
        List<NpcRepository.Draft> drafts = binding(chosen, ids);
        String tx = RouteFiles.commit(npcs, store, drafts, List.of(new DocumentStore.Change(path, "missing", text)));
        String on = drafts.isEmpty() ? "" : " on " + ToolkitFiles.named(drafts);
        return new Created("Made shop " + (id ? chosen : name) + on + "; tx " + tx, shop(chosen).orElseThrow(() -> new IOException("No shop file for " + chosen)));
    }

    /** {@code Old Bakery!} as {@code old-bakery}. */
    static String slug(String name) throws IOException {
        String slug = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-").replaceAll("^-+|-+$", "");
        if (slug.isEmpty()) throw new IOException("A shop name needs a letter or a digit");
        return slug.substring(0, Math.min(slug.length(), 60));
    }

    /** {@code id}, or {@code id-2} and on when a shop file already has it. */
    private String free(String id) throws IOException {
        String chosen = id;
        for (int n = 2; store.read(path(DocumentKind.SHOP, chosen)) != null; n++) chosen = id + "-" + n;
        return chosen;
    }

    /** Deletes the shop file; refused while an NPC or trait group binds it. */
    String deleteShop(String id) throws IOException {
        List<String> users = shopUsers(id);
        if (!users.isEmpty()) throw new IOException("Shop " + id + " is on " + ToolScreen.names(users, 4) + "; remove it from them first");
        return delete(DocumentKind.SHOP, id);
    }

    /** Opens the chest GUI or menu from a click on every listed NPC, keeping the open click. */
    String bindOpen(DocumentKind kind, String id, List<String> ids) throws IOException {
        String word = word(kind);
        if (store.read(path(kind, id)) == null) throw new IOException("No " + word + " file for " + id);
        List<NpcRepository.Draft> drafts = drafts(ids, (JsonObject document) -> {
            JsonObject module = module(document, GuiBinding.MODULE).orElseGet(JsonObject::new);
            JsonObject previous = module.has("open") && module.get("open").isJsonObject() ? module.getAsJsonObject("open") : new JsonObject();
            JsonObject open = new JsonObject();
            open.addProperty("kind", word);
            open.addProperty("id", id);
            if (previous.has("openOn")) open.add("openOn", previous.get("openOn"));
            if (open.equals(previous)) return false;
            module.add("open", open);
            put(document, GuiBinding.MODULE, Optional.of(module));
            return true;
        });
        if (drafts.isEmpty()) return word + " " + id + " already bound";
        return "Opens " + word + " " + id + " on " + ToolkitFiles.named(drafts) + "; tx " + npcs.applyAll(drafts);
    }

    /** Sets which click opens the GUI on every listed NPC whose click opens {@code id}. */
    String openOn(String id, List<String> ids, String openOn) throws IOException {
        List<NpcRepository.Draft> drafts = drafts(ids, (JsonObject document) -> {
            Optional<JsonObject> found = opener(document, id);
            if (found.isEmpty()) return false;
            JsonObject module = found.get(), open = module.getAsJsonObject("open");
            if (openOn.equals(open.has("openOn") ? open.get("openOn").getAsString() : "right")) return false;
            open.addProperty("openOn", openOn);
            put(document, GuiBinding.MODULE, Optional.of(module));
            return true;
        });
        if (drafts.isEmpty()) return "Opens on " + openOn + " already, or no selected NPC opens " + id;
        return "Opens " + id + " on " + openOn + " click for " + ToolkitFiles.named(drafts) + "; tx " + npcs.applyAll(drafts);
    }

    /** Puts the hologram above every listed NPC, replacing the one it had; an NPC already showing it keeps its offset. */
    String bindHologram(String id, List<String> ids) throws IOException {
        if (store.read(path(DocumentKind.HOLOGRAM, id)) == null) throw new IOException("No hologram file for " + id);
        List<NpcRepository.Draft> drafts = drafts(ids, (JsonObject document) -> {
            JsonObject module = module(document, GuiBinding.MODULE).orElseGet(JsonObject::new);
            if (module.has("hologram") && bound(module.getAsJsonObject("hologram"), id)) return false;
            JsonObject hologram = new JsonObject();
            hologram.addProperty("id", id);
            module.add("hologram", hologram);
            put(document, GuiBinding.MODULE, Optional.of(module));
            return true;
        });
        if (drafts.isEmpty()) return "Hologram " + id + " already bound";
        return "Hologram " + id + " on " + ToolkitFiles.named(drafts) + "; tx " + npcs.applyAll(drafts);
    }

    /** {@code offset y 2.5}, or {@code offset x -1, y 2.5}: the axes that move, in blocks. */
    static String offset(double[] values) {
        List<String> axes = new ArrayList<>();
        String[] names = {"x", "y", "z"};
        for (int axis = 0; axis < 3; axis++)
            if (values[axis] != 0) axes.add(names[axis] + " " + (values[axis] == Math.rint(values[axis]) ? String.valueOf((long) values[axis]) : String.valueOf(values[axis])));
        return axes.isEmpty() ? "offset 0" : "offset " + String.join(", ", axes);
    }

    /** Sets or, when empty, removes the hologram's offset from the feet on every listed NPC that shows {@code id}. */
    String offset(String id, List<String> ids, Optional<double[]> offset) throws IOException {
        List<NpcRepository.Draft> drafts = drafts(ids, (JsonObject document) -> {
            Optional<JsonObject> found = module(document, GuiBinding.MODULE).filter((JsonObject module) -> module.has("hologram") && bound(module.getAsJsonObject("hologram"), id));
            if (found.isEmpty()) return false;
            JsonObject module = found.get(), hologram = module.getAsJsonObject("hologram");
            JsonArray vector = new JsonArray();
            offset.ifPresent((double[] values) -> { for (double value : values) vector.add(value); });
            if (offset.isPresent() ? vector.equals(hologram.get("offset")) : !hologram.has("offset")) return false;
            if (offset.isPresent()) hologram.add("offset", vector); else hologram.remove("offset");
            put(document, GuiBinding.MODULE, Optional.of(module));
            return true;
        });
        String shown = offset.map(ShopFiles::offset).orElse("above the head");
        if (drafts.isEmpty()) return "Hologram " + id + " already " + shown + ", or no selected NPC shows it";
        return "Hologram " + id + " " + shown + " on " + ToolkitFiles.named(drafts) + "; tx " + npcs.applyAll(drafts);
    }

    /**
     * Sets one axis of the hologram's offset from the feet on every listed NPC that shows {@code id}, keeping its other
     * two; an NPC without an offset starts from {@link HologramScreen#HEAD}, just above the head.
     */
    String offset(String id, List<String> ids, int axis, double value) throws IOException {
        List<NpcRepository.Draft> drafts = drafts(ids, (JsonObject document) -> {
            Optional<JsonObject> found = module(document, GuiBinding.MODULE).filter((JsonObject module) -> module.has("hologram") && bound(module.getAsJsonObject("hologram"), id));
            if (found.isEmpty()) return false;
            JsonObject module = found.get(), hologram = module.getAsJsonObject("hologram");
            double[] at = HologramScreen.offset(hologram).orElse(HologramScreen.HEAD).clone();
            if (hologram.has("offset") && at[axis] == value) return false;
            at[axis] = value;
            JsonArray vector = new JsonArray();
            for (double each : at) vector.add(each);
            hologram.add("offset", vector);
            put(document, GuiBinding.MODULE, Optional.of(module));
            return true;
        });
        String shown = new String[]{"X", "Y", "Z"}[axis] + " " + HologramScreen.number(value);
        if (drafts.isEmpty()) return "Hologram " + id + " already has " + shown + ", or no selected NPC shows it";
        return "Hologram " + id + " offset " + shown + " on " + ToolkitFiles.named(drafts) + "; tx " + npcs.applyAll(drafts);
    }

    /** Removes the GUI from the click or the hologram of every listed NPC that uses {@code id}. */
    String unbindGui(DocumentKind kind, String id, List<String> ids) throws IOException {
        String part = kind == DocumentKind.HOLOGRAM ? "hologram" : "open";
        List<NpcRepository.Draft> drafts = drafts(ids, (JsonObject document) -> {
            Optional<JsonObject> found = module(document, GuiBinding.MODULE).filter((JsonObject module) -> module.has(part) && module.get(part).isJsonObject() && bound(module.getAsJsonObject(part), id));
            if (found.isEmpty()) return false;
            JsonObject module = found.get();
            module.remove(part);
            put(document, GuiBinding.MODULE, module.isEmpty() ? Optional.empty() : Optional.of(module));
            return true;
        });
        if (drafts.isEmpty()) return "No selected NPC uses " + id;
        return "Unbound " + id + " from " + ToolkitFiles.named(drafts) + "; tx " + npcs.applyAll(drafts);
    }

    /** Deletes the GUI file; refused while an NPC, trait group, shop or other GUI uses it. */
    String deleteGui(DocumentKind kind, String id) throws IOException {
        List<String> users = guiUsers(kind, id);
        if (!users.isEmpty()) throw new IOException(word(kind) + " " + id + " is used by " + ToolScreen.names(users, 4) + "; unbind it first");
        return delete(kind, id);
    }

    /** The NPCs and trait groups ({@code trait group <id>}) that bind the shop through either module, sorted. */
    List<String> shopUsers(String shop) {
        List<String> users = new ArrayList<>();
        for (NpcDefinition definition : npcs.stored()) if (usesShop(definition.json(), shop)) users.add(definition.id());
        for (GroupDocument group : npcs.groups().groups().values()) if (usesShop(group.values(), shop)) users.add(TRAIT_GROUP + group.id());
        users.sort(null);
        return users;
    }

    /**
     * What uses a GUI file: NPCs and trait groups for a chest GUI, menu or hologram, shops that lay out in a chest GUI,
     * and GUI files whose text shows a text animation by its id or, within its namespace, its path.
     */
    List<String> guiUsers(DocumentKind kind, String id) throws IOException {
        List<String> users = new ArrayList<>();
        if (kind == DocumentKind.TEXT_ANIMATION) {
            for (DocumentKind other : List.of(DocumentKind.INVENTORY, DocumentKind.MENU, DocumentKind.HOLOGRAM))
                for (Gui gui : guis(other)) {
                    String text = store.read(gui.path()), namespace = id.substring(0, id.indexOf(':') + 1);
                    boolean relative = gui.id().startsWith(namespace) && text.contains("|animation." + id.substring(namespace.length()) + "|");
                    if (relative || text.contains("|animation." + id + "|")) users.add(gui.id());
                }
            return users;
        }
        for (NpcDefinition definition : npcs.stored()) if (usesGui(definition.json(), kind, id)) users.add(definition.id());
        for (GroupDocument group : npcs.groups().groups().values()) if (usesGui(group.values(), kind, id)) users.add(TRAIT_GROUP + group.id());
        users.sort(null);
        if (kind == DocumentKind.INVENTORY)
            for (Shop shop : shops()) {
                JsonObject document = EditorDocuments.source(store.read(shop.path()));
                if (document.has("layout") && document.get("layout").isJsonPrimitive() && document.get("layout").getAsString().equals(id)) users.add("shop " + shop.id());
            }
        return users;
    }

    /** The module object on a definition or trait group's values, when it is one. */
    static Optional<JsonObject> module(JsonObject document, String name) {
        if (!document.has("modules") || !document.get("modules").isJsonObject()) return Optional.empty();
        JsonElement module = document.getAsJsonObject("modules").get(name);
        return module != null && module.isJsonObject() ? Optional.of(module.getAsJsonObject()) : Optional.empty();
    }

    /** The {@code animus:gui} module of a definition whose click opens {@code id}. */
    static Optional<JsonObject> opener(JsonObject document, String id) {
        return module(document, GuiBinding.MODULE).filter((JsonObject module) -> module.has("open") && module.get("open").isJsonObject() && bound(module.getAsJsonObject("open"), id));
    }

    /** {@code inventory} or {@code menu}, the {@code open.kind} word; the folder's own word for the rest. */
    static String word(DocumentKind kind) {
        return switch (kind) { case INVENTORY -> "inventory"; case MENU -> "menu"; case HOLOGRAM -> "hologram"; default -> "text animation"; };
    }

    static String type(JsonObject document) {
        return document.has("type") && document.get("type").isJsonPrimitive() ? document.get("type").getAsString() : "";
    }

    /** The shop module names {@code id} in {@code shop}; an open target or hologram names it in {@code id}. */
    private static boolean bound(JsonObject part, String id) { return id.equals(text(part, part.has("shop") ? "shop" : "id")); }

    /** {@code open.kind}, which defaults to {@code inventory}. */
    private static String openKind(JsonObject open) { return open.has("kind") ? text(open, "kind") : "inventory"; }

    private static String text(JsonObject object, String field) {
        JsonElement value = object.get(field);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private static boolean usesShop(JsonObject document, String shop) {
        if (module(document, ShopBinding.MODULE).filter((JsonObject module) -> bound(module, shop)).isPresent()) return true;
        return opener(document, shop).map((JsonObject module) -> openKind(module.getAsJsonObject("open"))).filter("shop"::equals).isPresent();
    }

    /** The definition or trait group's values open or show this GUI through {@code animus:gui}. */
    static boolean usesGui(JsonObject document, DocumentKind kind, String id) {
        if (kind == DocumentKind.HOLOGRAM)
            return module(document, GuiBinding.MODULE).filter((JsonObject module) -> module.has("hologram") && module.get("hologram").isJsonObject() && bound(module.getAsJsonObject("hologram"), id)).isPresent();
        return opener(document, id).map((JsonObject module) -> openKind(module.getAsJsonObject("open"))).filter(word(kind)::equals).isPresent();
    }

    private String delete(DocumentKind kind, String id) throws IOException {
        String path = path(kind, id), current = store.read(path);
        if (current == null) throw new IOException("No file " + path);
        return "Deleted " + path + "; tx " + store.commit(List.of(new DocumentStore.Change(path, JsonDocuments.hash(current), null)));
    }

    private static String path(DocumentKind kind, String id) throws IOException {
        try { return EditorDocuments.pathFor(kind, id); }
        catch (EditorError invalid) { throw new IOException(invalid.getMessage() + " Use namespace:path in lowercase, such as town:baker."); }
    }

    private static void put(JsonObject document, String name, Optional<JsonObject> module) {
        JsonObject modules = document.has("modules") && document.get("modules").isJsonObject() ? document.getAsJsonObject("modules") : new JsonObject();
        if (module.isPresent()) modules.add(name, module.get()); else modules.remove(name);
        if (modules.isEmpty()) document.remove("modules"); else document.add("modules", modules);
    }

    /** Drafts for the listed NPCs the edit changed; refused whole when any changed module no longer parses. */
    private List<NpcRepository.Draft> drafts(List<String> ids, Edit edit) throws IOException {
        List<NpcRepository.Draft> drafts = new ArrayList<>();
        for (String id : ids) {
            NpcRepository.Draft draft = npcs.draft(id);
            JsonObject document = draft.document();
            if (!edit.change(document)) continue;
            try { ShopBinding.parse(document); GuiBinding.parse(document); }
            catch (IllegalArgumentException invalid) { throw new IOException(id + " " + invalid.getMessage()); }
            drafts.add(new NpcRepository.Draft(draft.path(), draft.expectedHash(), draft.expectedRevision(), document));
        }
        return drafts;
    }
}
