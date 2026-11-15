# animus-bukkit-api

The Bukkit side of the Animus API: the `AnimusNpcs` service Bukkit's services manager holds while Animus runs (`art.arcane.animus.bukkit.api`), conversions between Bukkit types and the API's own (`BukkitNpcs`), and the NPC events (`art.arcane.animus.bukkit.event`). It builds on `animus-api` and shares its version, 1.3.0.

## Depending on it

Through JitPack, with an API tag such as `api-1.3.0` or a commit hash:

```groovy
repositories {
    maven { url = 'https://jitpack.io' }
}
dependencies {
    compileOnly 'com.github.VolmitSoftware.Animus:animus-api:api-1.3.0'
    compileOnly 'com.github.VolmitSoftware.Animus:animus-bukkit-api:api-1.3.0'
}
```

Or build both locally with `./gradlew :animus-api:publishToMavenLocal :animus-bukkit-api:publishToMavenLocal`, which writes `art.arcane.animus:animus-api:1.3.0-SNAPSHOT` and `art.arcane.animus:animus-bukkit-api:1.3.0-SNAPSHOT`.

Declare Animus in `plugin.yml` with `depend: [Animus]` (or `softdepend` and check that the service is there), and leave the API classes out of your jar: the Animus plugin ships them.

```java
NpcService npcs = getServer().getServicesManager().load(AnimusNpcs.class).service(this);
```

The [example plugin](https://github.com/VolmitSoftware/Animus/tree/master/examples/plugins/npc-demo) uses only these two artifacts. Documentation: [Java modules and API](https://github.com/VolmitSoftware/docs/blob/master/animus/99-java-modules-api.md).
