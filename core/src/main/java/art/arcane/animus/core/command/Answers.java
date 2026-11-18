package art.arcane.animus.core.command;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A hidden command a chat button runs to answer a question. While such a question waits for a player, help and
 * completion offer the command to them as they do any other, so it can be typed; with nothing waiting it stays out of
 * both, and running it says nothing waits.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@interface Answers {
    Question value();

    enum Question {
        /** A conversation's {@code animus:choices} prompt. */
        CONVERSATION("No question is waiting for you"),
        /** A quest offer's Accept and Decline. */
        QUEST_OFFER("No quest offer is waiting for you");

        /** The reply to the command while no such question waits. */
        final String none;

        Question(String none) { this.none = none; }
    }
}
