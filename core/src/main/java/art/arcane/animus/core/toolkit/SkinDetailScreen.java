package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.npc.NpcItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** One saved skin: wear it, rename it, or delete it once nothing wears it. Each change goes back to the list, read again. */
final class SkinDetailScreen<P, S> extends ToolScreen<P, S> {
    static final int HEADER = 4, WEAR = 20, RENAME = 24, DELETE = 35;
    private final SkinFiles.Entry skin;
    private final Runnable changed;

    SkinDetailScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, SkinFiles.Entry skin, Runnable changed) {
        super(toolkit, viewer, commands);
        this.skin = skin; this.changed = changed;
    }

    @Override String title() { return skin.title(); }
    @Override String node() { return PermissionNodes.NPC_SKIN; }
    @Override int rows() { return 6; }

    @Override void draw() {
        NpcItem head = skin.head().orElse(new NpcItem(HEAD, 1, Optional.empty(), Optional.empty()));
        List<String> lines = new ArrayList<>(List.of("&7" + SkinScreen.worn(toolkit, skin)));
        if (advanced()) lines.add("&8" + skin.hash());
        icon(HEADER, head, title(skin.title()), lines, Optional.empty());
        button(WEAR, head, title("Wear it"), lore(List.of("&7Player bodies only"), any("put it on the selection")),
                (GuiModel.Click click) -> chosen().ifPresent((List<String> ids) -> done(() -> toolkit.write(viewer, (ToolkitFiles files) -> files.skin(Optional.of(skin.hash()), ids)))));
        button(RENAME, "minecraft:name_tag", title("Rename"), lore(List.of("&fNow: " + skin.label().orElse("no name")), any("type a name")),
                (GuiModel.Click click) -> prompt("Skin name", skin.label().orElse(""), (String label) -> done(() -> toolkit.write(viewer, (ToolkitFiles ignored) -> files().rename(skin.hash(), label)))));
        if (skin.used()) button(DELETE, "minecraft:lava_bucket", title("Delete"), lore(List.of("&7Worn now; take it off first"), any("delete")),
                (GuiModel.Click click) -> refuse(SkinScreen.worn(toolkit, skin) + "; take it off first"));
        else confirm(DELETE, "minecraft:lava_bucket", "Delete", "Deletes this skin; Undo in chat brings it back", 0, true,
                () -> done(() -> toolkit.write(viewer, (ToolkitFiles ignored) -> files().delete(skin.hash()))));
        back();
        help("A skin is the look a player body wears. Rename it to find it again; delete it once nothing wears it.", "21-in-game-toolkit", "skins");
    }

    private SkinFiles files() { return new SkinFiles(toolkit.npcs(), toolkit.documents()); }

    /** Queues the write, then the list's reread after it, and goes back to the list. */
    private void done(Runnable write) {
        write.run();
        changed.run();
        viewer.back();
    }
}
