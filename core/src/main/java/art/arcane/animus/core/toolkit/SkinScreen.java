package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** The skin files on this server, labelled by who wears them. Skins show on player bodies only; other bodies are skipped. */
final class SkinScreen<P, S> extends ToolScreen<P, S> {
    static final int MINE = 46, REMOVE = 47;
    private List<ToolkitFiles.Skin> skins;

    SkinScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands) {
        super(toolkit, viewer, commands);
        toolkit.later(viewer, ToolkitFiles::skins, (List<ToolkitFiles.Skin> found) -> { skins = found; viewer.refresh(); });
    }

    @Override String title() { return "Skins"; }
    @Override int rows() { return 6; }

    @Override void draw() {
        if (skins == null) icon(22, "minecraft:clock", "&7Reading skins/", List.of(), Optional.empty());
        else list(skins, "No skin files yet; use your own skin below", (Integer slot, ToolkitFiles.Skin skin) -> {
            List<String> lines = new ArrayList<>(List.of("&7Worn by &f" + names(skin.npcs(), 4)));
            if (!skin.groups().isEmpty()) lines.add("&7Groups &f" + names(skin.groups(), 4));
            lines.add("&8" + skin.hash().substring(0, 12));
            button(slot, HEAD, title(skin.npcs().isEmpty() ? (skin.groups().isEmpty() ? "Unused skin" : skin.groups().getFirst()) : skin.npcs().getFirst()),
                    lore(lines, any("put on the selection")), (GuiModel.Click click) -> chosen().ifPresent((List<String> ids) -> toolkit.write(viewer, (ToolkitFiles files) -> files.skin(Optional.of(skin.hash()), ids))));
        });
        back();
        head(MINE, title("Use my skin"), lore(List.of("&7Copies your signed skin"), any("put it on the selection")), viewer.host().name(viewer.player()),
                (GuiModel.Click click) -> chosen().ifPresent((List<String> ids) -> toolkit.mySkin(viewer, ids)));
        button(REMOVE, "minecraft:shears", title("Remove skin"), lore(List.of("&7Back to the default look"), any("remove from the selection")),
                (GuiModel.Click click) -> chosen().ifPresent((List<String> ids) -> toolkit.write(viewer, (ToolkitFiles files) -> files.skin(Optional.empty(), ids))));
        icon(53, "minecraft:paper", "&7Player bodies only", List.of("&7Other body types are skipped"), Optional.empty());
    }
}
