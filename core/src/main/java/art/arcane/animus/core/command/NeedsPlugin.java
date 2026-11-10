package art.arcane.animus.core.command;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The server plugin a Director command or group works with, such as {@code ModelEngine}. While that plugin is not
 * running, help, completion and the client command tree leave the node out, and typing it anyway answers that it needs
 * the plugin.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface NeedsPlugin {
    String value();
}
