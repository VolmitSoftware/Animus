package art.arcane.animus.bukkit;

import art.arcane.animus.core.text.ChatLine;
import art.arcane.animus.core.text.DocsLink;
import art.arcane.animus.core.text.Palette;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BukkitActorTest {
    private static final String DOTTED = "Missing animus.tools; see config.toml for int3:greet";

    @Test void plainReplyReachesAPlayerAsTextWithoutLinks() throws ReflectiveOperationException {
        Heard heard = new Heard();
        new BukkitActor(heard.player(), new ReplyScheduler(null)).plain(Palette.error(DOTTED));
        assertEquals(List.of(), heard.strings, "the server turns dotted words in a String into web links");
        assertEquals(1, heard.lines.size());
        BaseComponent[] line = heard.lines.getFirst();
        assertEquals("[Animus]: " + DOTTED, BaseComponent.toPlainText(line));
        assertEquals(List.of(), clicks(line));
        assertEquals(ChatColor.RED, line[line.length - 1].getColorRaw(), "colour codes still render");
    }

    @Test void replyWithoutChipsIsPlainTextToo() throws ReflectiveOperationException {
        Heard heard = new Heard();
        BukkitActor actor = new BukkitActor(heard.player(), new ReplyScheduler(null));
        actor.tell(DOTTED);
        assertEquals(List.of(), heard.strings);
        assertEquals(List.of(), clicks(heard.lines.getFirst()));
    }

    @Test void docsChipStillOpensItsPage() throws ReflectiveOperationException {
        Heard heard = new Heard();
        new BukkitActor(heard.player(), new ReplyScheduler(null)).rich(ChatLine.reply(DOTTED).docs(DocsLink.PERMISSIONS));
        assertEquals(List.of(), heard.strings);
        assertEquals(List.of(ClickEvent.Action.OPEN_URL + " " + DocsLink.PERMISSIONS.url()), clicks(heard.lines.getFirst()));
    }

    static List<String> clicks(BaseComponent[] line) {
        List<String> clicks = new ArrayList<>();
        for (BaseComponent piece : line) clicks(piece, clicks);
        return clicks;
    }

    private static void clicks(BaseComponent piece, List<String> out) {
        if (piece.getClickEvent() != null) out.add(piece.getClickEvent().getAction() + " " + piece.getClickEvent().getValue());
        if (piece.getExtra() != null) for (BaseComponent child : piece.getExtra()) clicks(child, out);
    }

    /** A player that keeps every String and every component line it is sent. */
    static final class Heard {
        final List<String> strings = new ArrayList<>();
        final List<BaseComponent[]> lines = new ArrayList<>();
        private final UUID id = UUID.randomUUID();

        Player player() {
            Player.Spigot spigot = new Player.Spigot() {
                @Override public void sendMessage(BaseComponent component) { lines.add(new BaseComponent[]{component}); }
                @Override public void sendMessage(BaseComponent... components) { lines.add(components); }
            };
            return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (Object proxy, java.lang.reflect.Method method, Object[] arguments) -> switch (method.getName()) {
                case "sendMessage", "sendRawMessage" -> {
                    for (Object argument : arguments) if (argument instanceof String text) strings.add(text);
                    else if (argument instanceof String[] texts) strings.addAll(List.of(texts));
                    yield null;
                }
                case "spigot" -> spigot;
                case "getUniqueId" -> id;
                case "equals" -> proxy == arguments[0];
                case "hashCode" -> id.hashCode();
                case "toString" -> "player " + id;
                default -> throw new UnsupportedOperationException(method.getName());
            });
        }
    }
}
