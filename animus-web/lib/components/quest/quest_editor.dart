/// The quest editor: the quest's settings, stages and rewards as forms on
/// one side, and on the other the offer and journal entry players see.
/// Problems show at their field: the schema's, the server's, and the
/// editor's own checks (NPCs and quests the workspace lacks, unknown items,
/// repeated objective ids).
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/gui/gui_json.dart';
import '../../logic/gui/shop_checks.dart' show withoutDuplicatePaths;
import '../../logic/quest/quest_model.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../gui/gui_fields.dart';
import '../mc/item_picker.dart';
import 'quest_fields.dart';
import 'quest_preview.dart';
import 'quest_rewards.dart';
import 'quest_settings.dart';
import 'quest_stages.dart';

/// Every diagnostic the quest editor shows: the document's own, then the
/// editor's checks against [workspace].
List<Diagnostic> questEditorDiagnostics(AnimusDocument document, {List<AnimusDocument> workspace = const <AnimusDocument>[], bool Function(String id)? knownItem}) {
  final List<Diagnostic> own = document.diagnostics;
  final List<Diagnostic> checks = questChecks(
    asMap(document.json),
    npcs: QuestNpcs(workspace).idSet,
    quests: <String>{for (final AnimusDocument other in workspace) if (other.kind == DocumentKind.quest) other.id},
    knownItem: knownItem,
  );
  return <Diagnostic>[...own, ...withoutDuplicatePaths(own, checks)];
}

class QuestEditor extends StatefulWidget {
  const QuestEditor({required this.document, required this.onJson, this.workspaceDocuments = const <AnimusDocument>[], super.key});

  final AnimusDocument document;
  final JsonEdit onJson;

  /// The workspace, for NPC and quest pickers.
  final List<AnimusDocument> workspaceDocuments;

  @override
  State<QuestEditor> createState() => _QuestEditorState();
}

enum _Part { quest, stages, rewards }

class _QuestEditorState extends State<QuestEditor> {
  _Part _part = _Part.quest;

  JsonMap get _quest => asMap(component.document.json);

  @override
  void initState() {
    super.initState();
    if (ItemRegistry.ids.isEmpty) {
      ItemRegistry.load().then((List<String> _) {
        if (mounted) setState(() {});
      });
    }
  }

  void _write(List<JsonStep> steps, Object? value, {String? coalesce}) => component.onJson(writeAt(_quest, steps, value), coalesce: coalesce);

  @override
  Widget build(BuildContext context) {
    final JsonMap quest = _quest;
    final List<AnimusDocument> workspace = component.workspaceDocuments;
    final List<Diagnostic> all = questEditorDiagnostics(component.document, workspace: workspace, knownItem: ItemRegistry.ids.isEmpty ? null : ItemRegistry.has);
    final QuestNpcs npcs = QuestNpcs(workspace);
    final List<String> quests = <String>[
      for (final AnimusDocument other in workspace)
        if (other.kind == DocumentKind.quest && other.id != component.document.id) other.id,
    ]..sort();
    final int stages = asList(quest['stages']).length;
    final int errors = all.where((Diagnostic d) => d.severity == DiagnosticSeverity.error).length;
    bool inStages(Diagnostic d) => d.path.startsWith(r'$.stages');
    bool inRewards(Diagnostic d) => d.path.startsWith(r'$.rewards');
    String mark(String label, bool Function(Diagnostic) where) =>
        all.any((Diagnostic d) => d.severity == DiagnosticSeverity.error && where(d)) ? '$label (!)' : label;
    final List<Diagnostic> loose = <Diagnostic>[for (final Diagnostic d in all) if (d.path == r'$' || d.path == r'$.id' || d.path == r'$.schemaVersion' || d.path == r'$.revision') d];
    return dom.div(classes: 'animus-quest-editor', <Widget>[
      dom.section(classes: 'animus-panel animus-quest-form', attributes: const <String, String>{'aria-label': 'Quest fields'}, <Widget>[
        if (errors > 0)
          dom.p(classes: 'animus-gui-problem is-error', attributes: const <String, String>{'role': 'status'}, <Widget>[
            Component.text('$errors problem${errors == 1 ? '' : 's'} to fix before the server loads this quest.'),
          ]),
        for (final Diagnostic problem in loose)
          dom.p(classes: 'animus-gui-problem is-${problem.severity.name}', attributes: const <String, String>{'role': 'note'}, <Widget>[
            dom.code(<Widget>[Component.text(problem.path)]),
            Component.text(' ${problem.message}'),
          ]),
        GuiTabs(
          label: 'Quest parts',
          tabs: <String>[
            mark('Quest', (Diagnostic d) => !inStages(d) && !inRewards(d)),
            mark('Stages ($stages)', inStages),
            mark('Rewards', inRewards),
          ],
          selected: _part.index,
          onSelect: (int index) => setState(() => _part = _Part.values[index]),
        ),
        switch (_part) {
          _Part.quest => QuestSettings(quest: quest, diagnostics: all, npcs: npcs, quests: quests, onWrite: _write),
          _Part.stages => QuestStages(quest: quest, diagnostics: all, npcs: npcs, onWrite: _write),
          _Part.rewards => QuestRewards(quest: quest, diagnostics: all, onWrite: _write),
        },
      ]),
      QuestPreview(key: ValueKey<String>('quest-preview:${component.document.id}'), document: component.document, npcs: npcs),
    ]);
  }
}
