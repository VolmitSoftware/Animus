package art.arcane.animus.core.mount;

import java.util.Objects;
import java.util.UUID;

/**
 * One persisted rider-to-vehicle relation. {@code vehicleUuid} is present for imported references, whose
 * identity was checked at import time; a native reference names only the vehicle's stable id.
 */
public record MountReference(String field, String vehicleId, UUID vehicleUuid, Status status, String original) {
    public enum Status { DECLARED, MISSING, AMBIGUOUS, INVALID }
    public MountReference {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(original, "original");
        if (status == Status.DECLARED && vehicleId == null) throw new IllegalArgumentException("A declared mount names its vehicle");
    }
}
