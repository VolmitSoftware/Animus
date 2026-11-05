/// Extracts the textures and model JSON the NPC preview draws from a client
/// jar into `web/assets/mc/<version>/`.
///
/// Usage:
///   dart run tool/extract_minecraft_world_assets.dart \
///     --jar=/path/to/minecraft-26.3-client.jar --version=26.3 [--output=web/assets/mc]
///
/// The output is Mojang's art and is never committed: `web/assets/mc/` is
/// gitignored and each checkout or CI run generates it before `jaspr build`.
/// Offline: only the jar is read, and its classes are never loaded. Re-run
/// whenever the jar version changes; the folder is versioned.
library;

import 'dart:io';

import 'package:animus_editor/mc/rigs/mc_rigs.dart';
import 'package:crypto/crypto.dart';

import 'mc_extract/jar_assets.dart';
import 'mc_extract/pack_writer.dart';

Future<void> main(List<String> arguments) async {
  final Map<String, String> options = _options(arguments);
  final File jarFile = File(_required(options, 'jar'));
  final String version = _required(options, 'version');
  final Directory output = Directory('${options['output'] ?? 'web/assets/mc'}/$version');
  if (!jarFile.existsSync()) {
    stderr.writeln('No jar at ${jarFile.path}');
    exitCode = 66;
    return;
  }
  final String sha = sha256.convert(jarFile.readAsBytesSync()).toString();
  final JarAssets jar = JarAssets.fromFile(jarFile);
  await PackWriter(
    jar: jar,
    version: version,
    jarSha256: sha,
    generatedAt: DateTime.now().toUtc().toIso8601String(),
    entityTextures: mcEntityTextureIds,
  ).write(output);
  stdout.writeln('Wrote ${output.path}');
}

Map<String, String> _options(List<String> arguments) {
  final Map<String, String> options = <String, String>{};
  for (final String argument in arguments) {
    if (!argument.startsWith('--') || !argument.contains('=')) {
      throw ArgumentError('Expected --name=value, got $argument');
    }
    final int separator = argument.indexOf('=');
    options[argument.substring(2, separator)] = argument.substring(separator + 1);
  }
  return options;
}

String _required(Map<String, String> options, String key) {
  final String? value = options[key];
  if (value == null || value.isEmpty) throw ArgumentError('Missing --$key=value');
  return value;
}
