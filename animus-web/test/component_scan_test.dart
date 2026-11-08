// Scans lib/components for two things a rebuild or a new screen can lose:
// a `<details>` whose open state nobody owns (jaspr drops `open` on the next
// build, or a hardcoded `open` re-opens what the reader closed), and a
// section or screen with no hover tip on its labels.
import 'dart:io';

import 'package:test/test.dart';

/// The argument text of each call to [name] in [source], from the opening
/// parenthesis to its match.
List<({int line, String args, String topLevel})> calls(String source, String name) {
  final List<({int line, String args, String topLevel})> found = <({int line, String args, String topLevel})>[];
  final RegExp start = RegExp('(?<![A-Za-z0-9_])${RegExp.escape(name)}\\(');
  for (final RegExpMatch match in start.allMatches(source)) {
    final String before = source.substring(source.lastIndexOf('\n', match.start) + 1, match.start);
    if (before.trimLeft().startsWith('//') || before.contains('///')) continue;
    int depth = 0;
    final StringBuffer top = StringBuffer();
    int index = match.end - 1;
    for (; index < source.length; index++) {
      final String char = source[index];
      if (char == '(' || char == '[' || char == '{') {
        depth++;
        if (depth == 1) continue;
      } else if (char == ')' || char == ']' || char == '}') {
        depth--;
        if (depth == 0) break;
      }
      if (depth == 1) top.write(char);
    }
    found.add((
      line: '\n'.allMatches(source.substring(0, match.start)).length + 1,
      args: source.substring(match.end, index),
      topLevel: top.toString(),
    ));
  }
  return found;
}

/// Why a `dom.details(` call is not controlled, or null when it is.
String? uncontrolled(String file, String args) {
  if (RegExp(r"""const\s*<String,\s*String>\{[^}]*'open'""").hasMatch(args)) return 'passes a constant open';
  if (!RegExp(r"""if\s*\([^)]*\)\s*'open'""").hasMatch(args)) return 'does not pass open from its state';
  if (!args.contains("'toggle'")) return 'does not follow the browser toggle';
  if (!file.contains('takeSummaryClick(')) return 'does not take the summary click';
  return null;
}

Iterable<File> componentFiles() sync* {
  for (final FileSystemEntity entity in Directory('lib/components').listSync(recursive: true)) {
    if (entity is File && entity.path.endsWith('.dart')) yield entity;
  }
}

/// Screens and parts that had no hover tip at all. Each keeps at least one.
const List<String> tippedFiles = <String>[
  'lib/components/npc/npc_browser.dart',
  'lib/components/npc/npc_folders.dart',
  'lib/components/npc/group_parts.dart',
  'lib/components/npc/equipment_slots.dart',
  'lib/components/npc/attributes_editor.dart',
  'lib/components/npc/appearance_editor.dart',
  'lib/components/npc/position_grid.dart',
  'lib/components/npc/npc_modules.dart',
  'lib/components/gui/menu_editor.dart',
  'lib/components/gui/text_animation_editor.dart',
  'lib/components/gui/style_editor.dart',
  'lib/components/gui/chest_window.dart',
  'lib/components/gui/offer_board.dart',
  'lib/components/gui/shop_previews.dart',
  'lib/components/gui/merchant_window.dart',
  'lib/components/rig/rig_tree.dart',
  'lib/components/rig/rig_actions.dart',
  'lib/components/rig/rig_animations_panel.dart',
  'lib/components/rig/rig_gizmo.dart',
  'lib/components/recording/recording_view.dart',
  'lib/components/server/server_overview.dart',
  'lib/components/server/live_bar.dart',
  'lib/components/shell_sidebar.dart',
  'lib/components/offline/kind_list_page.dart',
  'lib/components/offline/home_screen.dart',
  'lib/components/workspace_overview.dart',
];

final RegExp tipMarker = RegExp(r'\bwithTip\(|\bInfoTip\(|\btip:\s*(?!null\b)\S');

void main() {
  test('every details element in lib/components owns its open state', () {
    final List<String> found = <String>[];
    for (final File file in componentFiles()) {
      final String source = file.readAsStringSync();
      for (final ({int line, String args, String topLevel}) call in calls(source, 'dom.details')) {
        final String? why = uncontrolled(source, call.args);
        if (why != null) found.add('${file.path}:${call.line}: $why');
      }
    }
    expect(found, isEmpty, reason: 'Use Fold (lib/components/fold.dart), GuiSection or NpcSection, or keep open in state as they do.');
  });

  test('every NpcSection and GuiSection has a tip on its title', () {
    final List<String> found = <String>[];
    for (final File file in componentFiles()) {
      final String source = file.readAsStringSync();
      for (final String name in <String>['NpcSection', 'GuiSection']) {
        for (final ({int line, String args, String topLevel}) call in calls(source, name)) {
          if (call.args.contains('required this.')) continue;
          if (!RegExp(r'\btip:').hasMatch(call.topLevel)) found.add('${file.path}:${call.line}: $name has no tip');
        }
      }
    }
    expect(found, isEmpty);
  });

  test('screens that had no hover info keep some', () {
    final List<String> missing = <String>[
      for (final String path in tippedFiles)
        if (!tipMarker.hasMatch(File(path).readAsStringSync())) path,
    ];
    expect(missing, isEmpty);
  });

  test('the scans catch what they look for', () {
    const String bare = "dom.details(<Widget>[dom.summary(<Widget>[]), child])";
    const String fixed = "dom.details(attributes: const <String, String>{'open': ''}, <Widget>[])";
    const String owned = "dom.details(attributes: <String, String>{if (_open) 'open': ''}, events: {'toggle': (e) {}}, <Widget>[])";
    expect(uncontrolled('', calls(bare, 'dom.details').single.args), isNotNull);
    expect(uncontrolled('takeSummaryClick(', calls(fixed, 'dom.details').single.args), 'passes a constant open');
    expect(uncontrolled('takeSummaryClick(', calls(owned, 'dom.details').single.args), isNull);
    final ({int line, String args, String topLevel}) section =
        calls("NpcSection(title: 'A', summary: '', children: <Widget>[GuiSection(title: 'B', tip: 'x', children: [])])", 'NpcSection').single;
    expect(section.topLevel.contains('tip:'), isFalse);
    expect(tipMarker.hasMatch("withTip(Component.text('Tags'), 'Labels.')"), isTrue);
    expect(tipMarker.hasMatch('tip: null'), isFalse);
  });
}
