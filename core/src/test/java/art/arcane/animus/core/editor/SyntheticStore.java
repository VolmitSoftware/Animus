package art.arcane.animus.core.editor;

import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/** A data folder shaped like an imported Citizens server: NPCs with routes, skins, behaviors, shops and holograms. */
final class SyntheticStore {
    private SyntheticStore() {}

    /** Writes the documents under {@code root} and returns how many it wrote. */
    static int write(Path root, int npcs, int skins, int behaviors, int shops, int holograms) throws IOException {
        List<String> skinIds = new ArrayList<>();
        for (int index = 0; index < skins; index++) {
            JsonObject textures = new JsonObject(), skin = new JsonObject(), url = new JsonObject();
            url.addProperty("url", "https://textures.minecraft.net/texture/" + String.format("%064x", index + 1));
            skin.add("SKIN", url);
            textures.add("textures", skin);
            textures.addProperty("profileName", "skin" + index);
            JsonObject document = new JsonObject();
            document.addProperty("textureRaw", Base64.getEncoder().encodeToString(textures.toString().getBytes(StandardCharsets.UTF_8)));
            document.addProperty("signature", "c2lnbmF0dXJl".repeat(60));
            String text = JsonDocuments.write(document);
            String id = JsonDocuments.hash(text);
            skinIds.add(id);
            write(root, "skins/" + id + ".json", text);
        }
        for (int index = 0; index < npcs; index++) {
            JsonObject npc = new JsonObject();
            npc.addProperty("schemaVersion", 2);
            npc.addProperty("revision", 1 + index % 5);
            npc.addProperty("uuid", UUID.nameUUIDFromBytes(("npc" + index).getBytes(StandardCharsets.UTF_8)).toString());
            npc.addProperty("id", "citizens-" + index);
            npc.addProperty("group", "world");
            npc.addProperty("name", "&f&lTown &6citizen " + index);
            npc.addProperty("type", "minecraft:player");
            npc.addProperty("spawned", index % 10 != 0);
            JsonObject position = new JsonObject();
            position.addProperty("world", "world");
            position.addProperty("x", index * 3.5);
            position.addProperty("y", 64);
            position.addProperty("z", -index * 1.5);
            position.addProperty("yaw", 0);
            position.addProperty("pitch", 0);
            npc.add("position", position);
            if (!skinIds.isEmpty()) npc.addProperty("skin", skinIds.get(index % skinIds.size()));
            JsonArray tags = new JsonArray();
            tags.add("imported");
            npc.add("tags", tags);
            JsonArray list = new JsonArray();
            list.add("town:b-" + index % Math.max(1, behaviors));
            npc.add("behaviors", list);
            JsonObject modules = new JsonObject(), navigation = new JsonObject();
            JsonArray points = new JsonArray();
            for (int point = 0; point < 30; point++) {
                JsonObject step = new JsonObject();
                step.addProperty("x", index * 3.5 + point);
                step.addProperty("y", 64);
                step.addProperty("z", -index * 1.5 + point);
                points.add(step);
            }
            navigation.add("points", points);
            modules.add("animus:navigation", navigation);
            npc.add("modules", modules);
            write(root, "npcs/world/citizens-" + index + ".json", JsonDocuments.write(npc));
        }
        for (int index = 0; index < behaviors; index++) {
            JsonObject behavior = new JsonObject();
            behavior.addProperty("schemaVersion", 1);
            behavior.addProperty("revision", 1);
            behavior.addProperty("id", "town:b-" + index);
            behavior.addProperty("entry", "main");
            JsonArray triggers = new JsonArray();
            JsonObject trigger = new JsonObject();
            trigger.addProperty("id", "click");
            trigger.addProperty("event", "interaction");
            triggers.add(trigger);
            behavior.add("triggers", triggers);
            JsonObject routines = new JsonObject(), main = new JsonObject();
            main.addProperty("type", "sequence");
            JsonArray steps = new JsonArray();
            for (int step = 0; step < 20; step++) {
                JsonObject say = new JsonObject(), arguments = new JsonObject();
                say.addProperty("type", "action");
                say.addProperty("capability", "animus:say");
                arguments.addProperty("text", "Line " + step + " of the town conversation.");
                say.add("arguments", arguments);
                steps.add(say);
            }
            main.add("steps", steps);
            routines.add("main", main);
            behavior.add("routines", routines);
            write(root, "behaviors/town/b-" + index + ".json", JsonDocuments.write(behavior));
        }
        for (int index = 0; index < shops; index++) {
            JsonObject shop = new JsonObject(), page = new JsonObject(), trade = new JsonObject();
            shop.addProperty("schemaVersion", 1);
            shop.addProperty("revision", 1);
            shop.addProperty("id", "town:s-" + index);
            shop.addProperty("title", "&6Shop " + index);
            page.addProperty("name", "Goods");
            page.addProperty("rows", 3);
            for (int slot = 0; slot < 6; slot++) {
                JsonObject offer = new JsonObject(), item = new JsonObject();
                offer.addProperty("id", "offer-" + slot);
                offer.addProperty("type", "trade");
                item.addProperty("id", "minecraft:bread");
                offer.add("item", item);
                trade.add(Integer.toString(slot), offer);
            }
            page.add("trade", trade);
            JsonArray pages = new JsonArray();
            pages.add(page);
            shop.add("pages", pages);
            write(root, "shops/town/s-" + index + ".json", JsonDocuments.write(shop));
        }
        for (int index = 0; index < holograms; index++) {
            JsonObject hologram = new JsonObject(), anchor = new JsonObject();
            hologram.addProperty("schemaVersion", 3);
            hologram.addProperty("revision", 1);
            anchor.addProperty("world", "world");
            JsonArray at = new JsonArray();
            at.add(index);
            at.add(70);
            at.add(index);
            anchor.add("position", at);
            hologram.add("anchor", anchor);
            JsonArray lines = new JsonArray();
            lines.add("&6Sign " + index);
            lines.add("&7Second line");
            hologram.add("lines", lines);
            write(root, "gui/holograms/town/h-" + index + ".json", JsonDocuments.write(hologram));
        }
        return npcs + skins + behaviors + shops + holograms;
    }

    private static void write(Path root, String relative, String text) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}
