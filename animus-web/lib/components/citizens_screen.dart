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

import '../config/docs_links.dart';
import '../logic/citizens/citizens_converter.dart';
import '../logic/citizens/citizens_review.dart';
import '../logic/citizens/converted_workspace.dart';
import '../logic/citizens/denizen_folder.dart';
import '../logic/denizen/denizen_converter.dart';
import '../logic/shopkeepers/shopkeepers_converter.dart';
import '../model/animus_document.dart';
import '../services/browser.dart';
import '../services/server/server_imports.dart';
import '../state/editor_store.dart';
import '../state/server_hub.dart';
import '../state/workspace_repository.dart';
import '../state/workspace_route.dart';
import 'fold.dart';
import 'info_tip.dart';
import 'server/server_import_panel.dart';

@JS('animusCitizensResult')
external set _citizensResult(JSString? value);

@JS('animusShopkeepersResult')
external set _shopkeepersResult(JSString? value);

@JS('animusDenizenResult')
external set _denizenResult(JSString? value);

/// The screen's heading, also the label of the home page button that opens it.
const String converterTitle = 'Import Citizens and others';

/// A field status from a Citizens NPC report, in words.
String fieldStatusLabel(String status) => switch (status) {
      'CONVERTED' => 'Converted',
      'REQUIRES_REVIEW' => 'Converted, check it',
      'UNSUPPORTED_PRESERVED' => 'Kept, not run',
      'REQUIRES_INTEGRATION' => 'Needs another plugin',
      _ => status.toLowerCase().replaceAll('_', ' '),
    };

/// What a failed conversion means and what to do next.
String conversionProblem(String what, String message) =>
    '$message${message.endsWith('.') ? '' : '.'} Choose the unedited $what again.';

/// Lets the user pick a folder; every file in it, as text, by its picked path.
Future<Map<String, String>?> _pickTextFolder() {
  final Completer<Map<String, String>?> completer = Completer<Map<String, String>?>();
  final web.HTMLInputElement input = web.document.createElement('input') as web.HTMLInputElement
    ..type = 'file'
    ..multiple = true;
  input.setAttribute('webkitdirectory', '');
  input.style.display = 'none';
  Future<void> read() async {
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
  }

  input.addEventListener('change', ((web.Event _) => unawaited(read())).toJS);
  input.addEventListener('cancel', ((web.Event _) {
    input.remove();
    if (!completer.isCompleted) completer.complete(null);
  }).toJS);
  web.document.body?.append(input);
  input.click();
  return completer.future;
}

class CitizensScreen extends StatefulWidget {
  const CitizensScreen({required this.hub, this.store, this.onNavigate, super.key});

  final ServerHub hub;

  /// Where Open as workspace and Send to server create the converted
  /// workspace; without both those next steps are not offered.
  final EditorStore? store;
  final void Function(EditorRoute route)? onNavigate;

  @override
  State<CitizensScreen> createState() => _CitizensScreenState();
}

class _CitizensScreenState extends State<CitizensScreen> {
  String? _fileName;
  Uint8List? _bytes;
  String _worlds = '';
  CitizensReview? _review;

  /// The converted NPCs' names by Citizens id, for the warnings.
  Map<String, String> _names = const <String, String>{};
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
  String? _importState;
  bool _opening = false;

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
          ? 'No Denizen scripts or flags in that folder. Choose plugins/Denizen.'
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

  static const String _unzipNext = 'Unzip into the Animus data folder, then run /animus import citizens.';

  void _downloadShops(ShopkeepersPreview preview) {
    downloadBytes('animus-shopkeepers-import-${preview.id.substring(0, 12)}.zip', shopkeepersBundleZip(preview));
    ArcaneSonner.success(_unzipNext);
  }

  void _downloadDenizen(DenizenPreview preview) {
    downloadBytes('animus-denizen-import-${preview.id.substring(0, 12)}.zip', denizenBundleZip(preview));
    ArcaneSonner.success('Unzip into the Animus data folder beside the Citizens import, then run /animus import citizens.');
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
      setState(() {
        _review = review;
        _names = citizensNames(preview);
      });
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

  ConverterStep get _step => converterNextStep(
        citizensChosen: _bytes != null,
        citizensConverted: _review != null,
        shopsChosen: _shopSave != null,
        shopsConverted: _shops != null,
        denizenChosen: _denizen != null && !_denizen!.empty,
        denizenConverted: _denizenPreview != null,
        importState: _importState,
      );

  ButtonVariant _variant(ConverterStep step) => _step == step ? ButtonVariant.primary : ButtonVariant.secondary;

  /// Opens everything converted so far as a new workspace, then shows it, or
  /// its Sync page when [send] is set and a server is paired.
  Future<void> _openWorkspace({required bool send}) async {
    final EditorStore? store = component.store;
    final void Function(EditorRoute route)? navigateTo = component.onNavigate;
    if (store == null || navigateTo == null) return;
    setState(() => _opening = true);
    try {
      final WorkspaceSnapshot made = await store.openFiles(
        convertedWorkspaceName(citizens: _review != null, shops: _shops != null, denizen: _denizenPreview != null),
        convertedWorkspaceFiles(citizens: _review?.preview, shops: _shops, denizen: _denizenPreview),
      );
      final int invalid = made.documents.where((AnimusDocument document) => !document.valid).length;
      ArcaneSonner.success('Opened ${made.name}: ${made.documents.length} documents${invalid == 0 ? '' : ', $invalid to repair'}.');
      if (!send) {
        navigateTo(WorkspaceRoute(made.id));
      } else if (component.hub.servers.isEmpty) {
        ArcaneSonner.info('Pair a server under Servers, then send ${made.name} from its Sync page.');
        navigateTo(const HomeRoute());
      } else {
        navigateTo(SyncRoute(made.id));
      }
      focusMainHeading();
    } on Object catch (failure) {
      ArcaneSonner.error('Could not open the conversion ($failure). Download the bundle instead.');
    } finally {
      if (mounted) setState(() => _opening = false);
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
        const dom.h1(<Widget>[
          InfoTip(
            tip: 'Files convert in this browser and are never uploaded unless you use Upload and stage. Apply on the server installs the NPCs with everything that converted turned on.',
            docs: Docs.citizens,
            child: Component.text(converterTitle),
          ),
        ]),
        dom.div(classes: 'animus-row', <Widget>[
          Button.secondary(label: 'Choose saves.yml', icon: ArcaneIcon.fileUp(), size: ButtonSize.small, disabled: _busy, onPressed: _choose),
          dom.span(classes: 'animus-muted animus-citizens-file', <Widget>[Component.text(_fileName ?? 'No Citizens save chosen')]),
        ]),
        TextArea(
          label: 'World mappings (optional)',
          placeholder: 'old_world=new_world, one per line',
          rows: 3,
          value: _worlds,
          fullWidth: true,
          onChanged: (String value) => setState(() => _worlds = value),
        ),
        dom.div(classes: 'animus-row', <Widget>[
          Button(
            label: _busy ? 'Converting' : 'Convert',
            icon: ArcaneIcon.play(),
            variant: _variant(ConverterStep.convertCitizens),
            size: ButtonSize.small,
            disabled: _busy || _bytes == null,
            onPressed: _convert,
          ),
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
      // Without a Citizens review the next steps follow the converted shops or scripts.
      if (review == null && _canOpen) _nextPanel(null),
      ServerImportPanel(
        hub: component.hub,
        uploads: _uploads,
        worlds: _worldMap,
        onState: (String state) => setState(() => _importState = state),
      ),
    ]);
  }

  bool get _canOpen => component.store != null && component.onNavigate != null && (_review != null || _shops != null || _denizenPreview != null);

  /// Open as workspace and Send to server, with the bundle download when a
  /// Citizens save was converted ([review]).
  Widget _nextPanel(CitizensReview? review) => dom.section(classes: 'animus-panel', attributes: const <String, String>{'aria-label': 'Next steps', 'data-testid': 'citizens-next'}, <Widget>[
        const dom.h2(<Widget>[
          InfoTip(
            tip: 'Open the converted NPCs, shops, GUIs and behaviors as a workspace in this browser to review and edit them, then send them to a paired server.',
            docs: Docs.citizensOutput,
            child: Component.text('Next steps'),
          ),
        ]),
        dom.div(classes: 'animus-row', <Widget>[
          Button(
            label: _opening ? 'Opening' : 'Open as workspace',
            icon: ArcaneIcon.folderOpen(),
            variant: _variant(ConverterStep.openWorkspace),
            size: ButtonSize.small,
            disabled: _busy || _opening,
            onPressed: () => _openWorkspace(send: false),
          ),
          Button.secondary(
            label: 'Send to server',
            icon: ArcaneIcon.upload(),
            size: ButtonSize.small,
            disabled: _busy || _opening,
            onPressed: () => _openWorkspace(send: true),
          ),
          if (review != null)
            Button.secondary(label: 'Download data-folder bundle', icon: ArcaneIcon.download(), size: ButtonSize.small, onPressed: () => _download(review)),
        ]),
        if (review != null) const dom.p(classes: 'animus-muted', <Widget>[Component.text('With the bundle: $_unzipNext')]),
      ]);

  Map<String, String> get _worldMap {
    try {
      return parseWorldMappings(_worlds);
    } on CitizensImportException {
      return const <String, String>{};
    }
  }

  Widget _tradersPanel() {
    return dom.section(classes: 'animus-panel', attributes: const <String, String>{'aria-label': 'dtlTraders files'}, <Widget>[
      const dom.h2(<Widget>[
        InfoTip(
          tip: 'Only the server converts these, with the Citizens save. Pick guis.yml, config.yml, messages.yml, data.dat and <shop>.yml files.',
          docs: Docs.dtlTraders,
          child: Component.text('dtlTraders files'),
        ),
      ]),
      dom.div(classes: 'animus-row', <Widget>[
        Button.secondary(label: 'Choose dtlTraders files', icon: ArcaneIcon.fileUp(), size: ButtonSize.small, disabled: _busy, onPressed: _chooseTraders),
        dom.span(classes: 'animus-muted animus-citizens-file', <Widget>[
          Component.text(_traders.isEmpty ? 'No dtlTraders files chosen' : _traders.map(((String, Uint8List) file) => file.$1).join(', ')),
        ]),
      ]),
      if (_tradersSkipped.isNotEmpty)
        dom.p(classes: 'animus-muted', attributes: const <String, String>{'data-testid': 'traders-skipped'}, <Widget>[
          Component.text('Skipped ${_tradersSkipped.join(', ')}: not ${_tradersSkipped.length == 1 ? 'a dtlTraders file' : 'dtlTraders files'}.'),
        ]),
    ]);
  }

  Widget _denizenPanel() {
    final DenizenFolder? folder = _denizen;
    final DenizenPreview? preview = _denizenPreview;
    return dom.section(classes: 'animus-panel', attributes: const <String, String>{'aria-label': 'Denizen folder'}, <Widget>[
      const dom.h2(<Widget>[
        InfoTip(
          tip: 'Pick plugins/Denizen. Assignments bind to NPCs in the Citizens save above, and apply on the server turns the behaviors on.',
          docs: Docs.denizen,
          child: Component.text('Denizen folder'),
        ),
      ]),
      dom.div(classes: 'animus-row', <Widget>[
        Button.secondary(label: 'Choose Denizen folder', icon: ArcaneIcon.folderOpen(), size: ButtonSize.small, disabled: _busy, onPressed: _chooseDenizen),
        dom.span(classes: 'animus-muted animus-citizens-file', attributes: const <String, String>{'data-testid': 'denizen-folder'}, <Widget>[
          Component.text(folder == null
              ? 'No Denizen folder chosen'
              : '${_denizenName ?? 'Folder'}: ${folder.scripts} script${folder.scripts == 1 ? '' : 's'}'
                  '${folder.input.serverFlags == null ? '' : ', server flags'}'
                  '${folder.input.playerFlags.isEmpty ? '' : ', ${folder.input.playerFlags.length} player flag file${folder.input.playerFlags.length == 1 ? '' : 's'}'}'),
        ]),
      ]),
      if (folder != null && folder.skipped.isNotEmpty)
        Fold(attributes: const <String, String>{'data-testid': 'denizen-skipped'}, summary: <Widget>[
          Component.text('${folder.skipped.length} file${folder.skipped.length == 1 ? '' : 's'} not read'),
        ], children: <Widget>[
          const dom.p(classes: 'animus-muted', <Widget>[Component.text('Only .dsc and .yml under scripts/, server_flags.dat and player_flags/*.dat are read.')]),
          dom.ul(classes: 'animus-citizens-lines', <Widget>[for (final String path in folder.skipped.take(50)) dom.li(<Widget>[Component.text(path)])]),
        ]),
      if (folder != null && !folder.empty && _bytes == null)
        const dom.p(classes: 'animus-muted', <Widget>[
          Component.text('No Citizens save chosen above, so assignments bind to no NPC.'),
        ]),
      dom.div(classes: 'animus-row', <Widget>[
        Button(
          label: _busy ? 'Converting' : 'Convert Denizen',
          icon: ArcaneIcon.play(),
          variant: _variant(ConverterStep.convertDenizen),
          size: ButtonSize.small,
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
          (preview.behaviors, 'behaviors'),
          (preview.bindings, 'NPCs bound'),
          (preview.flagsImported, 'flags imported'),
          (preview.preserved, 'items kept, not run'),
          (preview.flagsPreserved, 'flags kept, not run'),
        ]),
        dom.p(classes: 'animus-muted animus-citizens-id', <Widget>[
          const Component.text('Import id '),
          dom.code(<Widget>[Component.text(preview.id)]),
        ]),
        dom.div(classes: 'animus-row', <Widget>[
          Button.secondary(label: 'Download Denizen bundle', icon: ArcaneIcon.download(), size: ButtonSize.small, onPressed: () => _downloadDenizen(preview)),
        ]),
      ],
    ]);
  }

  Widget _shopkeepersPanel() {
    final ShopkeepersPreview? shops = _shops;
    return dom.section(classes: 'animus-panel', attributes: const <String, String>{'aria-label': 'Shopkeepers shops'}, <Widget>[
      const dom.h2(<Widget>[
        InfoTip(
          tip: 'Add config.yml for its currency items. Citizens shopkeepers are checked against the saves.yml above, and shops open once applied.',
          docs: Docs.shopkeepers,
          child: Component.text('Shopkeepers shops'),
        ),
      ]),
      dom.div(classes: 'animus-row', <Widget>[
        Button.secondary(label: 'Choose Shopkeepers save.yml', icon: ArcaneIcon.fileUp(), size: ButtonSize.small, disabled: _busy, onPressed: () => _chooseShops(false)),
        dom.span(classes: 'animus-muted animus-citizens-file', <Widget>[Component.text(_shopSave?.$1 ?? 'No Shopkeepers save chosen')]),
      ]),
      dom.div(classes: 'animus-row', <Widget>[
        Button.secondary(label: 'Choose config.yml', icon: ArcaneIcon.settings(), size: ButtonSize.small, disabled: _busy, onPressed: () => _chooseShops(true)),
        dom.span(classes: 'animus-muted animus-citizens-file', <Widget>[Component.text(_shopConfig?.$1 ?? 'Default currency (emerald)')]),
      ]),
      dom.div(classes: 'animus-row', <Widget>[
        Button(
          label: _busy ? 'Converting' : 'Convert shops',
          icon: ArcaneIcon.store(),
          variant: _variant(ConverterStep.convertShops),
          size: ButtonSize.small,
          disabled: _busy || _shopSave == null,
          onPressed: _convertShops,
        ),
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
          const Component.text('Import id '),
          dom.code(<Widget>[Component.text(shops.id)]),
        ]),
        dom.div(classes: 'animus-row', <Widget>[
          Button.secondary(label: 'Download shops bundle', icon: ArcaneIcon.download(), size: ButtonSize.small, onPressed: () => _downloadShops(shops)),
        ]),
      ],
    ]);
  }

  List<Widget> _report(CitizensReview review) => <Widget>[
        dom.section(classes: 'animus-panel', attributes: const <String, String>{'aria-label': 'Conversion summary'}, <Widget>[
          const dom.h2(<Widget>[
            InfoTip(
              tip: 'What the conversion found in saves.yml. The server checks every NPC again when it applies the import.',
              docs: Docs.citizens,
              child: Component.text('Review'),
            ),
          ]),
          if (review.warnings.isNotEmpty || review.issues.isNotEmpty)
            dom.p(classes: 'animus-citizens-lead', <Widget>[
              Component.text(<String>[
                if (review.warnings.isNotEmpty) '${review.warnings.length} migration warning${review.warnings.length == 1 ? '' : 's'}',
                if (review.issues.isNotEmpty) '${review.issues.length} record${review.issues.length == 1 ? '' : 's'} that will not load',
              ].join(' and ')),
              const Component.text('. Read them before you install the import.'),
            ])
          else
            const dom.p(classes: 'animus-citizens-lead', <Widget>[Component.text('All records valid, no warnings.')]),
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
            const Component.text('Import id '),
            dom.code(<Widget>[Component.text(review.preview.id)]),
          ]),
        ]),
        if (_canOpen) _nextPanel(review),
        _list('Migration warnings', <String>[for (final String warning in review.warnings) plainWarning(warning, _names)], 'None.'),
        _list('Records that will not load', <String>[for (final String issue in review.issues) plainWarning(issue, _names)], 'None.'),
        dom.section(classes: 'animus-panel', <Widget>[
          const dom.h2(<Widget>[
            InfoTip(
              tip: 'How each Citizens field carried over: converted, converted but worth checking, kept but not run, or needing another plugin.',
              docs: Docs.citizensOutput,
              child: Component.text('Fields by conversion status'),
            ),
          ]),
          _counts(<String, int>{for (final MapEntry<String, int> entry in review.classification.entries) fieldStatusLabel(entry.key): entry.value}, 'Status', 'Fields',
              code: false),
        ]),
        dom.section(classes: 'animus-panel', <Widget>[
          dom.h2(<Widget>[
            withTip(Component.text('Traits (${review.traits.length})'), 'The Citizens traits in the save and how many NPCs carry each.', docs: Docs.citizens),
          ]),
          _counts(review.traits, 'Trait', 'Records'),
        ]),
        dom.section(classes: 'animus-panel', <Widget>[
          dom.h2(<Widget>[
            withTip(Component.text('Body types (${review.types.length})'), 'The entity types the NPCs use, such as player or villager.', docs: Docs.citizens),
          ]),
          _counts(review.types, 'Type', 'Records'),
        ]),
        if (!_canOpen)
          dom.section(classes: 'animus-panel', attributes: const <String, String>{'aria-label': 'Download the conversion'}, <Widget>[
            dom.div(classes: 'animus-row', <Widget>[
              Button.secondary(label: 'Download data-folder bundle', icon: ArcaneIcon.download(), size: ButtonSize.small, onPressed: () => _download(review)),
              const dom.span(classes: 'animus-muted', <Widget>[Component.text(_unzipNext)]),
            ]),
          ]),
      ];

  /// Counts as tiles, the non-zero ones only.
  Widget _stats(List<(int, String)> counts) {
    final List<(int, String)> shown = <(int, String)>[for (final (int, String) count in counts) if (count.$1 != 0) count];
    return dom.div(<Widget>[
      if (shown.isNotEmpty)
        dom.div(classes: 'animus-stats', <Widget>[
          for (final (int value, String label) in shown)
            dom.div(classes: 'animus-stat', <Widget>[
              dom.strong(<Widget>[Component.text('$value')]),
              dom.span(<Widget>[Component.text(label)]),
            ]),
        ]),
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
        Fold(open: lines.length <= 20 && lines.isNotEmpty, summary: <Widget>[Component.text('$title (${lines.length})')], children: <Widget>[
          if (lines.isEmpty)
            dom.p(classes: 'animus-empty-line', <Widget>[Component.text(empty)])
          else
            dom.ul(classes: 'animus-citizens-lines', <Widget>[
              for (final String line in lines.take(500)) dom.li(<Widget>[Component.text(line)]),
              if (lines.length > 500) dom.li(classes: 'animus-muted', <Widget>[Component.text('${lines.length - 500} more in report.json')]),
            ]),
        ]),
      ]);
}
