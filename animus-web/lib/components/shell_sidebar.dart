/// The workspace sidebar: New document, Send to a server, the document
/// search and every document by kind. Each kind's section, and each NPC
/// folder, collapses from its header; what is collapsed is kept per
/// workspace. On phones it is a drawer behind the top bar's Documents
/// button: Escape closes it, Tab stays inside, and picking a document
/// closes it.
library;

import 'dart:js_interop';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../config/docs_links.dart';
import '../logic/gui/gui_text.dart';
import '../logic/sidebar_sections.dart';
import '../model/animus_document.dart';
import '../model/document_kind.dart';
import '../state/live_session.dart';
import '../state/workspace_repository.dart';
import '../state/workspace_route.dart';
import 'info_tip.dart';
import 'server/live_bar.dart';
import 'shortcut_sheet.dart';

/// Kinds listed in the sidebar, in this order.
const List<DocumentKind> listedKinds = <DocumentKind>[
  DocumentKind.npc,
  DocumentKind.group,
  DocumentKind.behavior,
  DocumentKind.pack,
  DocumentKind.shop,
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

/// Kinds whose sidebar section is a folder tree (NPC ids are folder/name).
const Set<DocumentKind> foldered = <DocumentKind>{DocumentKind.npc};

/// The id of the sidebar's search box, which Cmd or Ctrl+F and `/` focus.
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
    this.hasCodeDraft,
    super.key,
  });

  /// True for a document with Code text not applied to its draft.
  final bool Function(DocumentKind kind, String id)? hasCodeDraft;

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

  /// Collapsed section and folder keys ([sectionKey], [folderKey]).
  Set<String> _collapsed = <String>{};

  @override
  void initState() {
    super.initState();
    _collapsed = _load(component.workspace.id);
  }

  @override
  void didUpdateWidget(ShellSidebar oldComponent) {
    super.didUpdateWidget(oldComponent);
    if (oldComponent.workspace.id != component.workspace.id) _collapsed = _load(component.workspace.id);
  }

  static Set<String> _load(String workspaceId) {
    try {
      return decodeCollapsed(web.window.localStorage.getItem(collapsedStorageKey(workspaceId)));
    } on Object {
      // Storage blocked: everything starts open.
      return <String>{};
    }
  }

  void _store(Set<String> next) {
    setState(() => _collapsed = next);
    try {
      web.window.localStorage.setItem(collapsedStorageKey(component.workspace.id), encodeCollapsed(next));
    } on Object {
      // Storage blocked: the sections still fold for this page.
    }
  }

  void _toggle(String key) => _store(_collapsed.contains(key) ? (<String>{..._collapsed}..remove(key)) : <String>{..._collapsed, key});

  /// Every section and folder key the sidebar shows now.
  Set<String> _allKeys() {
    final List<AnimusDocument> documents = component.workspace.documents;
    final Set<String> keys = <String>{
      for (final DocumentKind kind in listedKinds)
        if (documents.any((AnimusDocument document) => document.kind == kind)) sectionKey(kind.wireName),
    };
    void walk(DocumentKind kind, List<TreeNode<AnimusDocument>> nodes) {
      for (final TreeNode<AnimusDocument> node in nodes) {
        if (node is TreeFolder<AnimusDocument>) {
          keys.add(folderKey(kind.wireName, node.path));
          walk(kind, node.children);
        }
      }
    }

    for (final DocumentKind kind in foldered) {
      walk(kind, folderTree(<AnimusDocument>[for (final AnimusDocument document in documents) if (document.kind == kind) document], (AnimusDocument document) => document.id));
    }
    return keys;
  }

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
    final web.Element? active = web.document.activeElement;
    final bool onToggle = active != null && active.classList.contains('animus-tree-toggle');
    if (onToggle && (event.key == 'ArrowRight' || event.key == 'ArrowLeft')) {
      // Right opens a closed header, Left closes an open one.
      final String key = active.getAttribute('data-section') ?? '';
      final bool expanded = active.getAttribute('aria-expanded') == 'true';
      if (key.isNotEmpty && expanded == (event.key == 'ArrowLeft')) {
        event.preventDefault();
        _toggle(key);
      }
      return;
    }
    if (!onToggle && event.key == 'ArrowLeft') {
      // From a document, Left goes to the header of its folder or section.
      final web.Element? list = active?.closest('ul[data-section-list]');
      final String? key = list?.getAttribute('data-section-list');
      if (key == null) return;
      event.preventDefault();
      (web.document.querySelector('#animus-sidebar .animus-tree-toggle[data-section="$key"]') as web.HTMLElement?)?.focus();
      return;
    }
    if (event.key != 'ArrowDown' && event.key != 'ArrowUp') return;
    // Documents step to documents; a header steps to the next row of any kind.
    final List<web.HTMLElement> items = _focusables(onToggle ? '.animus-tree-item, .animus-tree-toggle' : '.animus-tree-item');
    final int at = items.indexWhere((web.HTMLElement item) => identical(item, active));
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
              classes: 'animus-side-link is-primary${route is NewDocumentRoute && !route.conversation ? ' is-selected' : ''}',
              const <Widget>[Component.text('New document')],
            ),
            dom.a(
              href: NewDocumentRoute.conversation(workspace.id).format(),
              classes: 'animus-side-link${route is NewDocumentRoute && route.conversation ? ' is-selected' : ''}',
              const <Widget>[Component.text('New conversation')],
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
              attributes: <String, String>{'aria-keyshortcuts': '${platformAriaKeys(const <String>['Mod+F'])} /'},
              onChanged: (String value) => setState(() => _filter = value),
            ),
          ]),
          if (nothingMatches)
            dom.p(classes: 'animus-muted animus-doc-search-empty', attributes: const <String, String>{'role': 'status'}, <Widget>[
              Component.text('Nothing matches "${_filter.trim()}".'),
            ]),
          if (groups.isNotEmpty)
            dom.div(classes: 'animus-tree-tools', <Widget>[
              // On phones the drawer keeps Tab and Escape for its own stops.
              if (!compact)
                const dom.span(classes: 'animus-tree-tools-label', <Widget>[
                  InfoTip(
                    tip: 'Every document in this workspace by kind. What you collapse stays collapsed in this browser.',
                    docs: Docs.editorDocuments,
                    child: Component.text('By kind'),
                  ),
                ]),
              Button.ghost(
                label: 'Collapse all',
                size: ButtonSize.small,
                disabled: filter.isNotEmpty,
                onPressed: () => _store(_allKeys()),
              ),
              Button.ghost(
                label: 'Expand all',
                size: ButtonSize.small,
                disabled: filter.isNotEmpty || _collapsed.isEmpty,
                onPressed: () => _store(<String>{}),
              ),
            ]),
          if (workspace.server != null && workspace.documents.isEmpty) _nothingOpen(workspace),
          dom.nav(classes: 'animus-tree', attributes: const <String, String>{'aria-label': 'Documents by kind'}, groups),
        ],
      ),
    ]);
  }

  /// A just-paired server's workspace: nothing is copied in until opened
  /// from the server catalog, so say so and offer to open everything.
  Widget _nothingOpen(WorkspaceSnapshot workspace) {
    final LiveSession? session = component.session;
    final int count = session == null || !session.catalogLoaded ? 0 : session.catalog.length;
    return dom.div(classes: 'animus-sidebar-empty', attributes: const <String, String>{'data-testid': 'sidebar-nothing-open'}, <Widget>[
      dom.p(classes: 'animus-muted', <Widget>[
        Component.text(count == 0
            ? 'Nothing open yet. Documents join this workspace as you open them from the server catalog.'
            : 'Nothing open yet. The server has $count document${count == 1 ? '' : 's'}.'),
      ]),
      if (count > 0)
        Button.secondary(
          label: 'Open all $count',
          size: ButtonSize.small,
          disabled: !session!.online || !session.owned || session.busy != null,
          onPressed: () => session.openDocuments(session.catalog),
        ),
      dom.a(href: WorkspaceRoute(workspace.id).format(), classes: 'animus-side-link', const <Widget>[Component.text('Pick from the catalog')]),
    ]);
  }

  /// A collapsible header: a button naming the section with its count.
  Widget _header(String key, String label, int count, {required bool open, int level = 0}) => dom.button(
        classes: 'animus-tree-toggle${level > 0 ? ' is-folder' : ''}',
        styles: level > 0 ? dom.Styles(raw: <String, String>{'padding-inline-start': '${0.25 + level * 0.75}rem'}) : null,
        attributes: <String, String>{
          'type': 'button',
          'aria-expanded': '$open',
          'aria-controls': _listId(key),
          'data-section': key,
        },
        events: <String, void Function(web.Event)>{'click': (web.Event _) => _toggle(key)},
        <Widget>[
          dom.span(classes: 'animus-tree-chevron${open ? ' is-open' : ''}', attributes: const <String, String>{'aria-hidden': 'true'}, <Widget>[ArcaneIcon.chevronRight(size: IconSize.sm)]),
          dom.span(classes: 'animus-tree-label', <Widget>[Component.text(label)]),
          dom.span(classes: 'animus-count', attributes: <String, String>{'aria-label': '$count document${count == 1 ? '' : 's'}'}, <Widget>[Component.text('$count')]),
        ],
      );

  static String _listId(String key) => 'animus-tree-${key.replaceAll(RegExp(r'[^A-Za-z0-9_-]+'), '-')}';

  Widget _group(WorkspaceSnapshot workspace, DocumentKind kind, DocumentRoute? selected, bool Function(AnimusDocument document) matches) {
    final List<AnimusDocument> documents = <AnimusDocument>[for (final AnimusDocument document in workspace.documents) if (document.kind == kind) document];
    final int count = documents.length;
    final String key = sectionKey(kind.wireName);
    final bool searching = _filter.trim().isNotEmpty;
    final bool open = searching || !_collapsed.contains(key);
    final List<AnimusDocument> shown = <AnimusDocument>[for (final AnimusDocument document in documents) if (matches(document)) document];
    return dom.div(classes: 'animus-tree-group', attributes: <String, String>{'data-kind': kind.wireName}, <Widget>[
      dom.div(classes: 'animus-tree-head', <Widget>[
        dom.h3(<Widget>[_header(key, kind.label, count, open: open)]),
        if (kind == DocumentKind.npc)
          dom.a(
            href: NpcListRoute(workspace.id).format(),
            classes: 'animus-tree-browse${component.route is NpcListRoute ? ' is-selected' : ''}',
            const <Widget>[Component.text('Browse')],
          ),
        if (kind == DocumentKind.recording && count > 0)
          dom.a(
            href: RecordingListRoute(workspace.id).format(),
            classes: 'animus-tree-browse${component.route is RecordingListRoute ? ' is-selected' : ''}',
            attributes: const <String, String>{'aria-label': 'Browse recordings'},
            const <Widget>[Component.text('Browse')],
          ),
      ]),
      if (open)
        foldered.contains(kind)
            ? _list(key, kind, folderTree(shown, (AnimusDocument document) => document.id), selected, searching, 0)
            : _list(key, kind, <TreeNode<AnimusDocument>>[for (final AnimusDocument document in shown) TreeLeaf<AnimusDocument>(document)], selected, searching, 0),
    ]);
  }

  Widget _list(String key, DocumentKind kind, List<TreeNode<AnimusDocument>> nodes, DocumentRoute? selected, bool searching, int level) {
    return dom.ul(id: _listId(key), attributes: <String, String>{'role': 'list', 'data-section-list': key}, <Widget>[
      for (final TreeNode<AnimusDocument> node in nodes)
        switch (node) {
          TreeLeaf<AnimusDocument>(:final AnimusDocument item) => dom.li(<Widget>[_item(kind, item, selected, level)]),
          TreeFolder<AnimusDocument>() => dom.li(classes: 'animus-tree-folder', <Widget>[
              _header(folderKey(kind.wireName, node.path), node.name, node.count, open: searching || !_collapsed.contains(folderKey(kind.wireName, node.path)), level: level + 1),
              if (searching || !_collapsed.contains(folderKey(kind.wireName, node.path)))
                _list(folderKey(kind.wireName, node.path), kind, node.children, selected, searching, level + 1),
            ]),
        },
    ]);
  }

  Widget _item(DocumentKind kind, AnimusDocument document, DocumentRoute? selected, int level) {
    final WorkspaceSnapshot workspace = component.workspace;
    final LiveSession? session = component.session;
    final bool current = selected?.kind == kind && selected?.documentId == document.id;
    return dom.a(
      href: DocumentRoute(workspace.id, kind, document.id, selected?.view ?? ViewMode.visual).format(),
      classes: 'animus-tree-item${current ? ' is-selected' : ''}',
      styles: level > 0 ? dom.Styles(raw: <String, String>{'padding-inline-start': '${0.5 + level * 0.75}rem'}) : null,
      attributes: <String, String>{if (current) 'aria-current': 'page'},
      <Widget>[
        dom.span(classes: 'animus-tree-title', <Widget>[Component.text(guiPlainText(document.title))]),
        if (document.title != document.id) dom.span(classes: 'animus-tree-id', <Widget>[Component.text(document.id)]),
        if (!document.valid)
          dom.span(
            classes: 'animus-badge is-error',
            attributes: <String, String>{'title': '${errorCount(document)} problem${errorCount(document) == 1 ? '' : 's'}'},
            <Widget>[Component.text('${errorCount(document)}')],
          ),
        if (component.hasCodeDraft?.call(kind, document.id) ?? false)
          const dom.span(
            classes: 'animus-badge is-draft',
            attributes: <String, String>{'data-code-draft': 'true', 'title': 'Code draft not applied'},
            <Widget>[Component.text('code')],
          ),
        if (session != null && workspace.server != null && draftBadge(session, kind, document.id) != null)
          dom.span(
            classes: 'animus-badge is-draft',
            attributes: <String, String>{'data-draft': draftBadge(session, kind, document.id)!},
            <Widget>[Component.text(draftBadge(session, kind, document.id)!)],
          ),
      ],
    );
  }
}
