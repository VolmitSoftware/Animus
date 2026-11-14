/// A behavior as plain sentences for Simple mode: each part (routine) with
/// what starts it and its steps as an indented outline. No ids, paths or
/// node type names; a condition's branches read "Yes:" and "No:".
library;

import '../../model/display_name.dart';
import '../gui/gui_json.dart';
import 'behavior_summary.dart';
import 'behavior_words.dart';

/// One line of the outline, [depth] levels in.
class OutlineLine {
  const OutlineLine(this.depth, this.text);

  final int depth;
  final String text;
}

/// One part of a behavior: its title, what runs it (null when the title
/// already says it) and its lines.
class OutlinePart {
  const OutlinePart(this.title, this.runs, this.lines);

  final String title;
  final String? runs;
  final List<OutlineLine> lines;
}

/// The entry part first ("What it does"), then every other part. A part
/// named after its event (an import's `on-delta-time-secondly-2`) is titled
/// by what starts it ("Every 2 seconds") instead of its name, and then an
/// entry part an event starts is too, so the parts read alike. [names]
/// names the documents steps open or play.
List<OutlinePart> behaviorOutline(JsonMap behavior, {DocumentNames names = idDocumentName}) {
  final JsonMap routines = asMap(behavior['routines']);
  final String entry = asString(behavior['entry']) ?? (routines.isEmpty ? 'main' : routines.keys.first);
  final JsonMap variables = asMap(behavior['variables']);
  final List<JsonMap> triggers = <JsonMap>[for (final Object? trigger in asList(behavior['triggers'])) asMap(trigger)];
  final List<String> parts = <String>[if (routines.containsKey(entry)) entry, for (final String name in routines.keys) if (name != entry) name];
  List<JsonMap> started(String name) => triggers.where((JsonMap trigger) => (asString(trigger['routine']) ?? entry) == name).toList();
  final bool byEvent = parts.any((String name) => name != entry && _byEvent(name, started(name)));
  return <OutlinePart>[
    for (final String name in parts) _part(name, entry, started(name), _Walker(variables, names).lines(routines[name]), byEvent: byEvent),
  ];
}

bool _byEvent(String name, List<JsonMap> triggers) => name.startsWith('on-') && triggers.isNotEmpty;

OutlinePart _part(String name, String entry, List<JsonMap> triggers, List<OutlineLine> lines, {required bool byEvent}) {
  final String runs = _runs(triggers);
  if (name == entry) return byEvent && triggers.isNotEmpty ? OutlinePart(runs, null, lines) : OutlinePart('What it does', runs, lines);
  if (_byEvent(name, triggers)) return OutlinePart(runs, null, lines);
  return OutlinePart(behaviorTitle(name), runs, lines);
}

String _runs(List<JsonMap> triggers) {
  final String line = startLine(triggers: triggers);
  if (line == 'Runs when called' && triggers.any((JsonMap trigger) => trigger['event'] == 'dialogue_choice')) return 'After an answer';
  return line;
}

class _Walker {
  _Walker(this.variables, this.names);

  final JsonMap variables;
  final DocumentNames names;
  final List<OutlineLine> out = <OutlineLine>[];

  List<OutlineLine> lines(Object? node) {
    _node(node, 0);
    return out;
  }

  void _add(int depth, String text) => out.add(OutlineLine(depth, text));

  void _node(Object? raw, int depth) {
    final JsonMap node = asMap(raw);
    switch (node['type']) {
      case 'sequence':
        for (final Object? step in asList(node['steps'])) {
          _node(step, depth);
        }
      case 'parallel':
        _add(depth, 'At the same time:');
        for (final Object? branch in asList(node['branches'])) {
          _node(branch, depth + 1);
        }
      case 'condition':
        _add(depth, nodeSummary(node, variables: variables, names: names));
        if (node['else'] == null) {
          _node(node['then'], depth + 1);
        } else {
          _add(depth + 1, 'Yes:');
          _node(node['then'], depth + 2);
          _add(depth + 1, 'No:');
          _node(node['else'], depth + 2);
        }
      case 'repeat' || 'foreach' || 'while':
        _add(depth, nodeSummary(node, variables: variables, names: names));
        _node(node['body'], depth + 1);
      case 'call':
        _add(depth, 'Run ${behaviorTitle(asString(node['routine']) ?? '?')}');
      case null when node.isEmpty:
        return;
      default:
        _add(depth, nodeSummary(node, variables: variables, names: names));
    }
  }
}
