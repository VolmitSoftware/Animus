package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.presentation.BodySkin;
import art.arcane.animus.core.text.ChatLine;
import com.google.gson.JsonElement;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The saved skins, for player bodies; other bodies are skipped. From the NPC screen or Home a click puts one on the
 * selection and a right-click opens it to rename or delete; with nothing selected a click opens it too. In the guided
 * create this is the Skin step, where a pick makes the NPC. My skin, Player name and Default sit in the action row.
 * The header names the NPC the skins go on, as the create flow names its new one; the note that other bodies are
 * skipped shows only when one of them is selected, or nothing is.
 */
final class SkinScreen<P, S> extends ToolScreen<P, S> {
    static final int HEADER = 4, MINE = ACTIONS, NAMED = ACTIONS + 1, DEFAULT = ACTIONS + 2, NOTE = ACTIONS + 8;
    private final SkinLookup lookup;
    private final Optional<CreateFlow> creating;
    private List<SkinFiles.Entry> skins;

    SkinScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands) { this(toolkit, viewer, commands, SkinLookup.shared(), Optional.empty()); }

    SkinScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, SkinLookup lookup) { this(toolkit, viewer, commands, lookup, Optional.empty()); }

    SkinScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, CreateFlow flow) { this(toolkit, viewer, commands, flow.lookup, Optional.of(flow)); }

    private SkinScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, SkinLookup lookup, Optional<CreateFlow> creating) {
        super(toolkit, viewer, commands);
        this.lookup = lookup;
        this.creating = creating;
    }

    @Override void begin() { reload(); }

    @Override String title() { return "Skin"; }
    @Override String node() { return creating.isPresent() ? PermissionNodes.NPC_CREATE : PermissionNodes.NPC_SKIN; }
    @Override int rows() { return 6; }
    @Override boolean searchable() { return true; }
    /** The NPC at the top, new or selected, so the skins start a row lower. */
    @Override int firstEntry() { return creating.isPresent() || !toolkit.selected(viewer.id()).isEmpty() ? WIDTH : 0; }

    @Override void draw() {
        back();
        creating.ifPresent((CreateFlow flow) -> flow.showing(this));
        List<String> ids = toolkit.selected(viewer.id());
        if (skins == null) icon(22, "minecraft:clock", "&7Reading skins", List.of(), Optional.empty());
        else list(skins, (SkinFiles.Entry skin) -> skin.title() + " " + skin.hash(),
                creating.isPresent() ? "No saved skins; pick My skin, Player name or Default" : "No saved skins yet; use My skin or Player name",
                (Integer slot, SkinFiles.Entry skin) -> entry(slot, skin, ids));
        String verb = creating.isPresent() ? "pick it" : "wear it";
        head(MINE, title("My skin"), lore(List.of("&7Your own skin"), any(verb)), viewer.host().name(viewer.player()), (GuiModel.Click click) -> mine());
        button(NAMED, "minecraft:name_tag", title("Player name"), lore(List.of("&7Any Minecraft account's skin"), any("type a name")), (GuiModel.Click click) -> prompt("Player name", "", this::named));
        button(DEFAULT, HEAD, title("Default"), lore(List.of("&7The look without a skin"), any(verb)), (GuiModel.Click click) -> plain());
        if (creating.isPresent()) {
            icon(HEADER, "minecraft:name_tag", title(creating.get().name), List.of("&7New NPC"), Optional.empty());
            help("Pick the skin your new NPC wears. Default is the plain look; change it later from its menu.", "21-in-game-toolkit", "skins");
        } else {
            if (!ids.isEmpty()) icon(HEADER, "minecraft:name_tag", title(named(ids)), ids.size() == 1 ? List.of("&fNow: " + wearing(ids.getFirst())) : List.of("&7Each one changes"), Optional.empty());
            if (ids.isEmpty() || ids.stream().anyMatch((String id) -> !ToolkitFiles.PLAYER.equals(ShopFiles.type(NpcScreen.stored(toolkit, id)))))
                icon(NOTE, "minecraft:paper", "&7Player bodies only", List.of("&7Other bodies are skipped"), Optional.empty());
            help("Click a skin to put it on the selected NPCs; right-click one to rename or delete it.", "21-in-game-toolkit", "skins");
        }
    }

    /** The saved skin the NPC wears, by its title, or Default. */
    private String wearing(String id) {
        JsonElement skin = NpcScreen.stored(toolkit, id).get("skin");
        if (skin == null || !skin.isJsonPrimitive()) return "Default";
        String hash = skin.getAsString();
        return skins == null ? "a saved skin" : skins.stream().filter((SkinFiles.Entry entry) -> entry.hash().equals(hash)).map(SkinFiles.Entry::title).findFirst().orElse("a saved skin");
    }

    private void entry(int slot, SkinFiles.Entry skin, List<String> ids) {
        NpcItem head = skin.head().orElse(new NpcItem(HEAD, 1, Optional.empty(), Optional.empty()));
        List<String> lines = new ArrayList<>(List.of("&7" + worn(toolkit, skin)));
        if (creating.isEmpty() && !ids.isEmpty() && wears(ids.getFirst(), skin.hash())) lines.add("&fNow: worn");
        if (advanced()) lines.add("&8" + skin.shortHash());
        if (creating.isPresent()) {
            button(slot, head, title(skin.title()), lore(lines, any("pick it")), (GuiModel.Click click) -> creating.get().make(this, ToolkitFiles.PLAYER, Optional.of(new SkinFiles.Skin(skin.hash(), Optional.empty()))));
            return;
        }
        button(slot, head, title(skin.title()), lore(lines, hints(ids.isEmpty() ? "details" : "wear it", "details", "")), (GuiModel.Click click) -> {
            if (click.right() || toolkit.selected(viewer.id()).isEmpty()) push(new SkinDetailScreen<>(toolkit, viewer, commands, skin, this::reload));
            else chosen().ifPresent((List<String> chosen) -> wear(Optional.of(skin.hash()), chosen));
        });
    }

    /** {@code Worn by Guard Tom, trait group looks}, NPCs by the name players see, or {@code Not worn}. */
    static String worn(Toolkit<?, ?> toolkit, SkinFiles.Entry skin) {
        if (!skin.used()) return "Not worn";
        List<String> wearers = new ArrayList<>();
        for (String id : skin.npcs()) wearers.add(toolkit.npcs().find(id).map(NpcsScreen::name).orElse(id));
        for (String group : skin.groups()) wearers.add("trait group " + group);
        return "Worn by " + names(wearers, 3);
    }

    private boolean wears(String id, String hash) {
        JsonElement skin = NpcScreen.stored(toolkit, id).get("skin");
        return skin != null && skin.isJsonPrimitive() && skin.getAsString().equals(hash);
    }

    private void mine() {
        if (creating.isEmpty()) { chosen().ifPresent((List<String> ids) -> { toolkit.mySkin(viewer, ids); reload(); }); return; }
        Optional<BodySkin> skin = toolkit.skin(viewer.player());
        if (skin.isEmpty()) { refuse("Your skin has no signature; offline-mode servers cannot copy it"); return; }
        make(skin.get(), viewer.host().name(viewer.player()));
    }

    private void plain() {
        if (creating.isPresent()) creating.get().make(this, ToolkitFiles.PLAYER, Optional.empty());
        else chosen().ifPresent((List<String> ids) -> wear(Optional.empty(), ids));
    }

    /** The guided create's last step with a skin that is saved along with the NPC. */
    private void make(BodySkin skin, String label) {
        try { creating.orElseThrow().make(this, ToolkitFiles.PLAYER, Optional.of(SkinFiles.skin(skin, label))); }
        catch (IOException invalid) { refuse(invalid.getMessage()); }
    }

    /** Looks the name up, then saves the skin labelled with the account's spelling and puts it on the selection or the new NPC. */
    private void named(String typed) {
        String name = typed.strip();
        if (!name.matches(SkinLookup.NAME)) { refuse("'" + name + "' is not a Minecraft name (1-16 letters, digits or _)"); return; }
        toolkit.say(viewer.player(), ChatLine.reply("Looking up " + name + " on Mojang"));
        lookup.find(name).thenAccept((SkinLookup.Found found) -> toolkit.living().players().deliver(viewer.id(), (P online) -> {
            if (!viewer.open() || creating.isPresent() && !creating.get().shows(this)) return;
            if (found.skin().isEmpty()) { toolkit.refuse(online, found.problem()); return; }
            String label = found.name().orElse(name);
            if (creating.isPresent()) { make(found.skin().get(), label); return; }
            List<String> ids = toolkit.selected(viewer.id());
            files((SkinFiles files) -> files.wear(found.skin().get(), label, ids));
        }));
    }

    /** Puts the skin on the NPCs, or takes it off when empty, then reads the wearers again. */
    private void wear(Optional<String> hash, List<String> ids) {
        toolkit.write(viewer, (ToolkitFiles files) -> files.skin(hash, ids));
        reload();
    }

    @FunctionalInterface private interface SkinWork { String run(SkinFiles files) throws IOException; }

    /** Skin file work on the file lane, then the list is read again. */
    private void files(SkinWork work) {
        toolkit.write(viewer, (ToolkitFiles ignored) -> work.run(new SkinFiles(toolkit.npcs(), toolkit.documents())));
        reload();
    }

    void reload() {
        toolkit.later(viewer, (ToolkitFiles ignored) -> new SkinFiles(toolkit.npcs(), toolkit.documents()).skins(), (List<SkinFiles.Entry> found) -> { skins = stable(skins, found); viewer.refresh(); });
    }

    /** {@code found} in the order the screen already shows, new skins last, so a click never moves the entry under the cursor. */
    static List<SkinFiles.Entry> stable(List<SkinFiles.Entry> shown, List<SkinFiles.Entry> found) {
        if (shown == null) return found;
        List<String> order = shown.stream().map(SkinFiles.Entry::hash).toList();
        List<SkinFiles.Entry> sorted = new ArrayList<>(found);
        sorted.sort(Comparator.comparingInt((SkinFiles.Entry entry) -> {
            int at = order.indexOf(entry.hash());
            return at < 0 ? Integer.MAX_VALUE : at;
        }));
        return sorted;
    }
}
