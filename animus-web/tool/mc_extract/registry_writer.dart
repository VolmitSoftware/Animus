/// Writes `registries.json`: the ids the editor completes offline, read from
/// the client jar. Items, blocks and particles come from their asset folders,
/// enchantments from the data folder, entity types, potion effects and
/// attributes from the English lang file. Sound events come from the
/// subtitle keys, which name most of them; a paired server lists them all.
library;

import 'dart:convert';
import 'dart:io';

import 'jar_assets.dart';

class RegistryWriter {
  const RegistryWriter(this.jar);

  final JarAssets jar;

  Map<String, List<String>> read() {
    final Map<String, Object?> lang = _lang();
    List<String> files(String prefix) => <String>{
          for (final String path in jar.paths(prefix, '.json')) path.substring(prefix.length, path.length - '.json'.length),
        }.where((String id) => !id.contains('/')).toList()
          ..sort();
    List<String> keys(String prefix, {bool flat = true}) => <String>{
          for (final String key in lang.keys)
            if (key.startsWith(prefix) && (!flat || !key.substring(prefix.length).contains('.'))) key.substring(prefix.length),
        }.toList()
          ..sort();
    return <String, List<String>>{
      'items': files('assets/minecraft/items/'),
      'blocks': files('assets/minecraft/blockstates/'),
      'particles': files('assets/minecraft/particles/'),
      'enchantments': files('data/minecraft/enchantment/'),
      'entityTypes': keys('entity.minecraft.'),
      'effects': keys('effect.minecraft.'),
      'attributes': keys('attribute.name.'),
      'sounds': keys('subtitles.', flat: false),
    };
  }

  Map<String, Object?> _lang() {
    final String? text = jar.text('assets/minecraft/lang/en_us.json');
    if (text == null) return const <String, Object?>{};
    return jsonDecode(text) as Map<String, Object?>;
  }

  void write(Directory output, String version) {
    output.createSync(recursive: true);
    File('${output.path}/registries.json').writeAsStringSync(jsonEncode(<String, Object?>{'version': version, 'registries': read()}));
  }
}
