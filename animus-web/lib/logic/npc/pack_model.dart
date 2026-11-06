/// Edits a behavior pack manifest's `modules` map (`packs/<pack>/pack.json`):
/// Java modules the pack uses, each with `required` and its own
/// `configuration`, as `PackManifest` reads them.
library;

import '../gui/gui_json.dart';

/// Module names as the pack schema accepts them.
final RegExp packModuleName = RegExp(r'^[a-z0-9][a-z0-9_-]{0,63}$');

/// The problem with naming a module [name] next to [taken], or null.
String? packModuleNameProblem(String name, List<String> taken) {
  if (name.isEmpty) return 'Name the module.';
  if (name == 'animus') return 'animus is built in; name a Java module the pack needs.';
  if (!packModuleName.hasMatch(name)) return 'Use 1-64 lowercase letters, digits, _ or -, starting with a letter or digit.';
  if (taken.contains(name)) return 'The pack already lists $name.';
  return null;
}

/// [pack] with module [name] set to [module], or removed when null; an
/// emptied map is removed.
JsonMap putPackModule(JsonMap pack, String name, JsonMap? module) {
  final JsonMap modules = <String, Object?>{...asMap(pack['modules'])};
  if (module == null) {
    modules.remove(name);
  } else {
    modules[name] = module;
  }
  final JsonMap next = <String, Object?>{...pack};
  if (modules.isEmpty) {
    next.remove('modules');
  } else {
    next['modules'] = modules;
  }
  return next;
}

/// [pack] with module [from] renamed [to] in place; unchanged when [to] is
/// not a valid, free name.
JsonMap renamePackModule(JsonMap pack, String from, String to) {
  final JsonMap modules = asMap(pack['modules']);
  final List<String> others = <String>[for (final String key in modules.keys) if (key != from) key];
  if (!modules.containsKey(from) || packModuleNameProblem(to, others) != null) {
    return pack;
  }
  return <String, Object?>{
    ...pack,
    'modules': <String, Object?>{for (final MapEntry<String, Object?> entry in modules.entries) (entry.key == from ? to : entry.key): entry.value},
  };
}
