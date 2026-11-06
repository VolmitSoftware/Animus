/// Convert Citizens, Shopkeepers, dtlTraders and Denizen data: saves read from disk are converted in this browser to
/// the same staged imports the server's converter writes, reviewed here, and downloaded as zips that unzip into the
/// data folder. Converting never uploads; only the Import on a server panel sends files, and only when asked.
library;

import 'dart:async';
import 'dart:convert';
import 'dart:js_interop';
import 'dart:typed_data';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../logic/citizens/citizens_converter.dart';
import '../logic/citizens/citizens_review.dart';
import '../logic/citizens/denizen_folder.dart';
import '../logic/denizen/denizen_converter.dart';
import '../logic/shopkeepers/shopkeepers_converter.dart';
import '../services/browser.dart';
import '../services/server/server_imports.dart';
import '../state/server_hub.dart';
import 'server/server_import_panel.dart';

@JS('animusCitizensResult')
external set _citizensResult(JSString? value);

@JS('animusShopkeepersResult')
external set _shopkeepersResult(JSString? value);

@JS('animusDenizenResult')
external set _denizenResult(JSString? value);

/// The screen's name, also on the home page button that opens it.
const String converterTitle = 'Convert Citizens, Shopkeepers, dtlTraders and Denizen data';

/// A field status from a Citizens NPC report, in words.
String fieldStatusLabel(String status) => switch (status) {
      'CONVERTED' => 'Converted',
      'REQUIRES_REVIEW' => 'Converted, check it',
      'UNSUPPORTED_PRESERVED' => 'Kept, not run',
      'REQUIRES_INTEGRATION' => 'Needs data from another plugin',
      _ => status.toLowerCase().replaceAll('_', ' '),
    };

/// What a failed conversion means and what to do next.
String conversionProblem(String what, String message) =>
    '$message${message.endsWith('.') ? '' : '.'} Check that this is the $what the plugin wrote (not a copy edited by hand), then choose it again.';

/// Lets the user pick a folder; every file in it, as text, by its picked path.
Future<Map<String, String>?> _pickTextFolder() {
  final Completer<Map<String, String>?> completer = Completer<Map<String, String>?>();
  final web.HTMLInputElement input = web.document.createElement('input') as web.HTMLInputElement
    ..type = 'file'
    ..multiple = true;
  input.setAttribute('webkitdirectory', '');
  input.style.display = 'none';
  input.addEventListener('change', ((web.Event _) async {
    final web.FileList? list = input.files;
    final Map<String, String> files = <String, String>{};
    for (int index = 0; index < (list?.length ?? 0); index++) {
      final web.File? file = list!.item(index);
      if (file == null) continue;
      final String path = file.webkitRelativePath.isEmpty ? file.name : file.webkitRelativePath;
      files[path] = (await file.text().toDart).toDart;
    }
    input.remove();
    if (!completer.isCompleted) completer.complete(files);
  }).toJS);
  input.addEventListener('cancel', ((web.Event _) {
    input.remove();
    if (!completer.isCompleted) completer.complete(null);
  }).toJS);
  web.document.body?.append(input);
  input.click();
  return completer.future;
}

class CitizensScreen extends StatefulWidget {
  const CitizensScreen({required this.hub, super.key});

  final ServerHub hub;

  @override
  State<CitizensScreen> createState() => _CitizensScreenState();
}

class _CitizensScreenState extends State<CitizensScreen> {
  String? _fileName;
  Uint8List? _bytes;
  String _worlds = '';
  CitizensReview? _review;
  String? _error;
  bool _busy = false;
  (String, Uint8List)? _shopSave;
  (String, Uint8List)? _shopConfig;
  ShopkeepersPreview? _shops;
  String? _shopError;
  List<(String, Uint8List)> _traders = const <(String, Uint8List)>[];
  List<String> _tradersSkipped = const <String>[];
  String? _denizenName;
  DenizenFolder? _denizen;
  DenizenPreview? _denizenPreview;
  String? _denizenError;

  static const String _yaml = '.yml,.yaml,application/x-yaml,text/yaml';

  Future<void> _choose() async {
    final (String, Uint8List)? picked = await pickFile(_yaml);
    if (picked == null) return;
    setState(() {
      _fileName = picked.$1;
      _bytes = picked.$2;
      _review = null;
      _error = null;
      // Links and bindings were checked against the previous Citizens save.
      _shops = null;
      _denizenPreview = null;
    });
    _citizensResult = null;
    _shopkeepersResult = null;
    _denizenResult = null;
  }

  Future<void> _chooseShops(bool config) async {
    final (String, Uint8List)? picked = await pickFile(_yaml);
    if (picked == null) return;
    setState(() {
      if (config) {
        _shopConfig = picked;
      } else {
        _shopSave = picked;
      }
      _shops = null;
      _shopError = null;
    });
    _shopkeepersResult = null;
  }

  Future<void> _convertShops() async {
    final (String, Uint8List)? save = _shopSave;
    if (save == null) return;
    setState(() {
      _busy = true;
      _shopError = null;
      _shops = null;
    });
    await Future<void>.delayed(const Duration(milliseconds: 16));
    try {
      final (String, Uint8List)? config = _shopConfig;
      final Uint8List? citizens = _bytes;
      final ShopkeepersPreview preview = convertShopkeepers(
        decodeShopkeepersSource(save.$2, 'save.yml'),
        config: config == null ? null : decodeShopkeepersSource(config.$2, 'config.yml'),
        citizens: citizens == null ? null : decodeShopkeepersSource(citizens, 'saves.yml'),
      );
      _shopkeepersResult = jsonEncode(<String, Object?>{
        'importId': preview.id,
        'outputHash': preview.outputHash(),
        'shops': preview.shops,
        'npcs': preview.npcs,
        'issues': preview.issueCount,
        'offers': <String, int>{'trade': preview.tradeOffers, 'static': preview.staticOffers, 'skipped': preview.skippedOffers},
        'links': <String, int>{'found': preview.linksFound, 'notFound': preview.linksMissing, 'unchecked': preview.linksUnchecked},
      }).toJS;
      setState(() => _shops = preview);
    } on ShopkeepersImportException catch (failure) {
      _shopkeepersResult = jsonEncode(<String, Object?>{'error': failure.message}).toJS;
      setState(() => _shopError = conversionProblem('Shopkeepers save.yml', failure.message));
    } on Object catch (failure) {
      _shopkeepersResult = jsonEncode(<String, Object?>{'error': '$failure'}).toJS;
      setState(() => _shopError = conversionProblem('Shopkeepers save.yml', 'The converter could not read the file'));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _chooseTraders() async {
    final List<(String, Uint8List)>? picked = await pickFiles('.yml,.yaml,.dat');
    if (picked == null) return;
    setState(() {
      _traders = <(String, Uint8List)>[
        for (final (String name, Uint8List bytes) in picked)
          if (dtlTradersName(name) != null) (dtlTradersName(name)!, bytes),
      ];
      _tradersSkipped = <String>[
        for (final (String name, Uint8List _) in picked)
          if (dtlTradersName(name) == null) name,
      ];
    });
  }

  Future<void> _chooseDenizen() async {
    final Map<String, String>? files = await _pickTextFolder();
    if (files == null) return;
    final String? first = files.keys.firstOrNull;
    setState(() {
      _denizenName = first == null ? null : (first.contains('/') ? first.substring(0, first.indexOf('/')) : first);
      _denizen = denizenFolderOf(files);
      _denizenPreview = null;
      _denizenError = _denizen!.empty
          ? 'That folder holds no Denizen scripts, server_flags.dat or player_flags. Choose plugins/Denizen, the folder with scripts/ inside it.'
          : null;
    });
    _denizenResult = null;
  }

  Future<void> _convertDenizen() async {
    final DenizenFolder? folder = _denizen;
    if (folder == null || folder.empty) return;
    setState(() {
      _busy = true;
      _denizenError = null;
      _denizenPreview = null;
    });
    await Future<void>.delayed(const Duration(milliseconds: 16));
    try {
      final Uint8List? citizens = _bytes;
      final DenizenPreview preview = convertDenizen(DenizenInput(
        scripts: folder.input.scripts,
        citizens: citizens == null ? null : decodeSaves(citizens),
        serverFlags: folder.input.serverFlags,
        playerFlags: folder.input.playerFlags,
      ));
      _denizenResult = jsonEncode(<String, Object?>{
        'importId': preview.id,
        'outputHash': preview.outputHash(),
        'scripts': folder.scripts,
        'behaviors': preview.behaviors,
        'bindings': preview.bindings,
        'preserved': preview.preserved,
        'flagsImported': preview.flagsImported,
        'flagsPreserved': preview.flagsPreserved,
        'skipped': folder.skipped.length,
      }).toJS;
      setState(() => _denizenPreview = preview);
    } on DenizenImportException catch (failure) {
      _denizenResult = jsonEncode(<String, Object?>{'error': failure.message}).toJS;
      setState(() => _denizenError = conversionProblem('Denizen folder', failure.message));
    } on CitizensImportException catch (failure) {
      _denizenResult = jsonEncode(<String, Object?>{'error': failure.message}).toJS;
      setState(() => _denizenError = conversionProblem('Citizens saves.yml', failure.message));
    } on Object catch (failure) {
      _denizenResult = jsonEncode(<String, Object?>{'error': '$failure'}).toJS;
      setState(() => _denizenError = conversionProblem('Denizen folder', 'The converter could not read the scripts'));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  /// The chosen files as server uploads: the Citizens save, the dtlTraders files, then the Shopkeepers save and config.
  List<ImportUpload> get _uploads => <ImportUpload>[
        if (_bytes != null) ImportUpload(ImportFile.citizens, _bytes!),
        for (final (String name, Uint8List bytes) in _traders) ImportUpload(ImportFile.dtltraders, bytes, name: name),
        if (_shopSave != null) ImportUpload(ImportFile.shopkeepers, _shopSave!.$2),
        if (_shopConfig != null) ImportUpload(ImportFile.shopkeepersConfig, _shopConfig!.$2),
      ];

  static const String _unzipNext = 'Unzip it into the Animus data folder, then run /animus import citizens on the server to review and apply it.';

  void _downloadShops(ShopkeepersPreview preview) {
    downloadBytes('animus-shopkeepers-import-${preview.id.substring(0, 12)}.zip', shopkeepersBundleZip(preview));
    ArcaneSonner.success(_unzipNext);
  }

  void _downloadDenizen(DenizenPreview preview) {
    downloadBytes('animus-denizen-import-${preview.id.substring(0, 12)}.zip', denizenBundleZip(preview));
    ArcaneSonner.success('Unzip it into the Animus data folder beside the Citizens import, then run /animus import citizens on the server.');
  }

  Future<void> _convert() async {
    final Uint8List? bytes = _bytes;
    if (bytes == null) return;
    setState(() {
      _busy = true;
      _error = null;
      _review = null;
    });
    // Let the busy state paint before the conversion holds the thread.
    await Future<void>.delayed(const Duration(milliseconds: 16));
    try {
      final CitizensPreview preview = convertCitizens(decodeSaves(bytes), parseWorldMappings(_worlds));
      final CitizensReview review = CitizensReview.of(preview);
      _citizensResult = jsonEncode(review.aggregate()).toJS;
      setState(() => _review = review);
    } on CitizensImportException catch (failure) {
      _citizensResult = jsonEncode(<String, Object?>{'error': failure.message}).toJS;
      setState(() => _error = conversionProblem('Citizens saves.yml', failure.message));
    } on Object catch (failure) {
      _citizensResult = jsonEncode(<String, Object?>{'error': '$failure'}).toJS;
      setState(() => _error = conversionProblem('Citizens saves.yml', 'The converter could not read the file'));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _download(CitizensReview review) {
    downloadBytes('animus-citizens-import-${review.preview.id.substring(0, 12)}.zip', citizensBundleZip(review.preview));
    ArcaneSonner.success(_unzipNext);
  }

  @override
  Widget build(BuildContext context) {
    final CitizensReview? review = _review;
    return dom.main_(classes: 'animus-home animus-citizens', <Widget>[
      dom.section(classes: 'animus-panel animus-home-intro', <Widget>[
        const dom.h1(<Widget>[Component.text(converterTitle)]),
        const dom.p(<Widget>[
          Component.text('Each file is converted in this browser with the same rules as the server converter, and converting never uploads it. '
              'Only Upload and stage, under Import on a server below, sends the files to a paired server. '
              'Imported NPCs stay dormant and combat stays off until reviewed on the server.'),
        ]),
        dom.div(classes: 'animus-row', <Widget>[
          Button.secondary(label: 'Choose saves.yml', icon: ArcaneIcon.fileUp(), disabled: _busy, onPressed: _choose),
          dom.span(classes: 'animus-muted animus-citizens-file', <Widget>[Component.text(_fileName ?? 'No Citizens save chosen')]),
        ]),
        TextArea(
          label: 'World mappings (optional)',
          placeholder: 'old_world=new_world, one per line',
          helperText: 'Renames worlds in locations, waypoints and Sentinel points, as the server converter does.',
          rows: 3,
          value: _worlds,
          fullWidth: true,
          onChanged: (String value) => setState(() => _worlds = value),
        ),
        dom.div(classes: 'animus-row', <Widget>[
          Button.primary(label: _busy ? 'Converting' : 'Convert', icon: ArcaneIcon.play(), disabled: _busy || _bytes == null, onPressed: _convert),
          const dom.a(href: '#/', classes: 'animus-muted', <Widget>[Component.text('Back to workspaces')]),
        ]),
      ]),
      if (_error != null)
        dom.section(classes: 'animus-panel animus-citizens-error', attributes: const <String, String>{'role': 'alert'}, <Widget>[
          const dom.h2(<Widget>[Component.text('Not converted')]),
          dom.p(<Widget>[Component.text(_error!)]),
        ]),
      if (review != null) ..._report(review),
      _shopkeepersPanel(),
      _tradersPanel(),
      _denizenPanel(),
      ServerImportPanel(hub: component.hub, uploads: _uploads, worlds: _worldMap),
    ]);
  }

  Map<String, String> get _worldMap {
    try {
      return parseWorldMappings(_worlds);
    } on CitizensImportException {
      return const <String, String>{};
    }
  }

  Widget _tradersPanel() {
    return dom.section(classes: 'animus-panel', attributes: const <String, String>{'aria-label': 'dtlTraders files'}, <Widget>[
      const dom.h2(<Widget>[Component.text('dtlTraders files (optional, server import)')]),
      const dom.p(classes: 'animus-muted', <Widget>[
        Component.text('Pick guis.yml, config.yml, messages.yml, data.dat and the shop files from the dtlTraders data folder. '
            'A file named <shop>.yml is the shop file shops/<shop>/<shop>.yml. The server converts them with the Citizens save.'),
      ]),
      dom.div(classes: 'animus-row', <Widget>[
        Button.secondary(label: 'Choose dtlTraders files', icon: ArcaneIcon.fileUp(), disabled: _busy, onPressed: _chooseTraders),
        dom.span(classes: 'animus-muted animus-citizens-file', <Widget>[
          Component.text(_traders.isEmpty ? 'No dtlTraders files chosen' : _traders.map(((String, Uint8List) file) => file.$1).join(', ')),
        ]),
      ]),
      if (_tradersSkipped.isNotEmpty)
        dom.p(classes: 'animus-muted', attributes: const <String, String>{'data-testid': 'traders-skipped'}, <Widget>[
          Component.text('Skipped ${_tradersSkipped.join(', ')}: dtlTraders does not use ${_tradersSkipped.length == 1 ? 'that name' : 'those names'}. '
              'Pick guis.yml, config.yml, messages.yml, data.dat or a <shop>.yml shop file.'),
        ]),
    ]);
  }

  Widget _denizenPanel() {
    final DenizenFolder? folder = _denizen;
    final DenizenPreview? preview = _denizenPreview;
    return dom.section(classes: 'animus-panel', attributes: const <String, String>{'aria-label': 'Denizen folder'}, <Widget>[
      const dom.h2(<Widget>[Component.text('Denizen folder (optional)')]),
      const dom.p(classes: 'animus-muted', <Widget>[
        Component.text('Pick the plugins/Denizen folder: its scripts/, server_flags.dat and player_flags/. Assignments bind to the NPCs of the '
            'Citizens save chosen above. Converted behaviors stay off until reviewed on the server.'),
      ]),
      dom.div(classes: 'animus-row', <Widget>[
        Button.secondary(label: 'Choose Denizen folder', icon: ArcaneIcon.folderOpen(), disabled: _busy, onPressed: _chooseDenizen),
        dom.span(classes: 'animus-muted animus-citizens-file', attributes: const <String, String>{'data-testid': 'denizen-folder'}, <Widget>[
          Component.text(folder == null
              ? 'No Denizen folder chosen'
              : '${_denizenName ?? 'Folder'}: ${folder.scripts} script${folder.scripts == 1 ? '' : 's'}'
                  '${folder.input.serverFlags == null ? '' : ', server flags'}'
                  '${folder.input.playerFlags.isEmpty ? '' : ', ${folder.input.playerFlags.length} player flag file${folder.input.playerFlags.length == 1 ? '' : 's'}'}'),
        ]),
      ]),
      if (folder != null && folder.skipped.isNotEmpty)
        dom.details(attributes: const <String, String>{'data-testid': 'denizen-skipped'}, <Widget>[
          dom.summary(<Widget>[Component.text('${folder.skipped.length} file${folder.skipped.length == 1 ? '' : 's'} not read')]),
          const dom.p(classes: 'animus-muted', <Widget>[Component.text('The converter reads .dsc and .yml under scripts/, server_flags.dat and player_flags/*.dat only.')]),
          dom.ul(classes: 'animus-citizens-lines', <Widget>[for (final String path in folder.skipped.take(50)) dom.li(<Widget>[Component.text(path)])]),
        ]),
      if (folder != null && !folder.empty && _bytes == null)
        const dom.p(classes: 'animus-muted', <Widget>[
          Component.text('No Citizens save is chosen, so assignments bind to no NPC. Choose saves.yml above to bind them.'),
        ]),
      dom.div(classes: 'animus-row', <Widget>[
        Button.primary(
          label: _busy ? 'Converting' : 'Convert Denizen',
          icon: ArcaneIcon.play(),
          disabled: _busy || folder == null || folder.empty,
          onPressed: _convertDenizen,
        ),
      ]),
      if (_denizenError != null)
        dom.div(classes: 'animus-citizens-error', attributes: const <String, String>{'role': 'alert'}, <Widget>[
          const dom.h3(<Widget>[Component.text('Denizen not converted')]),
          dom.p(<Widget>[Component.text(_denizenError!)]),
        ]),
      if (preview != null) ...<Widget>[
        _stats(<(int, String)>[
          (preview.behaviors, 'behaviors made'),
          (preview.bindings, 'NPCs with an assignment'),
          (preview.flagsImported, 'flag values imported'),
          (preview.preserved, 'items kept, not run'),
          (preview.flagsPreserved, 'flag values kept, not run'),
        ]),
        dom.p(classes: 'animus-muted animus-citizens-id', <Widget>[
          const Component.text('Import '),
          dom.code(<Widget>[Component.text(preview.id)]),
        ]),
        dom.div(classes: 'animus-row', <Widget>[
          Button.secondary(label: 'Download Denizen bundle', icon: ArcaneIcon.download(), onPressed: () => _downloadDenizen(preview)),
        ]),
      ],
    ]);
  }

  Widget _shopkeepersPanel() {
    final ShopkeepersPreview? shops = _shops;
    return dom.section(classes: 'animus-panel', attributes: const <String, String>{'aria-label': 'Shopkeepers shops'}, <Widget>[
      const dom.h2(<Widget>[Component.text('Shopkeepers shops (optional)')]),
      const dom.p(classes: 'animus-muted', <Widget>[
        Component.text('Pick a Shopkeepers save.yml, and its config.yml for the currency items. When a Citizens saves.yml is chosen above, '
            'Citizens shopkeepers are checked against it. Converted shops stay closed until reviewed on the server.'),
      ]),
      dom.div(classes: 'animus-row', <Widget>[
        Button.secondary(label: 'Choose Shopkeepers save.yml', icon: ArcaneIcon.fileUp(), disabled: _busy, onPressed: () => _chooseShops(false)),
        dom.span(classes: 'animus-muted animus-citizens-file', <Widget>[Component.text(_shopSave?.$1 ?? 'No Shopkeepers save chosen')]),
      ]),
      dom.div(classes: 'animus-row', <Widget>[
        Button.secondary(label: 'Choose config.yml', icon: ArcaneIcon.settings(), disabled: _busy, onPressed: () => _chooseShops(true)),
        dom.span(classes: 'animus-muted animus-citizens-file', <Widget>[Component.text(_shopConfig?.$1 ?? 'Default currency (emerald)')]),
      ]),
      dom.div(classes: 'animus-row', <Widget>[
        Button.primary(label: _busy ? 'Converting' : 'Convert shops', icon: ArcaneIcon.store(), disabled: _busy || _shopSave == null, onPressed: _convertShops),
      ]),
      if (_shopError != null)
        dom.div(classes: 'animus-citizens-error', attributes: const <String, String>{'role': 'alert'}, <Widget>[
          const dom.h3(<Widget>[Component.text('Shops not converted')]),
          dom.p(<Widget>[Component.text(_shopError!)]),
        ]),
      if (shops != null) ...<Widget>[
        _stats(<(int, String)>[
          (shops.shops, 'shops'),
          (shops.tradeOffers, 'trading offers'),
          (shops.staticOffers, 'shown only'),
          (shops.skippedOffers, 'kept in source.yml'),
          (shops.npcs, 'new mob NPCs'),
          (shops.issueCount, 'issues'),
          (shops.linksFound, 'Citizens links found'),
          (shops.linksMissing, 'Citizens links not found'),
          (shops.linksUnchecked, 'Citizens links unchecked'),
        ]),
        dom.p(classes: 'animus-muted animus-citizens-id', <Widget>[
          const Component.text('Import '),
          dom.code(<Widget>[Component.text(shops.id)]),
        ]),
        dom.div(classes: 'animus-row', <Widget>[
          Button.secondary(label: 'Download shops bundle', icon: ArcaneIcon.download(), onPressed: () => _downloadShops(shops)),
        ]),
      ],
    ]);
  }

  List<Widget> _report(CitizensReview review) => <Widget>[
        dom.section(classes: 'animus-panel', attributes: const <String, String>{'aria-label': 'Conversion summary'}, <Widget>[
          const dom.h2(<Widget>[Component.text('Review')]),
          if (review.warnings.isNotEmpty || review.issues.isNotEmpty)
            dom.p(classes: 'animus-citizens-lead', <Widget>[
              Component.text(<String>[
                if (review.warnings.isNotEmpty) '${review.warnings.length} migration warning${review.warnings.length == 1 ? '' : 's'} to act on',
                if (review.issues.isNotEmpty) '${review.issues.length} record${review.issues.length == 1 ? '' : 's'} that will not load',
              ].join(' and ')),
              const Component.text('. Read them below before you apply.'),
            ])
          else
            const dom.p(classes: 'animus-citizens-lead', <Widget>[Component.text('Every record converted to a valid definition, with no warnings.')]),
          _stats(<(int, String)>[
            (review.preview.records, 'records'),
            (review.preview.valid, 'valid definitions'),
            (review.preview.skins, 'unique skins'),
            (review.resolvedReferences, 'references resolved'),
            (review.unresolvedReferences, 'references unresolved'),
            (review.remappedLocations, 'locations remapped'),
            (review.conversions['routes'] ?? 0, 'patrol routes'),
            (review.conversions['lookClose'] ?? 0, 'look-close'),
            (review.conversions['textBehaviors'] ?? 0, 'text behaviors'),
            (review.conversions['commandBehaviors'] ?? 0, 'command behaviors'),
            (review.conversions['skinsKept'] ?? 0, 'skins kept for review'),
          ]),
          dom.p(classes: 'animus-muted animus-citizens-id', <Widget>[
            const Component.text('Import '),
            dom.code(<Widget>[Component.text(review.preview.id)]),
          ]),
        ]),
        _list('Migration warnings', review.warnings, 'No migration warnings.'),
        _list('Records that will not load', review.issues, 'Every record is a valid definition.'),
        dom.section(classes: 'animus-panel', <Widget>[
          const dom.h2(<Widget>[Component.text('Fields by conversion status')]),
          _counts(<String, int>{for (final MapEntry<String, int> entry in review.classification.entries) fieldStatusLabel(entry.key): entry.value}, 'Status', 'Fields',
              code: false),
        ]),
        dom.section(classes: 'animus-panel', <Widget>[
          dom.h2(<Widget>[Component.text('Traits (${review.traits.length})')]),
          _counts(review.traits, 'Trait', 'Records'),
        ]),
        dom.section(classes: 'animus-panel', <Widget>[
          dom.h2(<Widget>[Component.text('Body types (${review.types.length})')]),
          _counts(review.types, 'Type', 'Records'),
        ]),
        dom.section(classes: 'animus-panel', attributes: const <String, String>{'aria-label': 'Download the conversion'}, <Widget>[
          dom.div(classes: 'animus-row', <Widget>[
            Button.primary(label: 'Download data-folder bundle', icon: ArcaneIcon.download(), onPressed: () => _download(review)),
            const dom.span(classes: 'animus-muted', <Widget>[Component.text(_unzipNext)]),
          ]),
        ]),
      ];

  /// Counts as tiles, the non-zero ones only; the zeros share one quiet line.
  Widget _stats(List<(int, String)> counts) {
    final List<(int, String)> shown = <(int, String)>[for (final (int, String) count in counts) if (count.$1 != 0) count];
    final List<String> zero = <String>[for (final (int, String) count in counts) if (count.$1 == 0) count.$2];
    return dom.div(<Widget>[
      if (shown.isNotEmpty)
        dom.div(classes: 'animus-stats', <Widget>[
          for (final (int value, String label) in shown)
            dom.div(classes: 'animus-stat', <Widget>[
              dom.strong(<Widget>[Component.text('$value')]),
              dom.span(<Widget>[Component.text(label)]),
            ]),
        ]),
      if (zero.isNotEmpty) dom.p(classes: 'animus-muted animus-stats-none', <Widget>[Component.text('None: ${zero.join(', ')}.')]),
    ]);
  }

  Widget _counts(Map<String, int> counts, String key, String value, {bool code = true}) {
    final List<String> names = counts.keys.toList()..sort((String a, String b) => counts[b]!.compareTo(counts[a]!) != 0 ? counts[b]!.compareTo(counts[a]!) : a.compareTo(b));
    return dom.div(classes: 'animus-citizens-table', <Widget>[
      dom.table(<Widget>[
        dom.thead(<Widget>[
          dom.tr(<Widget>[
            dom.th(<Widget>[Component.text(key)]),
            dom.th(<Widget>[Component.text(value)]),
          ]),
        ]),
        dom.tbody(<Widget>[
          for (final String name in names)
            dom.tr(<Widget>[
              dom.td(<Widget>[if (code) dom.code(<Widget>[Component.text(name)]) else Component.text(name)]),
              dom.td(<Widget>[Component.text('${counts[name]}')]),
            ]),
        ]),
      ]),
    ]);
  }

  Widget _list(String title, List<String> lines, String empty) => dom.section(classes: 'animus-panel', <Widget>[
        dom.details(attributes: lines.length <= 20 && lines.isNotEmpty ? const <String, String>{'open': ''} : const <String, String>{}, <Widget>[
          dom.summary(<Widget>[Component.text('$title (${lines.length})')]),
          if (lines.isEmpty)
            dom.p(classes: 'animus-muted', <Widget>[Component.text(empty)])
          else
            dom.ul(classes: 'animus-citizens-lines', <Widget>[
              for (final String line in lines.take(500)) dom.li(<Widget>[Component.text(line)]),
              if (lines.length > 500) dom.li(classes: 'animus-muted', <Widget>[Component.text('${lines.length - 500} more in report.json')]),
            ]),
        ]),
      ]);
}
