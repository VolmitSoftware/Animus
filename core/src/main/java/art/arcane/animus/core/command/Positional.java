package art.arcane.animus.core.command;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Optional parameters a command also takes as bare words, in order, after its required ones: {@code /animus select guard}
 * as well as {@code npc=guard}. Only commands whose next value is plain from the words alone carry it.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Positional {
    String[] value();
}
