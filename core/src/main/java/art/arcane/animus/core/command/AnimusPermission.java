package art.arcane.animus.core.command;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The permission node a Director command or group needs. The nearest annotation wins: a method's own node, else its
 * group's, else the parent group's. An empty node is open to everyone.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface AnimusPermission {
    String value();
}
