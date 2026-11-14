import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/jaspr.dart' show Component;

import 'components/shell.dart';
import 'config/animus_stylesheet.dart';
import 'model/server_binding.dart';
import 'services/browser.dart';
import 'services/server/server_connection.dart';
import 'services/server/server_profiles.dart';
import 'services/terrain/terrain_link.dart';
import 'state/editor_store.dart';
import 'state/live_session.dart';
import 'state/session_owner.dart';
import 'state/server_hub.dart';
import 'state/workspace_route.dart';

class AnimusApp extends StatefulWidget {
  const AnimusApp({required this.store, required this.hub, super.key});

  final EditorStore store;
  final ServerHub hub;

  @override
  State<AnimusApp> createState() => _AnimusAppState();
}

class _AnimusAppState extends State<AnimusApp> {
  Brightness _brightness = loadBrightness();
  EditorRoute _route = EditorRoute.parse(currentHash());
  void Function()? _stopHash;
  LiveSession? _session;

  @override
  void initState() {
    super.initState();
    component.store.addListener(_storeChanged);
    component.hub.addListener(_storeChanged);
    _stopHash = onHashChange((String hash) => _routeTo(EditorRoute.parse(hash)));
    toast.addListener(_dropRepeatedToasts);
    _routeTo(_route);
  }

  /// The same message twice shows once: the older copy goes.
  void _dropRepeatedToasts() {
    final Set<String> seen = <String>{};
    for (final ToastData shown in toast.toasts) {
      if (!seen.add('${shown.variant.name}:${shown.message}')) toast.dismiss(shown.id);
    }
  }

  @override
  void dispose() {
    component.store.removeListener(_storeChanged);
    component.hub.removeListener(_storeChanged);
    toast.removeListener(_dropRepeatedToasts);
    _stopHash?.call();
    _session?.dispose();
    super.dispose();
  }

  void _storeChanged() {
    // Another tab deleted the open workspace: the banner says so on the home page.
    if (component.store.takeWorkspaceGone()) navigate(const HomeRoute().format());
    _syncSession();
    if (mounted) setState(() {});
  }

  /// Keeps one live session for the open workspace when it belongs to a
  /// paired server.
  void _syncSession() {
    final ServerBinding? binding = component.store.workspace?.server;
    final String? serverId = binding != null && component.hub.server(binding.serverId) != null ? binding.serverId : null;
    if (_session?.serverId == serverId) return;
    final LiveSession? previous = _session;
    _session = null;
    previous?.dispose();
    if (serverId == null) {
      TerrainLink.detach();
      return;
    }
    final ServerConnection connection = component.hub.connection(serverId);
    // The tab that owns the server's workspace runs live editing; see StoreOwner.
    final LiveSession session = LiveSession(store: component.store, connection: connection, serverId: serverId, owner: StoreOwner(component.store));
    TerrainLink.attach(serverId, connection, elsewhere: () => session.waiting);
    session.addListener(() {
      if (mounted) setState(() {});
    });
    session.notices.listen(_toast);
    // Set before start: starting notifies listeners, which land here again.
    _session = session;
    // The session connects only in the tab that owns the workspace.
    session.start();
    _loadRouted();
  }

  /// A server document opened by its address is read when it is not here yet.
  void _loadRouted() {
    final EditorRoute route = _route;
    if (route is DocumentRoute) _session?.ensureLoaded(<DocumentRef>[DocumentRef(route.kind, route.documentId)]);
  }

  /// The session's toast on screen: a new one replaces it, so they never pile up.
  String? _noticeToast;

  void _toast(LiveNotice notice) {
    if (!notice.toast) return;
    final String? shown = _noticeToast;
    if (shown != null) ArcaneSonner.dismiss(shown);
    _noticeToast = switch (notice.level) {
      NoticeLevel.success => ArcaneSonner.success(notice.message),
      NoticeLevel.warning => ArcaneSonner.warning(notice.message),
      NoticeLevel.error => ArcaneSonner.error(notice.message),
      NoticeLevel.info => ArcaneSonner.info(notice.message),
    };
  }

  Future<void> _routeTo(EditorRoute route) async {
    setState(() => _route = route);
    final String? workspaceId = switch (route) {
      WorkspaceRoute(:final String workspaceId) => workspaceId,
      DocumentRoute(:final String workspaceId) => workspaceId,
      NpcListRoute(:final String workspaceId) => workspaceId,
      RecordingListRoute(:final String workspaceId) => workspaceId,
      NewDocumentRoute(:final String workspaceId) => workspaceId,
      KindListRoute(:final String workspaceId) => workspaceId,
      SyncRoute(:final String workspaceId) => workspaceId,
      CitizensRoute(:final String? workspaceId) => workspaceId,
      _ => null,
    };
    if (route is ServerRoute) {
      final ServerProfile? profile = component.hub.server(route.serverId);
      if (profile == null) {
        component.store.closeWorkspace();
        await component.store.refreshWorkspaces();
        return;
      }
      await component.store.openServerWorkspace(ServerHub.workspaceIdOf(profile.id), profile.name, component.hub.bindingFor(profile));
      return;
    }
    if (workspaceId == null) {
      component.store.closeWorkspace();
      await component.store.refreshWorkspaces();
    } else {
      await component.store.openWorkspace(workspaceId);
      _loadRouted();
    }
  }

  void _toggleTheme() {
    final Brightness next = _brightness == Brightness.dark ? Brightness.light : Brightness.dark;
    saveBrightness(next);
    setState(() => _brightness = next);
  }

  @override
  Widget build(BuildContext context) {
    return ArcaneApp(
      brightness: _brightness,
      stylesheet: const AnimusStylesheet(),
      // Client mode hydrates every callback, and the CSP allows no inline script.
      includeFallbackScripts: false,
      home: Component.fragment(<Widget>[
        EditorShell(
          store: component.store,
          hub: component.hub,
          session: _session,
          route: _route,
          brightness: _brightness,
          onToggleTheme: _toggleTheme,
          onNavigate: (EditorRoute route) => navigate(route.format()),
        ),
        // Toasts stack above the save bar (48 px) with a 16 px gap, under
        // menus, suggestion lists and modals, and let clicks through; only
        // their own buttons take one.
        const ArcaneSonner(edgeOffset: 64, layer: 35, passThrough: true),
      ]),
    );
  }
}
