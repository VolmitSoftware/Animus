package art.arcane.animus.core.command;

import art.arcane.animus.core.migration.CitizensDropIn;
import art.arcane.animus.core.onboarding.SetupGuide;
import art.arcane.animus.core.onboarding.SetupState;
import art.arcane.animus.core.text.ChatLine;
import art.arcane.animus.core.text.RichText;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SetupCommandsTest {
    private static final SetupState FRESH = new SetupState(0, new CitizensDropIn.Progress(CitizensDropIn.Phase.FOUND, Optional.of("plugins/Citizens/saves.yml"), 0), true, 0);

    @Test void aPlayerGetsTheClickableStepsInSixLinesOrFewer() {
        List<Object> sent = new ArrayList<>();
        SetupCommands.reply(actor(UUID.randomUUID(), sent), FRESH);
        assertTrue(sent.size() <= SetupGuide.MAXIMUM_LINES, sent.toString());
        assertTrue(sent.stream().allMatch(ChatLine.class::isInstance));
    }

    @Test void theConsoleGetsThePlainForm() {
        List<Object> sent = new ArrayList<>();
        SetupCommands.reply(actor(null, sent), FRESH);
        assertEquals(SetupGuide.console(FRESH), sent);
    }

    @Test void setupIsARootCommandUnderTheToolsNode() {
        AnimusDirector real = new AnimusDirector(new AnimusCommandTree(null), (String parameter, CommandActor actor) -> List.of());
        DirectorRuntimeNode setup = real.engine().getRoot().getChildren().stream().filter((DirectorRuntimeNode node) -> node.getDescriptor().getName().equals("setup")).findFirst().orElseThrow();
        assertTrue(setup.isInvocable(), "/animus setup runs without a subcommand");
        assertEquals(PermissionNodes.TOOLS, AnimusDirector.permission(setup));
    }

    private static CommandActor actor(UUID player, List<Object> sent) {
        return new CommandActor() {
            @Override public boolean permitted(String node) { return true; }
            @Override public void tell(String message) { sent.add(RichText.plain(message).replaceFirst("^\\[Animus\\]: ", "")); }
            @Override public void plain(String line) { sent.add(RichText.plain(line)); }
            @Override public void rich(ChatLine line) { sent.add(line); }
            @Override public Optional<UUID> player() { return Optional.ofNullable(player); }
            @Override public boolean reply(Runnable action) { action.run(); return true; }
        };
    }
}
