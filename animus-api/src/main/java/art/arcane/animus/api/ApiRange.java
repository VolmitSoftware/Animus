package art.arcane.animus.api;

/**
 * Inclusive supported API major range, declared by the trusted module author.
 *
 * @param minimumMajor lowest supported API major version
 * @param maximumMajor highest supported API major version
 */
public record ApiRange(int minimumMajor, int maximumMajor) {
    public ApiRange { if(minimumMajor<1 || maximumMajor<minimumMajor)throw new IllegalArgumentException("Invalid API major range"); }
    public boolean accepts(Version version){return version.major()>=minimumMajor && version.major()<=maximumMajor;}
}
