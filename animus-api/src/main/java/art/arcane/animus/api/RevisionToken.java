package art.arcane.animus.api;

/**
 * Opaque accepted-source token. The snapshot JSON may be normalized and have different bytes.
 *
 * @param revision revision number
 * @param sourceSha256 SHA-256 of the source text
 */
public record RevisionToken(long revision, String sourceSha256) {
    public RevisionToken {
        if(revision<1 || sourceSha256==null || !sourceSha256.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid revision token");
    }
}
