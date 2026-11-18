package art.arcane.animus.bukkit;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * CraftBukkit and BungeeCord read a dotted word in legacy text, such as {@code config.toml}, as a web link. Every class
 * but {@link BukkitChat} hands chat text to the server as components; only the console, which shows no links, gets a String.
 */
class ChatTextSinksTest {
    private static final String CHAT = "art/arcane/animus/bukkit/BukkitChat";
    private static final Set<String> LEGACY_PARSERS = Set.of("fromLegacyText", "fromLegacy");
    private static final Set<String> BOOK_PAGES = Set.of("setPages", "setPage", "addPage");

    @Test void chatTextReachesPlayersOnlyAsComponents() throws IOException, URISyntaxException {
        Path classes = Path.of(BukkitActor.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Set<String> found = new TreeSet<>();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(classes)) { files = walk.filter((Path file) -> file.toString().endsWith(".class")).toList(); }
        for (Path file : files) {
            ClassModel model = ClassFile.of().parse(Files.readAllBytes(file));
            String owner = model.thisClass().asInternalName();
            if (owner.equals(CHAT)) continue;
            for (MethodModel method : model.methods()) {
                if (method.code().isEmpty()) continue;
                for (CodeElement element : method.code().get()) {
                    if (element instanceof InvokeInstruction invoke && linking(invoke))
                        found.add(owner + "." + method.methodName().stringValue() + " calls " + invoke.owner().asInternalName() + "." + invoke.name().stringValue());
                }
            }
        }
        assertTrue(files.size() > 100, "no plugin classes under " + classes);
        assertEquals(Set.of(), found);
    }

    private static boolean linking(InvokeInstruction invoke) {
        String owner = invoke.owner().asInternalName(), name = invoke.name().stringValue(), type = invoke.typeSymbol().descriptorString();
        if (owner.equals("net/md_5/bungee/api/chat/TextComponent") && LEGACY_PARSERS.contains(name)) return true;
        if (owner.equals("org/bukkit/inventory/meta/BookMeta") && BOOK_PAGES.contains(name)) return true;
        if (!owner.startsWith("org/bukkit/") || owner.equals("org/bukkit/command/ConsoleCommandSender")) return false;
        if (name.equals("broadcastMessage")) return true;
        return (name.equals("sendMessage") || name.equals("sendRawMessage")) && type.contains("Ljava/lang/String;");
    }
}
