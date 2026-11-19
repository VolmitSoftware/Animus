package art.arcane.animus.bukkit.packet;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every transport watching a viewer shares one Animus handler on the connection, which leaves with the last of them. */
class ViewerPipelineTest {
    private static final class Vanilla extends ChannelInboundHandlerAdapter {
        final List<Object> read = new ArrayList<>();
        @Override public void channelRead(ChannelHandlerContext context, Object message) { read.add(message); }
    }

    /** Takes the inbound messages it names and renames the outbound ones it names. */
    private static final class Recording implements ViewerPipeline.Tap {
        final String claims, renames;
        final List<Object> taken = new ArrayList<>();
        int closed;
        Recording(String claims, String renames) { this.claims = claims; this.renames = renames; }
        @Override public boolean read(Object message) { if (!message.equals(claims)) return false; taken.add(message); return true; }
        @Override public Object write(Object message) { return message.equals(renames) ? renames + "'" : message; }
        @Override public void closed() { closed++; }
    }

    private static EmbeddedChannel connection(Vanilla vanilla) {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast("packet_handler", vanilla);
        return channel;
    }

    private static long animusHandlers(EmbeddedChannel channel) {
        return channel.pipeline().names().stream().filter((String name) -> name.startsWith("animus_")).count();
    }

    @Test void bodiesAndDisplaysShareOneHandlerBeforeVanilla() {
        Vanilla vanilla = new Vanilla();
        EmbeddedChannel channel = connection(vanilla);
        Recording bodies = new Recording("attack-body", "passengers"), displays = new Recording("click-display", "none");
        ViewerPipeline.attach(channel, "bodies", bodies);
        ViewerPipeline.attach(channel, "displays", displays);

        assertEquals(1, animusHandlers(channel));
        List<String> names = channel.pipeline().names();
        assertEquals(names.indexOf("packet_handler") - 1, names.indexOf(ViewerPipeline.NAME), "the handler sits right before vanilla's");

        channel.writeInbound("attack-body", "click-display", "move");
        assertEquals(List.of("attack-body"), bodies.taken);
        assertEquals(List.of("click-display"), displays.taken);
        assertEquals(List.of("move"), vanilla.read, "messages no transport takes reach vanilla");

        channel.writeOutbound("passengers", "chunk");
        assertEquals("passengers'", channel.readOutbound());
        assertEquals("chunk", channel.readOutbound());
    }

    @Test void aNewWatchReplacesTheTransportsEarlierTap() {
        EmbeddedChannel channel = connection(new Vanilla());
        Recording first = new Recording("attack-body", "x"), second = new Recording("attack-body", "x");
        ViewerPipeline.attach(channel, "bodies", first);
        ViewerPipeline.attach(channel, "bodies", second);

        assertEquals(1, animusHandlers(channel));
        channel.writeInbound("attack-body");
        assertTrue(first.taken.isEmpty());
        assertEquals(List.of("attack-body"), second.taken);
        ViewerPipeline.detach(channel, (ViewerPipeline.Tap tap) -> tap == first);
        assertEquals(1, animusHandlers(channel), "detaching a replaced tap leaves the current one in place");
    }

    @Test void theHandlerLeavesWithTheLastTap() {
        EmbeddedChannel channel = connection(new Vanilla());
        Recording bodies = new Recording("a", "b"), displays = new Recording("c", "d");
        ViewerPipeline.attach(channel, "bodies", bodies);
        ViewerPipeline.attach(channel, "displays", displays);

        ViewerPipeline.detach(channel, (ViewerPipeline.Tap tap) -> tap == bodies);
        assertEquals(1, animusHandlers(channel));
        ViewerPipeline.detach(channel, (ViewerPipeline.Tap tap) -> tap == displays);
        assertEquals(0, animusHandlers(channel));
        assertNull(channel.pipeline().get(ViewerPipeline.NAME));
        ViewerPipeline.detach(channel, (ViewerPipeline.Tap tap) -> true);
        assertEquals(0, animusHandlers(channel));
    }

    @Test void aClosedConnectionTellsEveryTap() {
        EmbeddedChannel channel = connection(new Vanilla());
        Recording bodies = new Recording("a", "b"), displays = new Recording("c", "d");
        ViewerPipeline.attach(channel, "bodies", bodies);
        ViewerPipeline.attach(channel, "displays", displays);

        assertFalse(channel.finish());
        assertEquals(1, bodies.closed);
        assertEquals(1, displays.closed);
    }
}
