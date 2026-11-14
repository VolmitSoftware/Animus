/// A job's list page (`#/workspace/<id>/list/<kind>`): the kit's page header
/// and one [LibraryList] of the kind's documents. Where a job covers more
/// than one kind (GUIs, Rigs) a switch picks the kind; Behaviors and
/// Movement have pages of their own.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../model/document_kind.dart';
import '../services/browser.dart';
import '../state/editor_store.dart';
import '../state/library.dart';
import '../state/workspace_repository.dart';
import '../state/workspace_route.dart';
import '../ui/action_row.dart';
import '../ui/editor_mode.dart';
import '../ui/library_list.dart';
import '../ui/library_query.dart';
import '../ui/library_rows.dart';
import '../ui/library_watch.dart';
import '../ui/mode.dart';
import '../ui/page_header.dart';
import '../ui/tabs.dart';
import 'inspect/behavior_list.dart';
import 'offline/new_document_page.dart';
import 'recording/movement_page.dart';
import 'select/picker_scope.dart' show kindNoun, kindPlural;
import 'shell_sidebar.dart';

/// One [kind] in a sentence: "shop", "chest GUI", "trait group".
String listNoun(DocumentKind kind) => kind == DocumentKind.group ? 'trait group' : kindNoun(kind);

/// [kind] as a label beside a name: "NPC", "Shop", "Chest GUI".
String kindLabel(DocumentKind kind) {
  final String noun = listNoun(kind);
  return '${noun[0].toUpperCase()}${noun.substring(1)}';
}

/// [kind] in a sentence, plural: "shops", "trait groups".
String listPlural(DocumentKind kind) => kind == DocumentKind.group ? 'trait groups' : kindPlural(kind);

/// The one sub-kind switch of a job's list (GUIs, Rigs, Behaviors): tabs
/// under the title, each with its count where it has documents.
class SubKindTabs extends StatelessWidget {
  const SubKindTabs({required this.kinds, required this.kind, required this.workspaceId, required this.counts, super.key});

  final List<DocumentKind> kinds;
  final DocumentKind kind;
  final String workspaceId;
  final Map<DocumentKind, int> counts;

  @override
  Widget build(BuildContext context) => AppTabs(
        id: 'ui-kind-tabs',
        label: 'Show',
        tabs: <String>[
          for (final DocumentKind each in kinds) (counts[each] ?? 0) > 0 ? '${subKindTab(each)} (${counts[each]})' : subKindTab(each),
        ],
        selected: kinds.indexOf(kind).clamp(0, kinds.length - 1),
        onSelect: (int index) => navigate(KindListRoute(workspaceId, kinds[index]).format()),
      );
}

class ListPage extends StatefulWidget {
  const ListPage({required this.store, required this.workspace, required this.kind, required this.onNavigate, super.key});

  final EditorStore store;
  final WorkspaceSnapshot workspace;
  final DocumentKind kind;
  final void Function(EditorRoute route) onNavigate;

  @override
  State<ListPage> createState() => _ListPageState();
}

/// Rebuilds when the rows of its job's kinds move, not on every edit
/// elsewhere in the workspace.
class _ListPageState extends State<ListPage> with LibraryWatch<ListPage> {
  /// The New dialog, open over the list.
  bool _newOpen = false;

  @override
  void initState() {
    super.initState();
    _watch();
  }

  @override
  void didUpdateWidget(ListPage oldComponent) {
    super.didUpdateWidget(oldComponent);
    if (oldComponent.kind != component.kind || !identical(oldComponent.store, component.store)) _watch();
  }

  void _watch() {
    final ListPage page = component;
    watchLibrary(<LibraryChanges>[
      for (final DocumentKind kind in <DocumentKind>{
        page.kind,
        ...jobOfKind(page.kind).kinds,
        // Movement lists NPCs' own routes too.
        if (movementKinds.contains(page.kind)) DocumentKind.npc,
      })
        page.store.libraryChanges(kind),
    ]);
  }

  @override
  bool shouldRebuild(ListPage newComponent) =>
      _newOpen ||
      newComponent.kind != component.kind ||
      !identical(newComponent.store, component.store) ||
      newComponent.workspace.id != component.workspace.id ||
      newComponent.workspace.name != component.workspace.name;

  @override
  Widget build(BuildContext context) {
    final ListPage page = component;
    final EditorStore store = page.store;
    final WorkspaceSnapshot workspace = page.workspace;
    final DocumentKind kind = page.kind;
    final void Function(EditorRoute route) onNavigate = page.onNavigate;
    if (kind == DocumentKind.behavior) return BehaviorListPage(store: store, workspace: workspace, onNavigate: onNavigate);
    if (movementKinds.contains(kind)) return MovementPage(store: store, workspace: workspace, kind: kind, onNavigate: onNavigate);
    final EditorMode mode = ModeScope.of(context);
    final NavJob job = jobOfKind(kind);
    final List<DocumentKind> kinds = <DocumentKind>[
      for (final DocumentKind each in job.kinds)
        if (mode == EditorMode.advanced || each != DocumentKind.serverBehaviors) each,
    ];
    final String noun = listNoun(kind);
    final List<LibraryRow> rows = libraryRowsFor(store, kind);
    void create() => setState(() => _newOpen = true);
    return dom.div(classes: 'ui-list-page', <Widget>[
      PageHeader(
        title: job.id == 'home' ? kindTitle(kind) : job.label,
        crumbs: <Crumb>[Crumb(workspace.name, href: WorkspaceRoute(workspace.id).format()), Crumb(job.label)],
        // An empty list's own New is the one primary.
        primary: rows.isEmpty ? null : RowAction('New $noun', create, icon: ArcaneIcon.plus(), attributes: const <String, String>{'data-testid': 'list-new'}),
      ),
      if (kinds.length > 1) SubKindTabs(kinds: kinds, kind: kind, workspaceId: workspace.id, counts: store.libraryCounts()),
      LibraryList(
        key: ValueKey<String>('library:${workspace.id}:${kind.wireName}'),
        label: kindTitle(kind),
        noun: noun,
        rows: rows,
        filters: <LibraryFilter>[
          if (kind == DocumentKind.npc) ...<LibraryFilter>[spawnedFilter, despawnedFilter],
          problemsFilter,
        ],
        emptyMessage: 'No ${listPlural(kind)} yet.',
        emptyAction: Button.primary(label: 'New $noun', onPressed: create),
      ),
      if (_newOpen)
        NewDocumentPage(
          key: ValueKey<String>('new:${kind.wireName}'),
          store: store,
          workspace: workspace,
          kind: kind,
          onNavigate: onNavigate,
          onClose: () => setState(() => _newOpen = false),
        ),
    ]);
  }
}
