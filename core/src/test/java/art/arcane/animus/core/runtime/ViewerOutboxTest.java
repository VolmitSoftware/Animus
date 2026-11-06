package art.arcane.animus.core.runtime;

import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewerOutboxTest {
    @Test
    void cosmeticUpdatesAreRefusedFirstAsTheQueueFills() {
        ViewerOutbox outbox = new ViewerOutbox();
        int capacity = 8;
        for (int i = 0; i < 6; i++) assertTrue(outbox.admit(PresentationPressure.Tier.COSMETIC, capacity), "cosmetic " + i);
        assertFalse(outbox.admit(PresentationPressure.Tier.COSMETIC, capacity), "cosmetic work keeps a quarter of the queue free");
        assertTrue(outbox.admit(PresentationPressure.Tier.AUTHORITATIVE, capacity));
        assertTrue(outbox.admit(PresentationPressure.Tier.AUTHORITATIVE, capacity));
        assertFalse(outbox.admit(PresentationPressure.Tier.AUTHORITATIVE, capacity), "the queue is full");
        assertTrue(outbox.admit(PresentationPressure.Tier.DESPAWN, capacity), "a despawn is never refused");
        assertEquals(9, outbox.pending());
        assertEquals(2, outbox.refused());
    }

    @Test
    void aFinishedSendFreesItsPlaceWhicheverWayItEnds() {
        ViewerOutbox outbox = new ViewerOutbox();
        CompletableFuture<Void> ok = new CompletableFuture<>(), failed = new CompletableFuture<>();
        assertTrue(outbox.admit(PresentationPressure.Tier.AUTHORITATIVE, 4));
        outbox.track(ok);
        assertTrue(outbox.admit(PresentationPressure.Tier.AUTHORITATIVE, 4));
        outbox.track(failed);
        assertEquals(2, outbox.pending());
        ok.complete(null);
        failed.completeExceptionally(new IllegalStateException("closed"));
        assertEquals(0, outbox.pending());
    }

    @Test
    void aSendThatThrowsBeforeItIsQueuedGivesItsPlaceBack() {
        ViewerOutbox outbox = new ViewerOutbox();
        assertTrue(outbox.admit(PresentationPressure.Tier.AUTHORITATIVE, 4));
        outbox.abandon();
        assertEquals(0, outbox.pending());
        assertThrows(IllegalStateException.class, outbox::abandon);
    }

    @Test
    void aLoweredCapacityHoldsBackNewWorkOnly() {
        ViewerOutbox outbox = new ViewerOutbox();
        for (int i = 0; i < 6; i++) assertTrue(outbox.admit(PresentationPressure.Tier.AUTHORITATIVE, 8));
        assertFalse(outbox.admit(PresentationPressure.Tier.AUTHORITATIVE, 4));
        assertEquals(6, outbox.pending());
    }
}
