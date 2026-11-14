/// The workspace nav: jobs, not files. Simple lists Home, NPCs, Behaviors,
/// Shops, Movement and Holograms; Advanced adds GUIs, Quests, Rigs, Trait
/// groups, Packs, Send to server and Import. Each entry shows how many
/// documents it holds. Below them, Recent (the last opened) and Changed
/// (edits not applied to the server yet). An icon rail at 1100 px and
/// below; on phones the shell puts this in a drawer.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';

import '../logic/change_sets.dart';
import '../model/animus_document.dart';
import '../model/document_kind.dart';
import '../state/editor_store.dart';
import '../state/library.dart';
import '../state/live_session.dart';
import '../state/workspace_repository.dart';
import '../state/workspace_route.dart';
import '../ui/editor_mode.dart';
import '../ui/library_rows.dart';
import '../ui/library_watch.dart';

/// Kinds in the order lists and overviews show them.
const List<DocumentKind> listedKinds = <DocumentKind>[
  DocumentKind.npc,
  DocumentKind.group,
  DocumentKind.behavior,
  DocumentKind.pack,
  DocumentKind.shop,
  DocumentKind.quest,
  DocumentKind.inventory,
  DocumentKind.menu,
  DocumentKind.hologram,
  DocumentKind.rig,
  DocumentKind.animation,
  DocumentKind.route,
  DocumentKind.recording,
  DocumentKind.skin,
  DocumentKind.textAnimation,
  DocumentKind.presets,
  DocumentKind.serverBehaviors,
];

int errorCount(AnimusDocument document) =>
    document.diagnostics.where((Diagnostic diagnostic) => diagnostic.severity == DiagnosticSeverity.error).length;

/// One nav entry: a job and the document kinds it covers.
class NavJob {
  const NavJob(this.id, this.label, this.icon, {this.kinds = const <DocumentKind>[], this.advanced = false, this.hint});

  final String id;
  final String label;
  final ArcaneGlyph Function() icon;

  /// The kinds this job lists; the first is where the entry goes.
  final List<DocumentKind> kinds;
  final bool advanced;

  /// A line for each kind in the job's list switch (Movement's contrast).
  final String? hint;
}

const List<NavJob> navJobs = <NavJob>[
  NavJob('home', 'Home', ArcaneIcon.house),
  NavJob('npcs', 'NPCs', ArcaneIcon.users, kinds: <DocumentKind>[DocumentKind.npc]),
  NavJob('behaviors', 'Behaviors', ArcaneIcon.workflow, kinds: <DocumentKind>[DocumentKind.behavior, DocumentKind.serverBehaviors]),
  NavJob('shops', 'Shops', ArcaneIcon.store, kinds: <DocumentKind>[DocumentKind.shop]),
  NavJob('movement', 'Movement', ArcaneIcon.route, kinds: <DocumentKind>[DocumentKind.route, DocumentKind.recording]),
  NavJob('holograms', 'Holograms', ArcaneIcon.layers, kinds: <DocumentKind>[DocumentKind.hologram]),
  NavJob('guis', 'GUIs', ArcaneIcon.layoutGrid,
      advanced: true, kinds: <DocumentKind>[DocumentKind.inventory, DocumentKind.menu, DocumentKind.textAnimation, DocumentKind.presets]),
  NavJob('quests', 'Quests', ArcaneIcon.scroll, advanced: true, kinds: <DocumentKind>[DocumentKind.quest]),
  NavJob('rigs', 'Rigs', ArcaneIcon.bone, advanced: true, kinds: <DocumentKind>[DocumentKind.rig, DocumentKind.animation, DocumentKind.skin]),
  NavJob('groups', 'Trait groups', ArcaneIcon.puzzle, advanced: true, kinds: <DocumentKind>[DocumentKind.group]),
  NavJob('packs', 'Packs', ArcaneIcon.package, advanced: true, kinds: <DocumentKind>[DocumentKind.pack]),
  NavJob('send', 'Send to server', ArcaneIcon.upload, advanced: true),
  NavJob('import', 'Import', ArcaneIcon.download, advanced: true),
];

/// The job a document kind belongs to.
NavJob jobOfKind(DocumentKind kind) =>
    navJobs.firstWhere((NavJob job) => job.kinds.contains(kind), orElse: () => navJobs.first);

/// The plural a list shows for [kind].
String kindTitle(DocumentKind kind) => switch (kind) {
      DocumentKind.group => 'Trait groups',
      _ => kind.label,
    };

/// A job's tab for [kind], named by what it lists: Behaviors lists every
/// behavior, NPC or server run, and the server's list of those that run.
String subKindTab(DocumentKind kind) => switch (kind) {
      DocumentKind.behavior => 'All behaviors',
      DocumentKind.serverBehaviors => 'Server list',
      _ => kindTitle(kind),
    };

/// The crumb a document of [kind] adds after its job: the tab it is listed
/// under, unless the job has one kind or the tab says what the job says.
String? kindCrumb(DocumentKind kind) {
  final NavJob job = jobOfKind(kind);
  if (job.kinds.length < 2 || kind == DocumentKind.serverBehaviors || kindTitle(kind) == job.label) return null;
  return kindTitle(kind);
}

/// Whether [mode] lists and opens documents of [kind]: Simple keeps to its
/// own jobs' kinds and leaves the server behaviors list out.
bool kindShown(DocumentKind kind, EditorMode mode) {
  if (mode == EditorMode.advanced) return true;
  final NavJob job = jobOfKind(kind);
  return !job.advanced && job.kinds.contains(kind) && kind != DocumentKind.serverBehaviors;
}

/// The jobs the nav lists in [mode]; Send to server only for a workspace
/// kept in this browser.
List<NavJob> visibleJobs(EditorMode mode, {required bool server}) => <NavJob>[
      for (final NavJob job in navJobs)
        if ((!job.advanced || mode == EditorMode.advanced) && !(job.id == 'send' && server)) job,
    ];

/// Where a job's entry goes: with [counts], the first of its kinds that has
/// documents (Rigs of a server with only skins opens Skins).
EditorRoute jobRoute(NavJob job, String workspaceId, {Map<DocumentKind, int>? counts}) => switch (job.id) {
      'home' => WorkspaceRoute(workspaceId),
      'npcs' => NpcListRoute(workspaceId),
      'send' => SyncRoute(workspaceId),
      'import' => CitizensRoute(workspaceId: workspaceId),
      _ => KindListRoute(workspaceId, job.kinds.firstWhere((DocumentKind kind) => (counts?[kind] ?? 0) > 0, orElse: () => job.kinds.first)),
    };

/// The job the page at [route] belongs to, for the nav's current entry.
String? currentJob(EditorRoute route) => switch (route) {
      WorkspaceRoute() || ServerRoute() => 'home',
      NpcListRoute() => 'npcs',
      RecordingListRoute() => 'movement',
      SyncRoute() => 'send',
      CitizensRoute(:final String? workspaceId) when workspaceId != null => 'import',
      KindListRoute(:final DocumentKind kind) => jobOfKind(kind).id,
      DocumentRoute(:final DocumentKind kind) => jobOfKind(kind).id,
      NewDocumentRoute(:final DocumentKind? kind) => kind == null ? null : jobOfKind(kind).id,
      _ => null,
    };

/// How the nav is laid out.
enum NavLayout { full, rail, drawer }

/// One link of the nav.
class NavLink {
  const NavLink(this.label, this.href, {this.current = false, this.badge, this.job});

  final String label;
  final String href;
  final bool current;
  final String? badge;

  /// The job this link opens (jobs carry an icon).
  final NavJob? job;

  @override
  String toString() => '$label|$href|$current|${badge ?? ''}';
}

class ShellNav extends StatefulWidget {
  /// Works out Recent and Changed now, so the frame can tell whether
  /// anything the nav shows changed: a keystroke in a form leaves it as it
  /// was. The job counts come from the library and redraw only when
  /// [EditorStore.countChanges] says they moved.
  ShellNav({
    required this.store,
    required WorkspaceSnapshot workspace,
    required EditorRoute route,
    required this.mode,
    required this.layout,
    required List<String> recent,
    LiveSession? session,
    this.header,
    super.key,
  })  : workspaceId = workspace.id,
        server = workspace.server != null,
        current = currentJob(route),
        recent = _recent(store, workspace, route, recent, mode, session?.drafts ?? const <DraftChange>[]),
        changed = _changed(store, workspace, route, session?.drafts ?? const <DraftChange>[]),
        moreChanged = ((session?.drafts.length ?? 0) - 12).clamp(0, 1 << 30);

  final EditorStore store;
  final String workspaceId;
  final bool server;

  /// The job of the page shown, for the current entry.
  final String? current;
  final NavLayout layout;
  final EditorMode mode;
  final List<NavLink> recent;
  final List<NavLink> changed;

  /// Changed documents beyond the twelve listed.
  final int moreChanged;

  /// Shown above the jobs (the Mode switch in the phone drawer).
  final Widget? header;

  static bool _isOpen(EditorRoute route, DocumentKind kind, String id) => route is DocumentRoute && route.kind == kind && route.documentId == id;

  /// The recently opened documents that [mode] shows and Changed does not
  /// list already; Advanced ones stay in the list and come back with the
  /// mode.
  static List<NavLink> _recent(EditorStore store, WorkspaceSnapshot workspace, EditorRoute route, List<String> recent, EditorMode mode, List<DraftChange> drafts) {
    final Set<String> changed = <String>{for (final DraftChange draft in drafts.take(12)) '${draft.kind.wireName}:${draft.id}'};
    return <NavLink>[
      for (final String key in recent)
        if (_refOf(key) case (final DocumentKind kind, final String id)
            when !changed.contains(key) && kindShown(kind, mode) && (store.libraryEntry(kind, id) != null || store.document(kind, id) != null))
          NavLink(navName(store, kind, id), DocumentRoute(workspace.id, kind, id).format(), current: _isOpen(route, kind, id)),
    ];
  }

  static List<NavLink> _changed(EditorStore store, WorkspaceSnapshot workspace, EditorRoute route, List<DraftChange> drafts) => <NavLink>[
        for (final DraftChange draft in drafts.take(12))
          NavLink(navName(store, draft.kind, draft.id), DocumentRoute(workspace.id, draft.kind, draft.id).format(),
              current: _isOpen(route, draft.kind, draft.id), badge: _state(store, draft)),
      ];

  static (DocumentKind, String)? _refOf(String key) {
    final int colon = key.indexOf(':');
    if (colon < 0) return null;
    final DocumentKind? kind = DocumentKind.parse(key.substring(0, colon));
    return kind == null ? null : (kind, key.substring(colon + 1));
  }

  /// A changed document's state for its badge.
  static String? _state(EditorStore store, DraftChange draft) {
    final AnimusDocument? document = store.document(draft.kind, draft.id);
    final int errors = document == null ? 0 : errorCount(document);
    if (draft.problem != null || errors > 0) {
      final int count = errors == 0 ? 1 : errors;
      return '$count ${count == 1 ? 'problem' : 'problems'}';
    }
    return switch (draft.state) {
      DraftState.deleted => 'Deleted',
      DraftState.created => 'New',
      _ => null,
    };
  }

  @override
  State<ShellNav> createState() => _ShellNavState();
}

/// How many things a job holds: its kinds' documents (Simple leaves the
/// server behaviors list out) and, for Movement, the NPCs with a route of
/// their own, as the Movement page lists them. Rigs without a rig counts
/// the tab it opens on ([jobRoute]), so the number matches the page.
int jobCount(NavJob job, Map<DocumentKind, int> counts, EditorMode mode, {int ownRoutes = 0}) {
  final int total = kindTotal(counts, job.kinds.where((DocumentKind kind) => _counted(kind, mode))) + (job.kinds.contains(DocumentKind.route) ? ownRoutes : 0);
  if (total > 0 || job.kinds.isEmpty) return total;
  final EditorRoute landing = jobRoute(job, '', counts: counts);
  return landing is KindListRoute && kindShown(landing.kind, mode) ? counts[landing.kind] ?? 0 : 0;
}

/// Simple leaves the server behaviors list out; Rigs counts rigs, not the
/// animations and skins on its other tabs.
bool _counted(DocumentKind kind, EditorMode mode) =>
    (mode == EditorMode.advanced || kind != DocumentKind.serverBehaviors) && kind != DocumentKind.animation && kind != DocumentKind.skin;

const ArcaneStyleData _inDrawer = ArcaneStyleData(widthCustom: '100%', borderCustom: 'none', backgroundCustom: 'transparent');

/// What Recent and Changed call a document: the name its list row shows.
String navName(EditorStore store, DocumentKind kind, String id) => store.libraryEntry(kind, id)?.name ?? id;

/// The longest Recent or Changed label; a longer name ends in "..." and
/// shows whole in the link's tooltip.
const int navLabelLength = 28;

String _short(String label) => label.length <= navLabelLength ? label : '${label.substring(0, navLabelLength - 1).trimRight()}\u2026';

class _ShellNavState extends State<ShellNav> with LibraryWatch<ShellNav> {
  @override
  void initState() {
    super.initState();
    watchLibrary(<LibraryChanges>[component.store.countChanges]);
  }

  @override
  void didUpdateWidget(ShellNav oldComponent) {
    super.didUpdateWidget(oldComponent);
    if (!identical(oldComponent.store, component.store)) watchLibrary(<LibraryChanges>[component.store.countChanges]);
  }

  @override
  bool shouldRebuild(ShellNav newComponent) {
    final ShellNav old = component;
    return !identical(newComponent.store, old.store) ||
        newComponent.workspaceId != old.workspaceId ||
        newComponent.server != old.server ||
        newComponent.current != old.current ||
        newComponent.layout != old.layout ||
        newComponent.mode != old.mode ||
        newComponent.moreChanged != old.moreChanged ||
        newComponent.recent.join('\n') != old.recent.join('\n') ||
        newComponent.changed.join('\n') != old.changed.join('\n');
  }

  bool get _rail => component.layout == NavLayout.rail;

  List<NavLink> _jobs() {
    final ShellNav nav = component;
    final Map<DocumentKind, int> counts = nav.store.libraryCounts();
    final int ownRoutes = nav.store.libraryOwnRoutes();
    return <NavLink>[
      for (final NavJob each in visibleJobs(nav.mode, server: nav.server))
        NavLink(
          each.label,
          jobRoute(each, nav.workspaceId, counts: counts).format(),
          current: each.id == nav.current,
          job: each,
          badge: each.kinds.isEmpty ? null : '${jobCount(each, counts, nav.mode, ownRoutes: ownRoutes)}',
        ),
    ];
  }

  Widget _item(NavLink link) => ArcaneSidebarItem(
        label: link.job == null ? _short(link.label) : link.label,
        tooltip: link.job == null && link.label.length > navLabelLength ? link.label : null,
        href: link.href,
        icon: link.job?.icon(),
        selected: link.current,
        badge: link.badge,
        collapsed: _rail,
      );

  @override
  Widget build(BuildContext context) {
    final ShellNav nav = component;
    final List<Widget> groups = <Widget>[
      ArcaneSidebarGroup(children: <Widget>[for (final NavLink link in _jobs()) _item(link)]),
      if (!_rail && nav.recent.isNotEmpty) ArcaneSidebarGroup(label: 'Recent', children: <Widget>[for (final NavLink link in nav.recent) _item(link)]),
      if (!_rail && nav.changed.isNotEmpty)
        ArcaneSidebarGroup(label: 'Changed', children: <Widget>[
          for (final NavLink link in nav.changed) _item(link),
          if (nav.moreChanged > 0) ArcaneSidebarItem(label: '${nav.moreChanged} more in Review', disabled: true),
        ]),
    ];
    final bool drawer = nav.layout == NavLayout.drawer;
    return ArcaneSidebar(
      width: _rail ? 56 : 240,
      collapsedWidth: 56,
      collapsed: _rail,
      showCollapseToggle: false,
      header: nav.header,
      // In the phone drawer the sheet is the panel: the nav fills it with no
      // second box (border, tint) inside.
      styles: drawer ? _inDrawer : null,
      children: groups,
    );
  }
}
