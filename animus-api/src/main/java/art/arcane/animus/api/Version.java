package art.arcane.animus.api;

/**
 * Numeric release version. Pre-release selection belongs to the module loader, not this comparison.
 *
 * @param major major version
 * @param minor minor version
 * @param patch patch version
 */
public record Version(int major, int minor, int patch) implements Comparable<Version> {
    public Version { if(major<0 || minor<0 || patch<0)throw new IllegalArgumentException("Negative version component"); }
    @Override public int compareTo(Version other) {
        int comparison=Integer.compare(major,other.major);
        if(comparison==0)comparison=Integer.compare(minor,other.minor);
        return comparison==0?Integer.compare(patch,other.patch):comparison;
    }
    @Override public String toString(){return major+"."+minor+"."+patch;}
}
