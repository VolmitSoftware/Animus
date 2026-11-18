package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.editor.EditorDocuments;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.shop.PlayerShops;
import art.arcane.animus.core.shop.ShopBinding;
import art.arcane.animus.core.shop.ShopCatalog;
import art.arcane.animus.core.shop.ShopDocument;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocumentTree;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The file work of shops players run themselves, on the file lane: the shop file and the NPCs that open it change
 * together, one transaction each, so one Undo takes a change back whole. Every write passes the owner rules, which hold a
 * player to shops they own that keep their stock in chests.
 */
final class PlayerShopFiles {
    /** The offers a page holds; the last row stays free for the chest window's controls. */
    static final int PER_PAGE = 45, ROWS = 6;
    static final String NAMESPACE = PlayerShops.NAMESPACE, OFFERS = "Offers";

    /** A shop as stored, and the NPCs whose click opens it. */
    record Shop(ShopDocument shop, JsonObject document, List<String> npcs) {}

    /** What a new shop is: its owner, kind, body and chest, and where its NPC stands. */
    record Plan(UUID owner, String ownerName, ShopDocument.Kind kind, String type, ShopDocument.Container chest, Landing.Spot spot) {}

    /** The new shop and its NPC, and the shop as stored, so its menu opens with it. */
    record Made(String shop, String npc, String title, String tx, Shop stored) {}

    private final NpcRepository npcs;
    private final DocumentStore store;

    PlayerShopFiles(NpcRepository npcs, DocumentStore store) { this.npcs = npcs; this.store = store; }

    Shop read(String id) throws IOException {
        String text = store.read(PlayerShops.path(id));
        if (text == null) throw new IOException("That shop is gone");
        JsonObject document = JsonDocuments.object(text);
        ShopDocument.Result parsed = ShopDocument.parse(id, document);
        if (parsed.shop().isEmpty()) throw new IOException("The shop file has a problem: " + ShopDocument.readable(parsed.problems().getFirst()));
        return new Shop(parsed.shop().get(), document, npcsOf(id));
    }

    /** The NPCs whose click opens the shop. */
    List<String> npcsOf(String id) {
        List<String> found = new ArrayList<>();
        for (NpcDefinition definition : npcs.stored()) {
            try { if (ShopBinding.parse(definition.json()).map((ShopBinding binding) -> binding.shop().equals(id)).orElse(false)) found.add(definition.id()); }
            catch (IllegalArgumentException invalid) { /* A malformed module binds no shop. */ }
        }
        return found;
    }

    /** How many shop files name {@code player} as their owner, and which shop already keeps its stock in {@code chest}. */
    record Census(long owned, Optional<String> holder) {}

    Census census(UUID player, ShopDocument.Container chest) throws IOException {
        long owned = 0;
        Optional<String> holder = Optional.empty();
        Path root = store.resolve(ShopCatalog.SHOP_FOLDER);
        for (Path file : JsonDocumentTree.files(root)) {
            String relative = ShopCatalog.SHOP_FOLDER + "/" + root.relativize(file).toString().replace('\\', '/');
            Optional<String> id = EditorDocuments.idFor(art.arcane.animus.core.editor.DocumentKind.SHOP, relative);
            String text = store.read(relative);
            if (id.isEmpty() || text == null) continue;
            Optional<ShopDocument> shop;
            try { shop = ShopDocument.parse(id.get(), JsonDocuments.object(text)).shop(); }
            catch (IOException unreadable) { continue; }
            if (shop.isEmpty()) continue;
            if (shop.get().owns(player)) owned++;
            if (holder.isEmpty() && shop.get().containers().contains(chest)) holder = Optional.of(shop.get().id());
        }
        return new Census(owned, holder);
    }

    /** Saves the new shop and its NPC, spawned at the plan's spot and opening the shop on a click, in one transaction. */
    Made make(Plan plan, String world) throws IOException {
        String title = plan.ownerName() + "'s shop";
        Set<String> taken = new HashSet<>();
        for (NpcDefinition definition : npcs.stored()) taken.add(definition.id());
        String npc = CreateFlow.id(title, taken);
        String shop = NAMESPACE + ":" + npc;
        for (int n = 2; store.read(PlayerShops.path(shop)) != null; n++) shop = NAMESPACE + ":" + npc + "-" + n;
        CreateFlow.Draft draft = CreateFlow.draft(npcs, store, new CreateFlow.Plan(title, npc, plan.type(), true, Optional.empty()), world, plan.spot(), plan.owner());
        JsonObject definition = draft.definition().json();
        JsonObject modules = new JsonObject(), binding = new JsonObject();
        binding.addProperty("shop", shop);
        modules.add(ShopBinding.MODULE, binding);
        definition.add("modules", modules);
        NpcDefinition bound = NpcDefinition.parse(JsonDocuments.write(definition));
        String document = JsonDocuments.write(document(shop, title, plan));
        List<String> problems = ShopDocument.parse(shop, JsonDocuments.object(document)).problems();
        if (!problems.isEmpty()) throw new IOException(ShopDocument.readable(problems.getFirst()));
        String tx = art.arcane.animus.core.npc.DefinitionBatch.commit(store, List.of(bound), Map.of(bound.path(), "missing"),
                List.of(new DocumentStore.Change(PlayerShops.path(shop), "missing", document)));
        npcs.reload();
        return new Made(shop, npc, title, tx, read(shop));
    }

    private static JsonObject document(String id, String title, Plan plan) {
        JsonObject shop = new JsonObject();
        shop.addProperty("schemaVersion", ShopDocument.SCHEMA_VERSION);
        shop.addProperty("revision", 1);
        shop.addProperty("id", id);
        shop.addProperty("title", title);
        shop.addProperty("style", ShopDocument.Style.NATIVE.key());
        JsonArray modes = new JsonArray();
        modes.add(ShopDocument.Mode.TRADE.key());
        shop.add("modes", modes);
        shop.addProperty("defaultMode", ShopDocument.Mode.TRADE.key());
        shop.addProperty("kind", plan.kind().key());
        JsonObject owner = new JsonObject();
        owner.addProperty("uuid", plan.owner().toString());
        owner.addProperty("name", plan.ownerName());
        shop.add("owner", owner);
        JsonArray containers = new JsonArray();
        containers.add(chest(plan.chest()));
        shop.add("containers", containers);
        shop.add("pages", pages(List.of(), Optional.empty()));
        return shop;
    }

    private static JsonObject chest(ShopDocument.Container container) {
        JsonObject chest = new JsonObject();
        chest.addProperty("world", container.world());
        chest.addProperty("x", container.x()); chest.addProperty("y", container.y()); chest.addProperty("z", container.z());
        return chest;
    }

    /** Every entry in the trade grid, {@value #PER_PAGE} a page; the first page keeps its name. */
    private static JsonArray pages(List<JsonObject> entries, Optional<String> firstName) {
        JsonArray pages = new JsonArray();
        for (int start = 0; start < Math.max(1, entries.size()); start += PER_PAGE) {
            JsonObject page = new JsonObject();
            page.addProperty("name", pages.isEmpty() ? firstName.orElse(OFFERS) : OFFERS + " " + (pages.size() + 1));
            page.addProperty("rows", ROWS);
            List<JsonObject> chunk = entries.subList(Math.min(start, entries.size()), Math.min(entries.size(), start + PER_PAGE));
            if (!chunk.isEmpty()) {
                JsonObject grid = new JsonObject();
                for (int slot = 0; slot < chunk.size(); slot++) grid.add(Integer.toString(slot), chunk.get(slot));
                page.add(ShopDocument.Mode.TRADE.key(), grid);
            }
            pages.add(page);
        }
        return pages;
    }

    /** Replaces the shop's offers: {@code entries} in their order, every entry in the trade grid. */
    String offers(String id, List<JsonObject> entries) throws IOException {
        if (entries.size() > PER_PAGE * ShopDocument.MAXIMUM_PAGES) throw new IOException("A shop holds at most " + PER_PAGE * ShopDocument.MAXIMUM_PAGES + " offers");
        return write(id, (JsonObject document) -> {
            Optional<String> first = document.get("pages") instanceof JsonArray pages && !pages.isEmpty() && pages.get(0) instanceof JsonObject page
                    && page.get("name") instanceof JsonElement name && name.isJsonPrimitive() ? Optional.of(name.getAsString()).filter((String text) -> !text.isBlank()) : Optional.empty();
            document.add("pages", pages(entries, first));
            return "Saved " + entries.size() + (entries.size() == 1 ? " offer" : " offers");
        });
    }

    /** Closes the shop with {@code reason}, or opens it when empty. */
    String closed(String id, Optional<String> reason) throws IOException {
        return write(id, (JsonObject document) -> {
            if (reason.isPresent()) document.addProperty("closed", reason.get());
            else document.remove("closed");
            return reason.isPresent() ? "Closed the shop" : "Opened the shop";
        });
    }

    /** Changes how the shop trades; refused while an offer does not fit the new kind, such as a trade without a book in a book shop. */
    String kind(String id, ShopDocument.Kind kind) throws IOException {
        try {
            return write(id, (JsonObject document) -> {
                document.addProperty("kind", kind.key());
                return "The shop is now a " + kind.key() + " shop";
            });
        } catch (IOException refused) { throw new IOException("It cannot be a " + kind.key() + " shop yet. " + refused.getMessage(), refused); }
    }

    /** The shop's title and the name of every NPC that opens it. */
    String rename(String id, String title) throws IOException {
        if (title.isBlank() || title.length() > 64) throw new IOException("A shop name is 1 to 64 characters");
        Shop shop = read(id);
        List<NpcRepository.Draft> drafts = new ArrayList<>();
        for (String npc : shop.npcs()) {
            NpcRepository.Draft draft = npcs.draft(npc);
            JsonObject document = draft.document();
            document.addProperty("name", title);
            drafts.add(new NpcRepository.Draft(draft.path(), draft.expectedHash(), draft.expectedRevision(), document));
        }
        JsonObject document = shop.document().deepCopy();
        document.addProperty("title", title);
        return "Renamed the shop to " + title + "; tx " + RouteFiles.commit(npcs, store, drafts, List.of(change(id, document)));
    }

    /**
     * Adds a chest; a shop keeps at most {@value ShopDocument#MAXIMUM_CONTAINERS}. The listed chests in {@code gone}, whose
     * blocks were broken, leave in the same change, so a shop whose last chest was broken can open again.
     */
    String addChest(String id, ShopDocument.Container container, List<ShopDocument.Container> gone) throws IOException {
        return write(id, (JsonObject document) -> {
            JsonArray containers = document.get("containers") instanceof JsonArray listed ? listed : new JsonArray();
            if (containers.contains(chest(container))) throw new IOException("The shop already uses that chest");
            int dropped = 0;
            for (ShopDocument.Container broken : gone) if (containers.remove(chest(broken))) dropped++;
            if (containers.size() >= ShopDocument.MAXIMUM_CONTAINERS) throw new IOException("A shop keeps at most " + ShopDocument.MAXIMUM_CONTAINERS + " chests");
            containers.add(chest(container));
            document.add("containers", containers);
            return "Added the chest at " + PlayerShops.Block.of(container).shown() + (dropped == 0 ? "" : dropped == 1 ? "; the broken one is gone from the list" : "; the broken ones are gone from the list");
        });
    }

    /** Stops using a chest; the chest and what it holds stay. A shop keeps at least one. */
    String removeChest(String id, ShopDocument.Container container) throws IOException {
        return write(id, (JsonObject document) -> {
            if (!(document.get("containers") instanceof JsonArray containers) || !containers.contains(chest(container))) throw new IOException("The shop does not use that chest");
            if (containers.size() == 1) throw new IOException("A shop needs at least one chest; add another first");
            containers.remove(chest(container));
            return "The shop no longer uses the chest at " + PlayerShops.Block.of(container).shown() + "; it and its items stay";
        });
    }

    /** Removes the shop and the NPCs that open it in one transaction; its chests and their items stay. */
    String remove(String id) throws IOException {
        Shop shop = read(id);
        List<DocumentStore.Change> changes = new ArrayList<>();
        for (String npc : shop.npcs()) {
            NpcRepository.Draft draft = npcs.draft(npc);
            changes.add(new DocumentStore.Change(draft.path(), draft.expectedHash(), null));
            ToolkitFiles.pointer(store, draft.path()).ifPresent(changes::add);
        }
        String path = PlayerShops.path(id);
        changes.add(new DocumentStore.Change(path, JsonDocuments.hash(store.read(path)), null));
        String tx = store.commit(changes);
        npcs.reload();
        return "Removed " + art.arcane.animus.core.text.RichText.plain(art.arcane.animus.core.text.RichText.colorize(shop.shop().title())) + "; its chests and their items stay; tx " + tx;
    }

    @FunctionalInterface private interface Edit { String apply(JsonObject document) throws IOException; }

    /** Applies {@code edit} to the stored shop, checks it still loads and commits it; the reply names the transaction. */
    private String write(String id, Edit edit) throws IOException {
        Shop shop = read(id);
        JsonObject document = shop.document().deepCopy();
        String line = edit.apply(document);
        return line + "; tx " + store.commit(List.of(change(id, document)));
    }

    /** The write of {@code document} over the stored shop, with its revision raised; refused when it would not load. */
    private DocumentStore.Change change(String id, JsonObject document) throws IOException {
        String path = PlayerShops.path(id), current = store.read(path);
        if (current == null) throw new IOException("That shop is gone");
        document.addProperty("revision", EditorDocuments.revision(JsonDocuments.object(current)) + 1);
        List<String> problems = ShopDocument.parse(id, document).problems();
        if (!problems.isEmpty()) throw new IOException(ShopDocument.readable(problems.getFirst()));
        return new DocumentStore.Change(path, JsonDocuments.hash(current), JsonDocuments.write(document));
    }
}
