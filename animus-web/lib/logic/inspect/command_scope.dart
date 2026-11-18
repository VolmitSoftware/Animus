/// Which behavior steps the server lets the editor apply only with the Run
/// commands permission (`apply:commands`), as `EditorCommandGuard` decides:
/// a privileged capability such as `animus:command`, `animus:give-item`,
/// and quest steps that can pay rewards. A behavior that shares flags with a
/// raw-text behavior needs it too; only the server knows those.
library;

import '../gui/gui_json.dart';
import 'completion_catalog.dart';

/// The capabilities the catalog marks privileged, always with `animus:command`.
Set<String> privilegedCapabilities(CompletionCatalog catalog) => <String>{
      'animus:command',
      for (final CapabilitySpec spec in catalog.capabilities)
        if (spec.privileged) spec.id,
    };

/// Why applying step [node] needs Run commands, or null.
String? stepNeedsCommands(JsonMap node, Set<String> privileged) {
  final String? capability = asString(node['capability']);
  if (capability == null) return null;
  if (privileged.contains(capability)) return 'runs a command';
  if (capability == 'animus:give-item') return 'gives items';
  final bool pays = switch (capability) {
    'animus:advance-quest' => true,
    'animus:complete-quest' => !_off(node, 'rewards', absent: false),
    'animus:start-quest' => !_off(node, 'force', absent: true),
    _ => false,
  };
  return pays ? "can pay a quest's rewards" : null;
}

/// Why applying behavior [json] needs Run commands, or null: the first step
/// that does, depth first.
String? behaviorNeedsCommands(Object? json, Set<String> privileged) {
  String? found;
  void visit(Object? value) {
    if (found != null) return;
    if (value is Map<String, Object?>) {
      if (value['capability'] is String) found = stepNeedsCommands(value, privileged);
      value.values.forEach(visit);
    } else if (value is List<Object?>) {
      value.forEach(visit);
    }
  }

  visit(asMap(json)['routines']);
  return found;
}

/// Whether boolean argument [key] is surely false: written false, or left out
/// when [absent] means false, and not filled when the step runs.
bool _off(JsonMap node, String key, {required bool absent}) {
  final String pointer = '/$key';
  for (final String field in const <String>['bindings', 'expressions', 'templates']) {
    if (asMap(node[field]).containsKey(pointer)) return false;
  }
  final JsonMap arguments = asMap(node['arguments']);
  if (!arguments.containsKey(key)) return absent;
  return arguments[key] == false;
}
