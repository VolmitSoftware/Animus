/// The Behaviors page: every behavior of the workspace in one list (plain
/// names, what starts each one, conversations and the rest as filters),
/// and New behavior, the New dialog for behaviors: what it starts from (a
/// conversation, a template, blank or, in Advanced, a server behavior), its
/// name and folder, then the new behavior opens.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../logic/conversation/conversation_json.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/inspect/behavior_words.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../../state/editor_store.dart';
import '../../state/library.dart';
import '../../state/workspace_repository.dart';
import '../../state/workspace_route.dart';
import '../../ui/action_row.dart';
import '../../ui/library_list.dart';
import '../../ui/library_query.dart';
import '../../ui/library_rows.dart';
import '../../ui/mode.dart';
import '../../ui/page_header.dart';
import '../list_page.dart' show SubKindTabs;
import '../offline/new_document_page.dart';
import '../shell_sidebar.dart' show jobOfKind;

const LibraryFilter conversationsFilter = LibraryFilter('conversation', 'Conversations');
const LibraryFilter otherBehaviorsFilter = LibraryFilter('other', 'Other');

/// Whether a behavior reads as a conversation: the whole document when it
/// is here, else its catalog summary (it answers dialogue choices). Read
/// documents are remembered by their source.
final Map<String, (String, bool)> _conversationCache = <String, (String, bool)>{};

bool isConversation(LibraryEntry entry, AnimusDocument? document) {
  if (document == null) return entry.summary.triggers.contains('dialogue_choice');
  final (String, bool)? cached = _conversationCache[entry.key];
  if (cached != null && identical(cached.$1, document.source)) return cached.$2;
  final bool fits = conversationOf(document.json) != null;
  _conversationCache[entry.key] = (document.source, fits);
  return fits;
}

/// One behavior as a list row: its plain name (with its folder when another
/// behavior has the same name), what starts it, its state badges.
List<LibraryRow> behaviorRows(EditorStore store, String workspaceId) {
  final List<LibraryEntry> entries = store.library(DocumentKind.behavior);
  final Map<String, int> names = <String, int>{};
  for (final LibraryEntry entry in entries) {
    names.update(behaviorTitle(entry.id), (int count) => count + 1, ifAbsent: () => 1);
  }
  return <LibraryRow>[
    for (final LibraryEntry entry in entries) _row(store, workspaceId, entry, names),
  ];
}

LibraryRow _row(EditorStore store, String workspaceId, LibraryEntry entry, Map<String, int> names) {
  final LibraryRow base = entryRow(workspaceId, entry);
  final AnimusDocument? document = store.document(DocumentKind.behavior, entry.id);
  final bool talks = isConversation(entry, document);
  final String title = behaviorTitle(entry.id);
  final String folder = behaviorFolder(entry.id);
  final String start = document == null ? startLine(events: entry.summary.triggers) : startLine(triggers: asList(asMap(document.json)['triggers']));
  final bool server = document != null && asMap(document.json)['server'] == true;
  return LibraryRow(
    key: base.key,
    title: (names[title] ?? 0) > 1 && folder.isNotEmpty ? '$title ($folder)' : title,
    meta: <String>[if (talks) 'Conversation', if (server) 'Server', start].join(' · '),
    advancedMeta: base.advancedMeta,
    badges: base.badges,
    tags: <String>{...base.tags, talks ? conversationsFilter.id : otherBehaviorsFilter.id},
    href: base.href,
    keywords: '${base.keywords} $title',
    sortValues: <String, String>{LibrarySort.name.id: title.toLowerCase()},
  );
}

class BehaviorListPage extends StatefulWidget {
  const BehaviorListPage({required this.store, required this.workspace, required this.onNavigate, super.key});

  final EditorStore store;
  final WorkspaceSnapshot workspace;
  final void Function(EditorRoute route) onNavigate;

  @override
  State<BehaviorListPage> createState() => _BehaviorListPageState();
}

class _BehaviorListPageState extends State<BehaviorListPage> {
  bool _open = false;

  void _start() => setState(() => _open = true);

  void _close() => setState(() => _open = false);

  @override
  Widget build(BuildContext context) {
    final bool advanced = ModeScope.advanced(context);
    final WorkspaceSnapshot workspace = component.workspace;
    final String job = jobOfKind(DocumentKind.behavior).label;
    final List<LibraryRow> rows = behaviorRows(component.store, workspace.id);
    return dom.div(classes: 'ui-list-page', <Widget>[
      PageHeader(
        title: job,
        crumbs: <Crumb>[Crumb(workspace.name, href: WorkspaceRoute(workspace.id).format()), Crumb(job)],
        // An empty list's own New is the one primary.
        primary: rows.isEmpty ? null : RowAction('New behavior', _start, icon: ArcaneIcon.plus(), attributes: const <String, String>{'data-testid': 'list-new'}),
      ),
      if (advanced)
        SubKindTabs(
          kinds: const <DocumentKind>[DocumentKind.behavior, DocumentKind.serverBehaviors],
          kind: DocumentKind.behavior,
          workspaceId: workspace.id,
          counts: component.store.libraryCounts(),
        ),
      LibraryList(
        key: ValueKey<String>('library:${workspace.id}:behavior'),
        label: 'Behaviors',
        noun: 'behavior',
        rows: rows,
        filters: const <LibraryFilter>[conversationsFilter, otherBehaviorsFilter, problemsFilter],
        emptyMessage: 'No behaviors yet.',
        emptyAction: Button.primary(label: 'New behavior', onPressed: _start),
      ),
      if (_open)
        NewDocumentPage(
          key: const ValueKey<String>('new:behavior'),
          store: component.store,
          workspace: workspace,
          kind: DocumentKind.behavior,
          conversation: true,
          onNavigate: component.onNavigate,
          onClose: _close,
        ),
    ]);
  }
}
