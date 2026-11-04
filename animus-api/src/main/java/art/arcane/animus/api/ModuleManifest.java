package art.arcane.animus.api;

import java.util.*;

/** Trusted Java module contract. This declaration does not sandbox or authenticate code. */
public record ModuleManifest(String id,Version version,ApiRange api,Set<Platform> platforms,List<Dependency> dependencies) {
    public record Dependency(String id,Version minimumInclusive,Version maximumExclusive) {
        public Dependency {ApiText.id(id);Objects.requireNonNull(minimumInclusive);Objects.requireNonNull(maximumExclusive);if(minimumInclusive.compareTo(maximumExclusive)>=0)throw new IllegalArgumentException("Empty dependency version range");}
        public boolean accepts(Version version){return version.compareTo(minimumInclusive)>=0 && version.compareTo(maximumExclusive)<0;}
    }
    public ModuleManifest {
        ApiText.id(id);Objects.requireNonNull(version);Objects.requireNonNull(api);platforms=Set.copyOf(platforms);dependencies=List.copyOf(dependencies);
        if(platforms.isEmpty() || dependencies.size()>32 || dependencies.stream().map(Dependency::id).distinct().count()!=dependencies.size() || dependencies.stream().anyMatch(d->d.id().equals(id)))throw new IllegalArgumentException("Invalid module platform/dependencies");
    }
}
