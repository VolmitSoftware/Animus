package art.arcane.animus.api;

import java.util.*;
import java.util.concurrent.CompletionStage;

/** Actions, conditions and triggers a module adds to JSON behaviors, and how it registers them. */
public final class Capabilities {
    private Capabilities() {}
    /** What a capability is: an action that does something, a condition that answers true or false, or a trigger that starts behaviors. */
    public enum Kind { ACTION, CONDITION, TRIGGER }
    /**
     * A capability's name, {@code namespace:name}; the namespace is the module id.
     *
     * @param namespace the module id
     * @param name the capability name within the namespace
     */
    public record Key(String namespace,String name) {
        public Key {ApiText.id(namespace);ApiText.id(name);}
        @Override public String toString(){return namespace+":"+name;}
    }
    /**
     * One capability as a module declares it: key, kind, description, argument schema, platforms and whether it needs privilege.
     *
     * @param key capability key
     * @param kind action, condition or trigger
     * @param description what it does, for people
     * @param configurationSchemaJson JSON schema of its arguments
     * @param platforms platforms it runs on
     * @param privileged whether calling it needs the command allow-list
     */
    public record Descriptor(Key key,Kind kind,String description,String configurationSchemaJson,Set<Platform> platforms,boolean privileged) {
        public Descriptor {Objects.requireNonNull(key);Objects.requireNonNull(kind);ApiText.text(description,2048,"description");ApiText.text(configurationSchemaJson,65536,"schema JSON");platforms=Set.copyOf(platforms);if(platforms.isEmpty())throw new IllegalArgumentException("No supported platforms");}
    }
    /**
     * Whether a registered capability can run now, and why not.
     *
     * @param descriptor the capability
     * @param available whether it can run now
     * @param reason why not, or a short status
     */
    public record Availability(Descriptor descriptor,boolean available,String reason) {
        public Availability {Objects.requireNonNull(descriptor);ApiText.text(reason,2048,"reason");}
    }
    /**
     * {@code behavior} names the accepted JSON behavior making this call; it is empty for direct dispatch.
     *
     * @param npc NPC the call applies to
     * @param player player the call applies to, if any
     * @param argumentsJson capability arguments as a JSON object
     * @param preview true when the call only previews its effect
     * @param cancellation signal that stops the call early
     * @param behavior behavior that requested the call
     */
    public record Invocation(NpcHandle npc,Optional<UUID> player,String argumentsJson,boolean preview,Cancellation cancellation,Optional<String> behavior) {
        public Invocation {Objects.requireNonNull(npc);player=Objects.requireNonNull(player);ApiText.text(argumentsJson,65536,"arguments JSON");Objects.requireNonNull(cancellation);behavior=Objects.requireNonNull(behavior);}
    }
    /** Asked by long work: true once the call should stop early. */
    @FunctionalInterface public interface Cancellation {boolean cancelled();}
    /** What an action does when a behavior or command calls it; completes with a JSON object. */
    @FunctionalInterface public interface Action {CompletionStage<String> run(Invocation invocation) throws Exception;}
    /** What a condition answers when a behavior asks it. */
    @FunctionalInterface public interface Condition {CompletionStage<Boolean> test(Invocation invocation) throws Exception;}
    /**
     * Fires a trigger this module registered. Thread-safe and nonblocking: the result says whether the
     * event was queued. OK(false) means the NPC is dormant or has no bound triggers. Overflow returns BUSY
     * and is dropped. Other statuses carry a diagnostic. Publishing never grants world authority.
     */
    @FunctionalInterface public interface TriggerPublisher {Outcome<Boolean> publish(Key trigger,NpcHandle npc,Optional<UUID> player);}
    /** Registers capabilities during enable. */
    public interface Registrar {
        void action(Descriptor descriptor,Action action);
        void condition(Descriptor descriptor,Condition condition);
        void trigger(Descriptor descriptor);
    }
}
