package art.arcane.animus.core.gui;

/** One open window's behavior. Every method runs on the viewer's owner thread. */
public interface Screen {
    ScreenFrame render();
    void click(int slot, GuiModel.Click click);
    /** Called every owner tick while open; true asks for a new frame. */
    default boolean tick(long tick) { return false; }
    default boolean closeOnTeleport() { return true; }
}
