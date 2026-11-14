/// Builds the [SuggestScope] for a document: the bundled registry lists
/// (fetched once per session), the workspace's ids and flag and quest
/// names, the capability catalog, and the paired server's lists over them.
library;

import 'dart:convert';
import 'dart:js_interop';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:web/web.dart' as web;

import '../../logic/inspect/completion_catalog.dart';
import '../../logic/suggest/completion_sources.dart';
import '../../mc/assets/mc_pack_manifest.dart';
import '../../model/animus_document.dart';
import '../mc/mc_asset_loader.dart';
import 'suggest_scope.dart';

/// Registries the bundled lists hold in full; the others (sounds named by
/// subtitles, lang-derived lists) are offered but not checked.
const Set<RegistryKind> _bundledComplete = <RegistryKind>{RegistryKind.item, RegistryKind.block};

Future<CompletionSources>? _bundled;

Future<CompletionSources> _loadBundled() async {
  try {
    final String url = '${mcPackBaseUrl(McAssetLoader.version)}/registries.json';
    final web.Response response = await web.window.fetch(url.toJS).toDart;
    if (!response.ok) return CompletionSources.empty;
    final Map<RegistryKind, List<String>> lists = parseRegistries(jsonDecode((await response.text().toDart).toDart));
    return CompletionSources(registries: lists, complete: <RegistryKind>{for (final RegistryKind kind in _bundledComplete) if (lists.containsKey(kind)) kind});
  } on Object {
    return CompletionSources.empty;
  }
}

class SuggestSources extends StatefulWidget {
  const SuggestSources({required this.documents, required this.child, this.catalog, this.lookups, super.key});

  final List<AnimusDocument> documents;
  final CompletionCatalog? catalog;

  /// The paired server's lists, when paired and it sent them.
  final ServerLookups? lookups;
  final Widget child;

  @override
  State<SuggestSources> createState() => _SuggestSourcesState();
}

class _SuggestSourcesState extends State<SuggestSources> {
  CompletionSources _registries = CompletionSources.empty;
  final WorkspaceSourcesCache _cache = WorkspaceSourcesCache();
  CompletionSources? _built;
  CompletionSources? _builtOver;
  List<AnimusDocument>? _builtFrom;
  CompletionSources? _builtBase;
  CompletionSources? _baseRegistries;
  CompletionCatalog? _builtCatalog;
  ServerLookups? _builtLookups;

  @override
  void initState() {
    super.initState();
    (_bundled ??= _loadBundled()).then((CompletionSources loaded) {
      if (!mounted || identical(loaded, _registries)) return;
      setState(() {
        _registries = loaded;
        _built = null;
        _builtBase = null;
      });
    });
  }

  /// The registries, catalog and server lists, built again only when one
  /// of them changes.
  CompletionSources get _base {
    final CompletionSources? cached = _builtBase;
    if (cached != null && identical(_baseRegistries, _registries) && identical(_builtCatalog, component.catalog) && identical(_builtLookups, component.lookups)) {
      return cached;
    }
    CompletionSources base = _registries.copyWith(catalog: component.catalog);
    final ServerLookups? lookups = component.lookups;
    if (lookups != null) base = lookups.over(base);
    _baseRegistries = _registries;
    _builtCatalog = component.catalog;
    _builtLookups = lookups;
    return _builtBase = base;
  }

  /// The same instance while no id or name changes (an edit of one field),
  /// so the fields under the scope are not built again for it.
  CompletionSources get _sources {
    final CompletionSources? cached = _built;
    final CompletionSources base = _base;
    if (cached != null && identical(_builtFrom, component.documents) && identical(_builtOver, base)) return cached;
    _builtFrom = component.documents;
    _builtOver = base;
    return _built = _cache.sources(component.documents, base: base);
  }

  @override
  Widget build(BuildContext context) => SuggestScope(sources: _sources, child: component.child);
}
