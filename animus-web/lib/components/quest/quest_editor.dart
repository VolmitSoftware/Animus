/// The quest page: the form on the left in one tab strip (Quest, Stages,
/// Rewards) and on the right the offer and journal entry players see.
/// Problems show at their field: the schema's, the server's, and the
/// editor's own checks (NPCs and quests the workspace lacks, unknown items,
/// repeated objective ids). In Simple each tab says how many Advanced
/// settings it hides, with Show.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';

import '../../logic/gui/gui_json.dart';
import '../../logic/gui/shop_checks.dart' show withoutDuplicatePaths;
import '../../logic/quest/quest_model.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../../state/library.dart';
import '../../ui/mode.dart';
import '../../ui/tabs.dart';
import '../gui/gui_fields.dart';
import '../gui/gui_split.dart';
import '../mc/item_picker.dart';
import 'quest_fields.dart';
import 'quest_preview.dart';
import 'quest_rewards.dart';
import 'quest_settings.dart';
import 'quest_stages.dart';

/// Every diagnostic the quest editor shows: the document's own, then the
/// editor's checks against [workspace].
List<Diagnostic> questEditorDiagnostics(AnimusDocument document,
    {List<AnimusDocument> workspace = const <AnimusDocument>[], List<LibraryEntry> npcLibrary = const <LibraryEntry>[], bool Function(String id)? knownItem}) {
  final List<Diagnostic> own = document.diagnostics;
  final List<Diagnostic> checks = questChecks(
    asMap(document.json),
    npcs: QuestNpcs.of(workspace, library: npcLibrary).idSet,
    quests: <String>{for (final AnimusDocument other in workspace) if (other.kind == DocumentKind.quest) other.id},
    knownItem: knownItem,
  );
  return <Diagnostic>[...own, ...withoutDuplicatePaths(own, checks)];
}

/// Which tab a diagnostic belongs to: 0 Quest, 1 Stages, 2 Rewards.
int questTabOf(Diagnostic diagnostic) {
  if (diagnostic.path.startsWith(r'$.stages')) return 1;
  if (diagnostic.path.startsWith(r'$.rewards')) return 2;
  return 0;
}

class QuestEditor extends StatefulWidget {
  const QuestEditor({required this.document, required this.onJson, this.workspaceDocuments = const <AnimusDocument>[], this.npcLibrary = const <LibraryEntry>[], super.key});

  final AnimusDocument document;
  final JsonEdit onJson;

  /// The workspace, for NPC and quest pickers.
  final List<AnimusDocument> workspaceDocuments;

  /// Every NPC the workspace lists, read or not: on a server, NPCs not
  /// loaded yet still show as givers and in the pickers.
  final List<LibraryEntry> npcLibrary;

  @override
  State<QuestEditor> createState() => _QuestEditorState();
}

class _QuestEditorState extends State<QuestEditor> {
  int _tab = 0;

  /// Show on a tab's "advanced settings in use" line: this page shows its
  /// Advanced fields in Simple too.
  bool _reveal = false;

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

  void _show() => setState(() => _reveal = true);

  @override
  Widget build(BuildContext context) {
    final JsonMap quest = _quest;
    final bool advanced = ModeScope.advanced(context) || _reveal;
    final List<AnimusDocument> workspace = component.workspaceDocuments;
    final List<Diagnostic> all =
        questEditorDiagnostics(component.document, workspace: workspace, npcLibrary: component.npcLibrary, knownItem: ItemRegistry.ids.isEmpty ? null : ItemRegistry.has);
    final QuestNpcs npcs = QuestNpcs.of(workspace, library: component.npcLibrary);
    final List<String> quests = <String>[
      for (final AnimusDocument other in workspace)
        if (other.kind == DocumentKind.quest && other.id != component.document.id) other.id,
    ]..sort();
    final List<int> errors = <int>[0, 0, 0];
    for (final Diagnostic d in all) {
      if (d.severity == DiagnosticSeverity.error) errors[questTabOf(d)]++;
    }
    String tab(String label, int index) => errors[index] == 0 ? label : '$label (${errors[index]})';
    return GuiSplit(
      label: 'Quest',
      form: <Widget>[
        AppTabs(
          label: 'Quest',
          tabs: <String>[tab('Quest', 0), tab('Stages', 1), tab('Rewards', 2)],
          selected: _tab,
          onSelect: (int index) => setState(() => _tab = index),
        ),
        switch (_tab) {
          1 => QuestStages(quest: quest, diagnostics: all, npcs: npcs, onWrite: _write, advanced: advanced, onShow: _show),
          2 => QuestRewards(quest: quest, diagnostics: all, onWrite: _write, advanced: advanced, onShow: _show),
          _ => QuestSettings(quest: quest, diagnostics: all, npcs: npcs, quests: quests, onWrite: _write, advanced: advanced, onShow: _show),
        },
      ],
      preview: (bool band) => QuestPreview(key: ValueKey<String>('quest-preview:${component.document.id}'), document: component.document, npcs: npcs, band: band),
    );
  }
}
