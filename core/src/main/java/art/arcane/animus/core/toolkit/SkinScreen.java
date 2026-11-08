package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.text.ChatLine;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The skin files on this server, by label or by who wears them. Skins show on player bodies only; other bodies are
 * skipped. A click puts a skin on the selection, a right click relabels it and a sneak click deletes a file nothing
 * wears, asking once more first.
 */
final class SkinScreen<P, S> extends ToolScreen<P, S> {
    static final int MINE = 46, NAMED = 47, REMOVE = 51;
    private final SkinLookup lookup;
    private List<SkinFiles.Entry> skins;
    private String deleting;

    SkinScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands) { this(toolkit, viewer, commands, Mojang.LOOKUP); }

    SkinScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, SkinLookup lookup) {
        super(toolkit, viewer, commands);
        this.lookup = lookup;
        reload();
    }

    /** One HTTP client for every Skins screen, made on first use. */
    private static final class Mojang { static final SkinLookup LOOKUP = SkinLookup.mojang(); }

    @Override String title() { return "Skins"; }
    @Override int rows() { return 6; }

    @Override void draw() {
        if (skins == null) icon(22, "minecraft:clock", "&7Reading skins/", List.of(), Optional.empty());
        else list(skins, "No skin files yet; use your own skin or a player name below", this::entry);
        back();
        head(MINE, title("Use my skin"), lore(List.of("&7Copies your signed skin"), any("put it on the selection")), viewer.host().name(viewer.player()),
                (GuiModel.Click click) -> { deleting = null; chosen().ifPresent((List<String> ids) -> { toolkit.mySkin(viewer, ids); reload(); }); });
        button(NAMED, "minecraft:name_tag", title("From a player name"), lore(List.of("&7Looks up the account's signed skin", "&7Saves it labelled with the name", "&7Needs internet access, not online mode"),
                any("type a name; it goes on the selection")), (GuiModel.Click click) -> { deleting = null; prompt("Player name", "", this::named); });
        button(REMOVE, "minecraft:shears", title("Remove skin"), lore(List.of("&7Back to the default look"), any("remove from the selection")),
                (GuiModel.Click click) -> { deleting = null; chosen().ifPresent((List<String> ids) -> wear(Optional.empty(), ids)); });
        icon(53, "minecraft:paper", "&7Player bodies only", List.of("&7Other body types are skipped"), Optional.empty());
    }

    private void entry(int slot, SkinFiles.Entry skin) {
        boolean armed = skin.hash().equals(deleting);
        List<String> lines = new ArrayList<>();
        if (armed) lines.addAll(List.of("&cSneak-click again to delete skins/" + skin.shortHash(), "&7Any other click cancels"));
        lines.add("&7Worn by &f" + names(skin.npcs(), 4));
        if (!skin.groups().isEmpty()) lines.add("&7Groups &f" + names(skin.groups(), 4));
        lines.add("&8" + skin.shortHash());
        String sneak = skin.used() ? "delete (only when unused)" : armed ? "delete the file now" : "delete the file, asks once more";
        button(slot, skin.texture().map(ToolScreen::skinHead).orElse(new NpcItem(HEAD, 1, Optional.empty(), Optional.empty())), armed ? "&cDelete " + skin.title() + "?" : title(skin.title()), lore(lines, hints("put on the selection", "rename", sneak)), (GuiModel.Click click) -> {
            boolean wasArmed = skin.hash().equals(deleting);
            deleting = null;
            if (sneak(click)) delete(skin, wasArmed);
            else if (right(click)) prompt("Skin label", skin.label().orElse(""), (String label) -> files((SkinFiles files) -> files.rename(skin.hash(), label)));
            else chosen().ifPresent((List<String> ids) -> wear(Optional.of(skin.hash()), ids));
        });
    }

    private void delete(SkinFiles.Entry skin, boolean armed) {
        if (skin.used()) { toolkit.say(viewer.player(), ChatLine.error(skin.title() + " is worn by " + names(skin.npcs(), 4) + (skin.groups().isEmpty() ? "" : " and groups " + names(skin.groups(), 4)) + "; take it off first")); return; }
        if (!armed) { deleting = skin.hash(); return; }
        files((SkinFiles files) -> files.delete(skin.hash()));
    }

    /** Looks the name up, then saves the skin labelled with the account's spelling and puts it on the selection. */
    private void named(String typed) {
        String name = typed.strip();
        if (!name.matches(SkinLookup.NAME)) { toolkit.say(viewer.player(), ChatLine.error("'" + name + "' is not a Minecraft name (1-16 letters, digits or _)")); return; }
        toolkit.say(viewer.player(), ChatLine.reply("Looking up " + name + " on Mojang"));
        lookup.find(name).thenAccept((SkinLookup.Found found) -> toolkit.living().players().deliver(viewer.id(), (P online) -> {
            if (!viewer.open()) return;
            if (found.skin().isEmpty()) { toolkit.say(online, ChatLine.error(found.problem())); return; }
            String label = found.name().orElse(name);
            List<String> ids = toolkit.selected(viewer.id());
            files((SkinFiles files) -> {
                String hash = files.save(found.skin().get(), label);
                String saved = "Saved " + label + "'s skin as skins/" + hash.substring(0, 12);
                return ids.isEmpty() ? saved + "; select NPCs and click it to wear it" : saved + "; " + new ToolkitFiles(toolkit.npcs(), toolkit.documents()).skin(Optional.of(hash), ids);
            });
        }));
    }

    /** Puts the skin on the NPCs, or takes it off when empty, then reads the wearers again. */
    private void wear(Optional<String> hash, List<String> ids) {
        toolkit.write(viewer, (ToolkitFiles files) -> files.skin(hash, ids));
        reload();
    }

    @FunctionalInterface private interface SkinWork { String run(SkinFiles files) throws java.io.IOException; }

    /** Skin file work on the file lane, then the list is read again. */
    private void files(SkinWork work) {
        toolkit.write(viewer, (ToolkitFiles ignored) -> work.run(new SkinFiles(toolkit.npcs(), toolkit.documents())));
        reload();
    }

    private void reload() {
        toolkit.later(viewer, (ToolkitFiles ignored) -> new SkinFiles(toolkit.npcs(), toolkit.documents()).skins(), (List<SkinFiles.Entry> found) -> { skins = stable(skins, found); viewer.refresh(); });
    }

    /** {@code found} in the order the screen already shows, new skins last, so a click never moves the entry under the cursor. */
    static List<SkinFiles.Entry> stable(List<SkinFiles.Entry> shown, List<SkinFiles.Entry> found) {
        if (shown == null) return found;
        List<String> order = shown.stream().map(SkinFiles.Entry::hash).toList();
        List<SkinFiles.Entry> sorted = new ArrayList<>(found);
        sorted.sort(java.util.Comparator.comparingInt((SkinFiles.Entry entry) -> {
            int at = order.indexOf(entry.hash());
            return at < 0 ? Integer.MAX_VALUE : at;
        }));
        return sorted;
    }
}
