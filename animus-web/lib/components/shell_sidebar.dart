/// The workspace sidebar: New document, Send to a server, the document
/// search and every document by kind. On phones it is a drawer behind the
/// top bar's Documents button: Escape closes it, Tab stays inside, and
/// picking a document closes it.
library;

import 'dart:js_interop';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../logic/gui/gui_text.dart';
import '../model/animus_document.dart';
import '../model/document_kind.dart';
import '../state/live_session.dart';
import '../state/workspace_repository.dart';
import '../state/workspace_route.dart';
import 'server/live_bar.dart';

/// Kinds listed in the sidebar, in this order.
const List<DocumentKind> listedKinds = <DocumentKind>[
  DocumentKind.npc,
  DocumentKind.behavior,
  DocumentKind.pack,
  DocumentKind.shop,
  DocumentKind.inventory,
  DocumentKind.menu,
  DocumentKind.hologram,
  DocumentKind.rig,
  DocumentKind.animation,
  DocumentKind.route,
  DocumentKind.skin,
  DocumentKind.textAnimation,
  DocumentKind.presets,
];

/// The id of the sidebar's search box, which Ctrl+F and `/` focus.
const String documentSearchId = 'animus-doc-search';

int errorCount(AnimusDocument document) =>
    document.diagnostics.where((Diagnostic diagnostic) => diagnostic.severity == DiagnosticSeverity.error).length;

class ShellSidebar extends StatefulWidget {
  const ShellSidebar({
    required this.workspace,
    required this.route,
    required this.compact,
    required this.open,
    required this.onClose,
    this.session,
    super.key,
  });

  final WorkspaceSnapshot workspace;
  final EditorRoute route;

  /// Phone widths, where the sidebar is a drawer.
  final bool compact;

  /// Whether the drawer is open (phone widths only).
  final bool open;
  final void Function() onClose;
  final LiveSession? session;

  @override
  State<ShellSidebar> createState() => _ShellSidebarState();
}

class _ShellSidebarState extends State<ShellSidebar> {
  String _filter = '';

  List<web.HTMLElement> _focusables(String selector) {
    final web.NodeList? nodes = web.document.getElementById('animus-sidebar')?.querySelectorAll(selector);
    if (nodes == null) return const <web.HTMLElement>[];
    return <web.HTMLElement>[for (int index = 0; index < nodes.length; index++) nodes.item(index)! as web.HTMLElement];
  }

  void _keys(web.Event raw) {
    final web.KeyboardEvent event = raw as web.KeyboardEvent;
    final bool drawer = component.compact && component.open;
    if (event.key == 'Escape' && drawer) {
      event.preventDefault();
      component.onClose();
      return;
    }
    if (event.key == 'Tab' && drawer) {
      // Focus stays in the drawer while it is open.
      final List<web.HTMLElement> stops = _focusables('a[href], button:not([disabled]), input:not([disabled])');
      if (stops.isEmpty) return;
      final web.Element? active = web.document.activeElement;
      if (event.shiftKey && identical(active, stops.first)) {
        event.preventDefault();
        stops.last.focus();
      } else if (!event.shiftKey && identical(active, stops.last)) {
        event.preventDefault();
        stops.first.focus();
      }
      return;
    }
    if (event.key != 'ArrowDown' && event.key != 'ArrowUp') return;
    final List<web.HTMLElement> items = _focusables('.animus-tree-item');
    final int at = items.indexWhere((web.HTMLElement item) => identical(item, web.document.activeElement));
    if (at < 0) return;
    event.preventDefault();
    final int next = event.key == 'ArrowDown' ? (at + 1).clamp(0, items.length - 1) : (at - 1).clamp(0, items.length - 1);
    items[next].focus();
  }

  void _clicked(web.Event event) {
    if (!component.compact || !component.open) return;
    final web.EventTarget? target = event.target;
    if (target == null || !target.isA<web.Element>()) return;
    if ((target as web.Element).closest('a[href]') != null) component.onClose();
  }

  @override
  Widget build(BuildContext context) {
    final WorkspaceSnapshot workspace = component.workspace;
    final EditorRoute route = component.route;
    final DocumentRoute? selected = route is DocumentRoute ? route : null;
    final String filter = _filter.trim().toLowerCase();
    bool matches(AnimusDocument document) =>
        filter.isEmpty || document.id.toLowerCase().contains(filter) || guiPlainText(document.title).toLowerCase().contains(filter);
    final bool compact = component.compact;
    final bool open = component.open;
    final List<Widget> groups = <Widget>[
      for (final DocumentKind kind in listedKinds)
        if (workspace.documents.any((AnimusDocument document) => document.kind == kind)) _group(workspace, kind, selected, matches),
    ];
    final bool nothingMatches = filter.isNotEmpty && !workspace.documents.any(matches);
    return dom.div(classes: 'animus-sidebar-frame', <Widget>[
      if (compact && open)
        dom.div(
          classes: 'animus-drawer-scrim',
          attributes: const <String, String>{'aria-hidden': 'true'},
          events: <String, void Function(web.Event)>{'click': (web.Event _) => component.onClose()},
          const <Widget>[],
        ),
      dom.aside(
        id: 'animus-sidebar',
        classes: 'animus-sidebar${compact ? ' is-drawer' : ''}${open ? ' is-open' : ''}',
        attributes: <String, String>{
          'aria-label': 'Documents',
          if (compact && open) ...<String, String>{'role': 'dialog', 'aria-modal': 'true'},
          if (compact && !open) 'inert': '',
        },
        events: <String, void Function(web.Event)>{'keydown': _keys, 'click': _clicked},
        <Widget>[
          if (compact)
            dom.div(classes: 'animus-drawer-head', <Widget>[
              const dom.h2(classes: 'animus-drawer-title', <Widget>[Component.text('Documents')]),
              Button.ghost(
                label: 'Close',
                icon: ArcaneIcon.x(),
                size: ButtonSize.small,
                attributes: const <String, String>{'aria-label': 'Close documents'},
                onPressed: component.onClose,
              ),
            ]),
          dom.nav(classes: 'animus-new', attributes: const <String, String>{'aria-label': 'Workspace'}, <Widget>[
            dom.a(
              href: NewDocumentRoute(workspace.id).format(),
              classes: 'animus-side-link is-primary${route is NewDocumentRoute ? ' is-selected' : ''}',
              const <Widget>[Component.text('New document')],
            ),
            if (workspace.server == null)
              dom.a(
                href: SyncRoute(workspace.id).format(),
                classes: 'animus-side-link${route is SyncRoute ? ' is-selected' : ''}',
                const <Widget>[Component.text('Send to a server')],
              ),
          ]),
          dom.div(classes: 'animus-doc-search', <Widget>[
            const dom.label(classes: 'animus-sr-only', attributes: <String, String>{'for': documentSearchId}, <Widget>[
              Component.text('Search documents'),
            ]),
            TextInput(
              id: documentSearchId,
              placeholder: 'Search documents',
              value: _filter,
              attributes: const <String, String>{'aria-keyshortcuts': 'Control+F /'},
              onChanged: (String value) => setState(() => _filter = value),
            ),
          ]),
          if (nothingMatches)
            dom.p(classes: 'animus-muted animus-doc-search-empty', attributes: const <String, String>{'role': 'status'}, <Widget>[
              Component.text('No document id or name contains "${_filter.trim()}". Clear the search to see every document.'),
            ]),
          dom.nav(classes: 'animus-tree', attributes: const <String, String>{'aria-label': 'Documents by kind'}, groups),
        ],
      ),
    ]);
  }

  Widget _group(WorkspaceSnapshot workspace, DocumentKind kind, DocumentRoute? selected, bool Function(AnimusDocument document) matches) {
    final int count = workspace.documents.where((AnimusDocument document) => document.kind == kind).length;
    final LiveSession? session = component.session;
    final String headingId = 'animus-tree-${kind.wireName}';
    return dom.section(classes: 'animus-tree-group', attributes: <String, String>{'aria-labelledby': headingId}, <Widget>[
      dom.div(classes: 'animus-tree-head', <Widget>[
        dom.h3(id: headingId, <Widget>[
          Component.text(kind.label),
          dom.span(classes: 'animus-count', attributes: <String, String>{'aria-label': '$count'}, <Widget>[Component.text('$count')]),
        ]),
        if (kind == DocumentKind.npc)
          dom.a(
            href: NpcListRoute(workspace.id).format(),
            classes: 'animus-tree-browse${component.route is NpcListRoute ? ' is-selected' : ''}',
            const <Widget>[Component.text('Browse')],
          ),
      ]),
      dom.ul(attributes: const <String, String>{'role': 'list'}, <Widget>[
        for (final AnimusDocument document in workspace.documents)
          if (document.kind == kind && matches(document))
            dom.li(<Widget>[
              dom.a(
                href: DocumentRoute(workspace.id, kind, document.id, selected?.view ?? ViewMode.visual).format(),
                classes: 'animus-tree-item${selected?.kind == kind && selected?.documentId == document.id ? ' is-selected' : ''}',
                attributes: <String, String>{if (selected?.kind == kind && selected?.documentId == document.id) 'aria-current': 'page'},
                <Widget>[
                  dom.span(classes: 'animus-tree-title', <Widget>[Component.text(guiPlainText(document.title))]),
                  if (document.title != document.id) dom.span(classes: 'animus-tree-id', <Widget>[Component.text(document.id)]),
                  if (!document.valid)
                    dom.span(
                      classes: 'animus-badge is-error',
                      attributes: <String, String>{'title': '${errorCount(document)} problem${errorCount(document) == 1 ? '' : 's'}'},
                      <Widget>[Component.text('${errorCount(document)}')],
                    ),
                  if (session != null && workspace.server != null && draftBadge(session, kind, document.id) != null)
                    dom.span(
                      classes: 'animus-badge is-draft',
                      attributes: <String, String>{'data-draft': draftBadge(session, kind, document.id)!},
                      <Widget>[Component.text(draftBadge(session, kind, document.id)!)],
                    ),
                ],
              ),
            ]),
      ]),
    ]);
  }
}
