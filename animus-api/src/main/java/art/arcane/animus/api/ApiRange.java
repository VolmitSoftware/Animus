package art.arcane.animus.api;

/**
 * Supported API versions, declared by the trusted module author: every major from {@code minimumMajor} to
 * {@code maximumMajor}, and on {@code minimumMajor} only minors from {@code minimumMinor} up.
 *
 * @param minimumMajor lowest supported API major version
 * @param maximumMajor highest supported API major version
 * @param minimumMinor lowest supported minor of {@code minimumMajor}; 0 accepts all of them. @since 1.2
 */
public record ApiRange(int minimumMajor, int maximumMajor, int minimumMinor) {
    public ApiRange { if(minimumMajor<1 || maximumMajor<minimumMajor || minimumMinor<0)throw new IllegalArgumentException("Invalid API range"); }
    /**
     * Every minor of the majors from {@code minimumMajor} to {@code maximumMajor}.
     *
     * @param minimumMajor lowest supported API major version
     * @param maximumMajor highest supported API major version
     */
    public ApiRange(int minimumMajor, int maximumMajor) { this(minimumMajor, maximumMajor, 0); }
    /**
     * Needs {@code version} or a later minor of the same major, such as 1.2 for a module that calls a 1.2 addition.
     *
     * @param version lowest API version the module needs; the patch is ignored
     * @return the range
     * @since 1.2
     */
    public static ApiRange atLeast(Version version){return new ApiRange(version.major(),version.major(),version.minor());}
    public boolean accepts(Version version){
        return version.major()>=minimumMajor && version.major()<=maximumMajor && (version.major()>minimumMajor || version.minor()>=minimumMinor);
    }
    /**
     * Plain text for refusals, such as {@code requires API 1.2 or newer within 1.x}.
     *
     * @return the description
     * @since 1.2
     */
    public String describe(){
        String majors=minimumMajor==maximumMajor?minimumMajor+".x":minimumMajor+".x to "+maximumMajor+".x";
        return minimumMinor==0?"requires API "+majors:"requires API "+minimumMajor+"."+minimumMinor+" or newer within "+majors;
    }
}
