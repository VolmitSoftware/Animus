package art.arcane.animus.core.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.Platform;
import art.arcane.animus.core.RuntimeEnvironment;
import art.arcane.animus.core.command.AnimusDirector;
import art.arcane.animus.core.command.CommandActor;
import art.arcane.animus.core.gui.MockScreenHost.Player;
import art.arcane.animus.core.inventory.MockInventory.Stack;
import art.arcane.animus.core.shop.ShopService;
import art.arcane.animus.core.text.RichText;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code /animus shop reset <shop> player=Steve} answers with the name given, not the player's UUID. */
class ShopResetReplyTest {
    @TempDir Path directory;

    @Test void theReplyNamesThePlayerAsGiven() throws Exception {
        AnimusRuntime runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.BUKKIT, "26.2", directory));
        runtime.start();
        UUID steve = UUID.randomUUID();
        ShopService<Player, Stack> shops = new ShopService<>(runtime, new MockScreenHost(), new FakeNpcRuntime<Player>((UUID id) -> Optional.empty()), Runnable::run,
                (String name) -> name.equals("Steve") ? Optional.of(steve) : Optional.empty(), (String line) -> {});
        List<String> lines = new ArrayList<>();
        CommandActor console = new CommandActor() {
            @Override public boolean permitted(String node) { return node.equals(AnimusDirector.ADMIN); }
            @Override public void tell(String message) { lines.add(RichText.plain(message)); }
            @Override public Optional<UUID> player() { return Optional.empty(); }
            @Override public boolean reply(Runnable action) { action.run(); return true; }
        };
        try {
            shops.reset(console, "town:baker", Optional.of("Steve"));
            assertEquals("[Animus]: Cleared limits and stock of town:baker for Steve", lines.getLast());
        } finally { shops.close(); runtime.closeAsync(); }
    }
}
