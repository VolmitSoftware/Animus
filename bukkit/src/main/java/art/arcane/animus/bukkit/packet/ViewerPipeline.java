package art.arcane.animus.bukkit.packet;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import java.util.Arrays;
import java.util.UUID;
import java.util.function.IntPredicate;
import java.util.function.Predicate;

/**
 * The one Animus handler on a viewer's connection, just before vanilla's packet_handler. Each transport watching the
 * viewer adds a tap, and the handler leaves the pipeline with the last one. Attach and detach on the channel's event loop.
 */
final class ViewerPipeline extends ChannelDuplexHandler {
    /** Unique per plugin load, so a handler an earlier load left behind never collides with this one. */
    static final String NAME = "animus_" + UUID.randomUUID().toString().replace("-", "");

    /** One transport's view of the connection; called on its event loop. */
    interface Tap {
        /** True when this tap took the inbound message, which then goes no further. */
        boolean read(Object message) throws Exception;
        /** The outbound message to send in place of {@code message}. */
        default Object write(Object message) { return message; }
        void closed();
    }

    /** Parallel arrays in attach order; inbound messages reach the taps in this order. */
    private Object[] owners = {};
    private Tap[] taps = {};

    /** Adds {@code owner}'s tap, replacing its earlier one; the first tap installs the handler. */
    static void attach(Channel channel, Object owner, Tap tap) {
        ChannelPipeline pipeline = channel.pipeline();
        ViewerPipeline handler = pipeline.get(NAME) instanceof ViewerPipeline installed ? installed : null;
        if (handler == null) {
            if (pipeline.get("packet_handler") == null) throw new IllegalStateException("Vanilla packet_handler is unavailable");
            handler = new ViewerPipeline();
            pipeline.addBefore("packet_handler", NAME, handler);
        }
        ViewerPipeline installed = handler;
        installed.keep((int index) -> installed.owners[index] != owner, owner, tap);
    }

    /** Removes the taps {@code tap} matches; the handler leaves with the last one. */
    static void detach(Channel channel, Predicate<Tap> tap) {
        if (!(channel.pipeline().get(NAME) instanceof ViewerPipeline handler)) return;
        handler.keep((int index) -> !tap.test(handler.taps[index]), null, null);
        if (handler.taps.length == 0) channel.pipeline().remove(handler);
    }

    /** Keeps the taps {@code kept} accepts, then appends {@code tap} when one is given. */
    private void keep(IntPredicate kept, Object owner, Tap tap) {
        int size = 0;
        Object[] nextOwners = new Object[taps.length + 1];
        Tap[] nextTaps = new Tap[taps.length + 1];
        for (int index = 0; index < taps.length; index++)
            if (kept.test(index)) { nextOwners[size] = owners[index]; nextTaps[size++] = taps[index]; }
        if (tap != null) { nextOwners[size] = owner; nextTaps[size++] = tap; }
        owners = Arrays.copyOf(nextOwners, size);
        taps = Arrays.copyOf(nextTaps, size);
    }

    @Override public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
        for (Tap tap : taps) if (tap.read(message)) return;
        context.fireChannelRead(message);
    }

    @Override public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
        Object sent = message;
        for (Tap tap : taps) sent = tap.write(sent);
        context.write(sent, promise);
    }

    @Override public void channelInactive(ChannelHandlerContext context) throws Exception {
        for (Tap tap : taps) tap.closed();
        context.fireChannelInactive();
    }
}
