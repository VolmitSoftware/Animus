# Reference Java module

This external example compiles against **only `animus-api` and Java 25**. It declares API major 1 support on Spigot, Paper and Folia and registers the nonprivileged `reference:hello` action. The action returns a JSON greeting; it does not access Minecraft state, execute a command, or provide a behavior interpreter.

From the repository root:

```sh
JAVA_HOME=/path/to/java25 ./gradlew :animus-api:jar
python3 examples/modules/reference/build.py --java-home /path/to/java25
```

The builder writes `build/animus-reference-1.0.0.jar`, generated `animus-module.json`, and a JSON build receipt with exact API/source/artifact hashes. Pass `--api-jar` to compile against an explicitly selected standalone API artifact. The generated manifest must agree exactly with `ReferenceModule.manifest()`; mismatches are rejected before enable.

The module JAR belongs in Animus's `modules/` directory when using a release with the reviewed module runtime. It is not a Bukkit plugin and has no `plugin.yml`. Java binary installation, removal or replacement requires a restart; file/pack reload does not reload Java classes. The loader preserves unknown module configuration and reports incompatible or missing dependencies without deleting that configuration.

Java modules are trusted code with JVM/process access. The classloader preserves public API identity and limits ordinary access to engine implementation classes; it is not a security sandbox. Do not block lifecycle callbacks or result continuations. Acquire resources during enable through `ModuleContext.onClose` for reverse cleanup. Use the bounded module scheduler and asynchronous definition service, and do not infer platform thread ownership from those APIs.

This builder does not deploy or test the module on a server. Runtime evidence and limitations are tracked separately in the managed QA reports.
