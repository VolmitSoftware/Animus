# animus-api

The platform-neutral API of Animus, package `art.arcane.animus.api`: the NPC service (`NpcService`) that creates, edits, removes and drives NPCs, definition modules (`NpcModules`), definitions, and the contracts trusted Java modules implement (`TrustedModule`, capabilities, import converters, integration hooks). Java 25, no other dependencies. API version 1.3.0.

## Depending on it

Through JitPack, with an API tag such as `api-1.3.0` or a commit hash:

```groovy
repositories {
    maven { url = 'https://jitpack.io' }
}
dependencies {
    compileOnly 'com.github.VolmitSoftware.Animus:animus-api:api-1.3.0'
}
```

Or build it locally with `./gradlew :animus-api:publishToMavenLocal`, which writes `art.arcane.animus:animus-api:1.3.0-SNAPSHOT` to `~/.m2/repository`.

The classes ship inside the Animus plugin and mod jars, so keep them out of your own jar. A Java module gets its service with `context.npcs()`; a Bukkit plugin also needs `animus-bukkit-api` and gets it through `AnimusNpcs`.

Documentation: [Java modules and API](https://github.com/VolmitSoftware/docs/blob/master/animus/99-java-modules-api.md).
