/// Home of a workspace kept in this browser: a card per job with its count,
/// the files that need attention, the first steps and the files it did not
/// read. A blank workspace says what to make first. Advanced adds the files
/// that were upgraded.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../logic/gui/gui_text.dart';
import '../logic/plural.dart';
import '../model/animus_document.dart';
import '../model/document_kind.dart';
import '../state/workspace_repository.dart';
import '../state/workspace_route.dart';
import '../ui/action_row.dart';
import '../ui/empty_state.dart';
import '../ui/mode.dart';
import '../ui/page_header.dart';
import '../ui/section.dart';
import 'advanced_notice.dart';
import 'in_advanced_state.dart';
import 'offline/home_parts.dart';
import 'offline/page_parts.dart';
import 'offline/start_rows.dart';
import 'shell_sidebar.dart' show errorCount;
import '../ui/stack.dart';

class WorkspaceOverview extends StatelessWidget {
  const WorkspaceOverview({required this.workspace, required this.ignored, required this.onNavigate, this.upgraded = const <String>[], this.ownRoutes = 0, super.key});

  final WorkspaceSnapshot workspace;

  /// NPCs with a route of their own, counted under Movement.
  final int ownRoutes;

  /// Paths in the opened folder or zip that are not editor documents.
  final List<String> ignored;

  /// NPC and group files below the current schema that were upgraded as the
  /// workspace opened.
  final List<String> upgraded;
  final void Function(EditorRoute route) onNavigate;

  @override
  Widget build(BuildContext context) {
    final Map<DocumentKind, int> counts = <DocumentKind, int>{};
    final List<AttentionRow> attention = <AttentionRow>[];
    for (final AnimusDocument document in workspace.documents) {
      counts[document.kind] = (counts[document.kind] ?? 0) + 1;
      final int errors = errorCount(document);
      if (errors > 0) {
        final String name = guiPlainText(document.title).trim();
        attention.add(AttentionRow(kind: document.kind, id: document.id, name: name.isEmpty ? document.id : name, problems: errors));
      }
    }
    final bool blank = workspace.documents.isEmpty;
    final bool advanced = ModeScope.advanced(context);
    final int hidden = advanced ? 0 : hiddenOnly(counts);
    final List<String> shownUpgrades = advanced ? upgraded : const <String>[];
    return dom.div(classes: 'ui-home', <Widget>[
      stack(spacing: 24, <Widget>[
        PageHeader(title: workspace.name),
        if (blank)
          EmptyState(
            message: 'No files yet.',
            action: ActionRow(
              label: 'First file',
              secondary: <RowAction>[RowAction('Import', () => onNavigate(CitizensRoute(workspaceId: workspace.id)))],
              primary: RowAction('New NPC', () => onNavigate(NewDocumentRoute(workspace.id, DocumentKind.npc))),
            ),
          )
        else if (hidden > 0)
          InAdvancedState(count: hidden, landing: hiddenLanding(workspace.id, counts))
        else ...<Widget>[
          FirstSteps(
            steps: firstSteps(openedNpc: openedAnNpc(workspace.id), changed: workspace.generation > 1),
          ),
          HomeCards(workspaceId: workspace.id, counts: counts, ownRoutes: ownRoutes, server: false),
          NeedsAttention(workspaceId: workspace.id, rows: attention),
        ],
        if (shownUpgrades.isNotEmpty || ignored.isNotEmpty)
          dom.section(
            attributes: const <String, String>{'aria-label': 'Files'},
            <Widget>[
              if (shownUpgrades.isNotEmpty)
                AppSection(
                  title: '${countOf(shownUpgrades.length, 'file')} upgraded to schemaVersion 2',
                  tip: 'NPCs and trait groups from an older Animus move to the current schema as they open. Export and Send write the upgraded files.',
                  open: false,
                  attributes: const <String, String>{'data-testid': 'upgraded-files'},
                  children: <Widget>[_paths(shownUpgrades)],
                ),
              if (ignored.isNotEmpty)
                AppSection(
                  title: '${countOf(ignored.length, 'file')} not read',
                  tip: 'Files in the folder or zip that the editor does not edit, such as other plugins\' files. Export leaves them out.',
                  open: false,
                  attributes: const <String, String>{'data-testid': 'unread-files'},
                  children: <Widget>[_paths(ignored)],
                ),
            ],
          ),
      ]),
    ]);
  }

  static Widget _paths(List<String> paths) => rowList(<Widget>[
    for (final String path in paths.take(200)) listItem(dom.code(<Widget>[Component.text(path)])),
  ]);
}
