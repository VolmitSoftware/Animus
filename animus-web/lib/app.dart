import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:arcane_jaspr_ascii/arcane_jaspr_ascii.dart';
import 'package:jaspr/jaspr.dart' show Component;

import 'components/shell.dart';
import 'services/browser.dart';
import 'state/editor_store.dart';
import 'state/workspace_route.dart';

class AnimusApp extends StatefulWidget {
  const AnimusApp({required this.store, super.key});

  final EditorStore store;

  @override
  State<AnimusApp> createState() => _AnimusAppState();
}

class _AnimusAppState extends State<AnimusApp> {
  Brightness _brightness = loadBrightness();
  EditorRoute _route = EditorRoute.parse(currentHash());
  void Function()? _stopHash;

  @override
  void initState() {
    super.initState();
    component.store.addListener(_storeChanged);
    _stopHash = onHashChange((String hash) => _routeTo(EditorRoute.parse(hash)));
    _routeTo(_route);
  }

  @override
  void dispose() {
    component.store.removeListener(_storeChanged);
    _stopHash?.call();
    super.dispose();
  }

  void _storeChanged() {
    if (mounted) setState(() {});
  }

  Future<void> _routeTo(EditorRoute route) async {
    setState(() => _route = route);
    final String? workspaceId = switch (route) {
      WorkspaceRoute(:final String workspaceId) => workspaceId,
      DocumentRoute(:final String workspaceId) => workspaceId,
      _ => null,
    };
    if (workspaceId == null) {
      component.store.closeWorkspace();
      await component.store.refreshWorkspaces();
    } else {
      await component.store.openWorkspace(workspaceId);
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
      stylesheet: const AsciiStylesheet(),
      // Client mode hydrates every callback, and the CSP allows no inline script.
      includeFallbackScripts: false,
      home: Component.fragment(<Widget>[
        EditorShell(
          store: component.store,
          route: _route,
          brightness: _brightness,
          onToggleTheme: _toggleTheme,
          onNavigate: (EditorRoute route) => navigate(route.format()),
        ),
        const ArcaneSonner(),
      ]),
    );
  }
}
