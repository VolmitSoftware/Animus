/// Import (`#/citizens`): one wizard for Citizens, Shopkeepers, dtlTraders and
/// Denizen data, in four steps: Source, Files, Review, Result. Citizens,
/// Shopkeepers and Denizen convert in this browser and never upload; the
/// result opens as a workspace, goes to a paired server, or downloads as a
/// bundle. dtlTraders and "Already on my server" convert on a paired server,
/// which installs and rolls back the import itself.
library;

import 'dart:async';
import 'dart:convert';
import 'dart:js_interop';
import 'dart:typed_data';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:arcane_jaspr/html.dart' show HeadingLevel;
import 'package:jaspr/dom.dart' as dom;
import 'package:web/web.dart' as web;

import '../config/docs_links.dart';
import '../logic/citizens/citizens_converter.dart';
import '../logic/citizens/citizens_review.dart';
import '../logic/citizens/converted_workspace.dart';
import '../logic/citizens/denizen_folder.dart';
import '../logic/denizen/denizen_converter.dart';
import '../logic/plural.dart';
import '../logic/shopkeepers/shopkeepers_converter.dart';
import '../model/animus_document.dart';
import '../services/browser.dart';
import '../services/server/server_imports.dart';
import '../state/editor_store.dart';
import '../state/server_hub.dart';
import '../state/workspace_repository.dart';
import '../state/workspace_route.dart';
import '../ui/action_row.dart';
import '../ui/alert.dart';
import '../ui/confirm_dialog.dart';
import '../ui/field.dart';
import '../ui/mode.dart';
import '../ui/page_header.dart';
import '../ui/tabs.dart';
import 'advanced_notice.dart';
import 'offline/import_plan.dart';
import 'offline/import_review.dart';
import 'offline/page_parts.dart';
import 'server/server_import_panel.dart';
import '../ui/stack.dart';

@JS('animusCitizensResult')
external set _citizensResult(JSString? value);

@JS('animusShopkeepersResult')
external set _shopkeepersResult(JSString? value);

@JS('animusDenizenResult')
external set _denizenResult(JSString? value);

/// The page's heading, also its name in Search.
const String converterTitle = 'Import';

/// What a failed conversion means and what to do next.
String conversionProblem(String what, String message) => '$message${message.endsWith('.') ? '' : '.'} Choose the unedited $what again.';

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

const String _yaml = '.yml,.yaml,application/x-yaml,text/yaml';
const String _stepId = 'animus-import-step';
const String _whyId = 'animus-import-why';
const String _unzipNext = 'Unzip into the Animus data folder, then run /animus import citizens.';

class CitizensScreen extends StatefulWidget {
  const CitizensScreen({required this.hub, required this.store, required this.onNavigate, this.announce, this.landmark = true, super.key});

  final ServerHub hub;

  /// Where Open as workspace and Send to server create the converted
  /// workspace.
  final EditorStore store;
  final void Function(EditorRoute route) onNavigate;

  /// Says, in Simple, how many converted documents only Advanced lists.
  final bool Function(Arrival arrival, List<ArrivedDocument> documents, String workspaceId)? announce;

  /// The page's own main landmark; false inside a workspace, whose frame
  /// is the main.
  final bool landmark;

  @override
  State<CitizensScreen> createState() => _CitizensScreenState();
}

class _CitizensScreenState extends State<CitizensScreen> {
  ImportSource _source = ImportSource.citizens;
  int _step = 0;
  bool _converting = false;
  bool _opening = false;

  // Citizens.
  String? _fileName;
  Uint8List? _bytes;
  String _worlds = '';
  CitizensReview? _review;

  /// The converted NPCs' names by Citizens id, for the warnings.
  Map<String, String> _names = const <String, String>{};
  String? _error;

  // Shopkeepers.
  (String, Uint8List)? _shopSave;
  (String, Uint8List)? _shopConfig;
  ShopkeepersPreview? _shops;
  String? _shopError;

  // dtlTraders.
  List<(String, Uint8List)> _traders = const <(String, Uint8List)>[];
  List<String> _tradersSkipped = const <String>[];

  // Denizen.
  String? _denizenName;
  DenizenFolder? _denizen;
  DenizenPreview? _denizenPreview;
  String? _denizenError;

  late final ServerImportFlow _server = ServerImportFlow(component.hub, _changed);

  /// The server answered "nothing to import yet" to Check server.
  bool _serverEmpty = false;

  @override
  void initState() {
    super.initState();
    component.hub.addListener(_changed);
  }

  @override
  void dispose() {
    component.hub.removeListener(_changed);
    super.dispose();
  }

  void _changed() {
    if (mounted) setState(() {});
  }

  bool get _paired => component.hub.servers.isNotEmpty;

  String? _blocked(ImportSource source) => sourceBlocked(source, paired: _paired, canImport: _server.canImport);

  bool _hasResult(ImportSource source) => switch (source) {
        ImportSource.citizens => _review != null,
        ImportSource.shopkeepers => _shops != null,
        ImportSource.denizen => _denizenPreview != null,
        ImportSource.dtlTraders || ImportSource.server => (_server.review?.state ?? 'none') != 'none',
      };

  /// The furthest step the current source can show.
  int get _lastStep {
    if (_blocked(_source) != null) return 0;
    if (!_hasResult(_source)) return 1;
    if (_source.onServer) return _server.review!.state == 'applied' ? 3 : 2;
    return 3;
  }

  void _goTo(int step) {
    setState(() => _step = step.clamp(0, _lastStep));
    if (_step == 1 && _source.onServer) _server.connectIfIdle();
    _showStep();
  }

  /// After the step renders: the page back at its top and focus on the step,
  /// so a keyboard or screen reader starts at the new content.
  static void _showStep() => web.window.setTimeout((() {
        final web.HTMLElement? step = web.document.getElementById(_stepId) as web.HTMLElement?;
        if (step == null) return;
        final web.Element? page = step.closest('.ui-page');
        if (page != null) page.scrollTop = 0;
        step.focus(web.FocusOptions(preventScroll: true));
      }).toJS, 0.toJS);

  void _pickSource(ImportSource source) => setState(() {
        _source = source;
        _server.error = null;
        _serverEmpty = false;
      });

  // Choosing files.

  Future<void> _chooseCitizens() async {
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

  Future<void> _chooseShops({required bool config}) async {
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
      _denizenError = _denizen!.empty ? 'No Denizen scripts or flags in that folder. Choose plugins/Denizen.' : null;
    });
    _denizenResult = null;
  }

  Map<String, String> get _worldMap {
    try {
      return parseWorldMappings(_worlds);
    } on CitizensImportException {
      return const <String, String>{};
    }
  }

  /// The files a server source sends, in upload order: the Citizens save,
  /// the dtlTraders files, then the Shopkeepers save and config.
  List<ImportUpload> get _uploads => <ImportUpload>[
        if (_bytes != null) ImportUpload(ImportFile.citizens, _bytes!),
        if (_source == ImportSource.dtlTraders)
          for (final (String name, Uint8List bytes) in _traders) ImportUpload(ImportFile.dtltraders, bytes, name: name),
        if (_source == ImportSource.server && _shopSave != null) ImportUpload(ImportFile.shopkeepers, _shopSave!.$2),
        if (_source == ImportSource.server && _shopConfig != null) ImportUpload(ImportFile.shopkeepersConfig, _shopConfig!.$2),
      ];

  List<String> get _uploadProblems => <String>[
        for (final ImportUpload upload in _uploads)
          if (uploadProblem(upload, _server.path) != null) uploadProblem(upload, _server.path)!,
      ];

  String? get _convertBlocked => convertBlocked(
        _source,
        citizens: _bytes != null,
        shopSave: _shopSave != null,
        traders: _traders.length,
        denizen: _denizen != null && !_denizen!.empty,
        online: _server.online,
        problems: _uploadProblems,
      );

  // Converting.

  Future<void> _convert() async {
    setState(() => _converting = true);
    // Let the busy state paint before a conversion holds the thread.
    await Future<void>.delayed(const Duration(milliseconds: 16));
    bool done = false;
    try {
      switch (_source) {
        case ImportSource.citizens:
          done = _convertCitizens();
        case ImportSource.shopkeepers:
          done = _convertShops();
        case ImportSource.denizen:
          done = _convertDenizen();
        case ImportSource.dtlTraders || ImportSource.server:
          _serverEmpty = false;
          done = await _server.convert(_uploads, _worldMap, advanced: ModeScope.advanced(context));
      }
    } finally {
      if (mounted) setState(() => _converting = false);
    }
    if (done && mounted) _goTo(2);
  }

  bool _convertCitizens() {
    try {
      final CitizensPreview preview = convertCitizens(decodeSaves(_bytes!), parseWorldMappings(_worlds));
      final CitizensReview review = CitizensReview.of(preview);
      _citizensResult = jsonEncode(review.aggregate()).toJS;
      _review = review;
      _names = citizensNames(preview);
      _error = null;
      return true;
    } on CitizensImportException catch (failure) {
      _citizensResult = jsonEncode(<String, Object?>{'error': failure.message}).toJS;
      _error = conversionProblem('Citizens saves.yml', failure.message);
    } on Object catch (failure) {
      _citizensResult = jsonEncode(<String, Object?>{'error': '$failure'}).toJS;
      _error = conversionProblem('Citizens saves.yml', 'The converter could not read the file');
    }
    _review = null;
    return false;
  }

  bool _convertShops() {
    try {
      final (String, Uint8List)? config = _shopConfig;
      final Uint8List? citizens = _bytes;
      final ShopkeepersPreview preview = convertShopkeepers(
        decodeShopkeepersSource(_shopSave!.$2, 'save.yml'),
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
      _shops = preview;
      _shopError = null;
      return true;
    } on ShopkeepersImportException catch (failure) {
      _shopkeepersResult = jsonEncode(<String, Object?>{'error': failure.message}).toJS;
      _shopError = conversionProblem('Shopkeepers save.yml', failure.message);
    } on Object catch (failure) {
      _shopkeepersResult = jsonEncode(<String, Object?>{'error': '$failure'}).toJS;
      _shopError = conversionProblem('Shopkeepers save.yml', 'The converter could not read the file');
    }
    _shops = null;
    return false;
  }

  bool _convertDenizen() {
    final DenizenFolder folder = _denizen!;
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
      _denizenPreview = preview;
      _denizenError = null;
      return true;
    } on DenizenImportException catch (failure) {
      _denizenResult = jsonEncode(<String, Object?>{'error': failure.message}).toJS;
      _denizenError = conversionProblem('Denizen folder', failure.message);
    } on CitizensImportException catch (failure) {
      _denizenResult = jsonEncode(<String, Object?>{'error': failure.message}).toJS;
      _denizenError = conversionProblem('Citizens saves.yml', failure.message);
    } on Object catch (failure) {
      _denizenResult = jsonEncode(<String, Object?>{'error': '$failure'}).toJS;
      _denizenError = conversionProblem('Denizen folder', 'The converter could not read the scripts');
    }
    _denizenPreview = null;
    return false;
  }

  // Server steps.

  Future<void> _check() async {
    if (!await _server.refresh() || !mounted) return;
    final String state = _server.review?.state ?? 'none';
    setState(() => _serverEmpty = state == 'none');
    if (state != 'none') _goTo(state == 'applied' ? 3 : 2);
  }

  Future<void> _install() async {
    final ImportReview? review = _server.review;
    if (review == null) return;
    final String commands = review.commandNamespaces.isEmpty
        ? ''
        : ' Their commands run as before, so the server allows commands from ${joinWords(review.commandNamespaces)}.';
    final bool yes = await askToConfirm(
      title: 'Install this import on the server?',
      message: 'NPCs spawn as they were, with their patrols, combat, shops and other converted features on.$commands',
      confirmLabel: 'Install import',
    );
    if (yes && await _server.install() && mounted) _goTo(3);
  }

  Future<void> _rollback() async {
    final bool yes = await askToConfirm(
      title: 'Roll back this import?',
      message: 'Removes its NPCs, shops and behaviors from the server.',
      confirmLabel: 'Roll back',
      destructive: true,
    );
    if (yes && await _server.rollback() && mounted) _goTo(2);
  }

  void _openServer() {
    final String? id = _server.serverId;
    if (id != null) component.onNavigate(ServerRoute(id, const <String>[]));
  }

  // Next steps of a browser conversion.

  Map<String, Uint8List> get _convertedFiles => convertedWorkspaceFiles(citizens: _review?.preview, shops: _shops, denizen: _denizenPreview);

  /// Opens everything converted so far as a new workspace, then shows it, or
  /// its Send to server page when [send] is set.
  Future<void> _openWorkspace({required bool send}) async {
    final EditorStore store = component.store;
    final void Function(EditorRoute route) navigateTo = component.onNavigate;
    setState(() => _opening = true);
    try {
      final WorkspaceSnapshot made = await store.openFiles(
        convertedWorkspaceName(citizens: _review != null, shops: _shops != null, denizen: _denizenPreview != null),
        _convertedFiles,
      );
      final int invalid = made.documents.where((AnimusDocument document) => !document.valid).length;
      final bool told = component.announce?.call(Arrival.imported, arrivedOf(made.documents), made.id) ?? false;
      if (!told || invalid > 0) ArcaneSonner.success('Opened ${made.name}: ${countOf(made.documents.length, 'document')}${invalid == 0 ? '' : ', $invalid with problems'}.');
      navigateTo(send ? SyncRoute(made.id) : WorkspaceRoute(made.id));
      focusMainHeading();
    } on Object catch (failure) {
      ArcaneSonner.error('Could not open the conversion ($failure). Download the bundle instead.');
    } finally {
      if (mounted) setState(() => _opening = false);
    }
  }

  void _downloadCitizens(CitizensReview review) {
    downloadBytes('animus-citizens-import-${review.preview.id.substring(0, 12)}.zip', citizensBundleZip(review.preview));
    ArcaneSonner.success(_unzipNext);
  }

  void _downloadShops(ShopkeepersPreview preview) {
    downloadBytes('animus-shopkeepers-import-${preview.id.substring(0, 12)}.zip', shopkeepersBundleZip(preview));
    ArcaneSonner.success(_unzipNext);
  }

  void _downloadDenizen(DenizenPreview preview) {
    downloadBytes('animus-denizen-import-${preview.id.substring(0, 12)}.zip', denizenBundleZip(preview));
    ArcaneSonner.success('Unzip into the Animus data folder beside the Citizens import, then run /animus import citizens.');
  }

  // The page.

  @override
  Widget build(BuildContext context) {
    final int last = _lastStep;
    if (_step > last) _step = last;
    final List<Widget> page = <Widget>[
      const PageHeader(title: converterTitle),
      AppTabs(
        id: 'animus-import-steps',
        label: 'Import steps',
        tabs: importSteps,
        selected: _step,
        disabled: <int>{for (int step = last + 1; step < importSteps.length; step++) step},
        onSelect: _goTo,
      ),
      dom.section(
        id: _stepId,
        attributes: <String, String>{'tabindex': '-1', 'aria-label': importSteps[_step]},
        <Widget>[
          stack(spacing: 16, switch (_step) {
            0 => _sourceStep(),
            1 => _filesStep(context),
            2 => _reviewStep(),
            _ => _resultStep(context),
          }),
        ],
      ),
      _footer(),
    ];
    return component.landmark ? dom.main_(classes: 'animus-import', page) : dom.div(classes: 'animus-import', page);
  }

  List<Widget> _sourceStep() => <Widget>[
        ArcaneRadioGroup<ImportSource>(
          id: 'animus-import-source',
          label: 'Import from',
          value: _source,
          // The plain radio list: the dot beside its label, no boxes in the page.
          variant: RadioGroupVariant.standard,
          options: <RadioOption<ImportSource>>[
            for (final ImportSource source in ImportSource.values)
              RadioOption<ImportSource>(
                value: source,
                label: source.label,
                description: _blocked(source) == null ? source.line : '${source.line} ${_blocked(source)}',
                disabled: _blocked(source) != null,
              ),
          ],
          onChanged: _pickSource,
        ),
      ];

  /// A file to choose: what is chosen on the left, the button on the right.
  Widget _file(String label, {required String? chosen, required String button, required void Function() onPick, String? hint, String? testId}) => Field(
        label: label,
        hint: hint,
        child: ActionRow(
          leading: <Widget>[
            Expanded(
              child: dom.span(attributes: <String, String>{'data-testid': ?testId, 'title': ?chosen}, <Widget>[
                Text.truncated(chosen ?? 'None chosen', size: FontSize.sm, color: TextColor.muted),
              ]),
            ),
          ],
          secondary: <RowAction>[RowAction(button, _converting ? null : onPick)],
        ),
      );

  Widget _citizensFile({String? hint}) => _file('Citizens save', chosen: _fileName, button: 'Choose saves.yml', onPick: _chooseCitizens, hint: hint, testId: 'citizens-file');

  Widget _worldsField() => Field(
        label: 'World mappings',
        forId: 'animus-import-worlds',
        hint: 'old=new, one per line',
        tip: 'Moves NPCs from a world that was renamed.',
        docs: Docs.citizens,
        child: TextArea(
          id: 'animus-import-worlds',
          placeholder: 'old_world=new_world',
          rows: 3,
          value: _worlds,
          onChanged: (String value) => setState(() => _worlds = value),
        ),
      );

  String? get _sourceError => switch (_source) {
        ImportSource.citizens => _error,
        ImportSource.shopkeepers => _shopError,
        ImportSource.denizen => _denizenError,
        _ => _server.error,
      };

  List<Widget> _filesStep(BuildContext context) {
    final List<String> problems = _source.onServer ? _uploadProblems : const <String>[];
    final DenizenFolder? folder = _denizen;
    return <Widget>[
      sectionHeading(
        '${_source.label} files',
        tip: _source.onServer ? 'Your server converts the files. Nothing installs until you press Install.' : 'Converted in this browser. Nothing is uploaded.',
        docs: switch (_source) {
          ImportSource.shopkeepers => Docs.shopkeepers,
          ImportSource.dtlTraders => Docs.dtlTraders,
          ImportSource.denizen => Docs.denizen,
          ImportSource.server => Docs.liveImports,
          ImportSource.citizens => Docs.citizens,
        },
      ),
      if (_source.onServer) ServerImportFields(flow: _server),
      ...switch (_source) {
        ImportSource.citizens => <Widget>[_citizensFile(), MoreFields(children: <Widget>[_worldsField()])],
        ImportSource.shopkeepers => <Widget>[
            _file('Shopkeepers save', chosen: _shopSave?.$1, button: 'Choose save.yml', onPick: () => _chooseShops(config: false), testId: 'shops-file'),
            _file('Shop config', chosen: _shopConfig?.$1, button: 'Choose config.yml', onPick: () => _chooseShops(config: true), hint: 'Optional. Sets the currency.'),
            _citizensFile(hint: 'Optional. Links shops to NPCs.'),
          ],
        ImportSource.denizen => <Widget>[
            _file(
              'Denizen folder',
              chosen: folder == null
                  ? null
                  : '${_denizenName ?? 'Folder'}: ${countOf(folder.scripts, 'script')}'
                      '${folder.input.serverFlags == null ? '' : ', server flags'}'
                      '${folder.input.playerFlags.isEmpty ? '' : ', ${countOf(folder.input.playerFlags.length, 'player flag file')}'}',
              button: 'Choose folder',
              onPick: _chooseDenizen,
              hint: 'plugins/Denizen',
              testId: 'denizen-folder',
            ),
            _citizensFile(hint: 'Optional. Binds scripts to NPCs.'),
          ],
        ImportSource.dtlTraders => <Widget>[
            _file(
              'dtlTraders files',
              chosen: _traders.isEmpty ? null : _traders.map(((String, Uint8List) file) => file.$1).join(', '),
              button: 'Choose files',
              onPick: _chooseTraders,
              testId: 'traders-files',
            ),
            if (_tradersSkipped.isNotEmpty)
              dom.div(attributes: const <String, String>{'data-testid': 'traders-skipped'}, <Widget>[
                mutedText('Skipped ${_tradersSkipped.join(', ')}: not ${_tradersSkipped.length == 1 ? 'a dtlTraders file' : 'dtlTraders files'}.'),
              ]),
            _citizensFile(hint: 'Optional when the server has it.'),
            MoreFields(children: <Widget>[_worldsField()]),
          ],
        ImportSource.server => <Widget>[
            MoreFields(label: 'Upload instead', children: <Widget>[
              _citizensFile(),
              _file('Shopkeepers save', chosen: _shopSave?.$1, button: 'Choose save.yml', onPick: () => _chooseShops(config: false)),
              _file('Shop config', chosen: _shopConfig?.$1, button: 'Choose config.yml', onPick: () => _chooseShops(config: true)),
              _worldsField(),
            ]),
          ],
      },
      for (final String problem in problems)
        dom.div(attributes: const <String, String>{'data-testid': 'import-problem'}, <Widget>[AppAlert(tone: AlertTone.error, message: problem)]),
      if (_serverEmpty && _source.onServer) const AppAlert(message: 'The server has no import yet. Press Convert to start one.'),
      if (_sourceError != null)
        dom.div(attributes: const <String, String>{'data-testid': 'import-error'}, <Widget>[
          AppAlert(tone: AlertTone.error, title: 'Not converted', message: _sourceError!, live: true),
        ]),
    ];
  }

  List<Widget> _reviewStep() => <Widget>[
        switch (_source) {
          ImportSource.citizens => CitizensImportReview(review: _review!, names: _names),
          ImportSource.shopkeepers => ShopsImportReview(preview: _shops!, linked: _bytes != null),
          ImportSource.denizen => DenizenImportReview(folder: _denizen!, preview: _denizenPreview!),
          _ => ServerImportReview(flow: _server),
        },
      ];

  List<Widget> _resultStep(BuildContext context) {
    if (_source.onServer) return <Widget>[ServerImportReview(flow: _server)];
    final List<(String, Map<String, String>)> converted = <(String, Map<String, String>)>[
      if (_review != null) ('Citizens', _review!.preview.files),
      if (_shops != null) ('Shopkeepers', _shops!.files),
      if (_denizenPreview != null) ('Denizen', _denizenPreview!.files),
    ];
    return <Widget>[
      rowList(attributes: const <String, String>{'data-testid': 'import-result'}, <Widget>[
        for (final (String source, Map<String, String> files) in converted) listItem(Text('$source: ${createdLine(kindCounts(files.keys))}.')),
      ]),
      if (ModeScope.advanced(context)) ...<Widget>[
        sectionHeading('Bundles', level: HeadingLevel.h3, tip: _unzipNext, docs: Docs.citizensOutput),
        ActionRow.footer(secondary: <RowAction>[
          if (_review != null) RowAction('Download Citizens bundle', () => _downloadCitizens(_review!)),
          if (_shops != null) RowAction('Download Shopkeepers bundle', () => _downloadShops(_shops!)),
          if (_denizenPreview != null) RowAction('Download Denizen bundle', () => _downloadDenizen(_denizenPreview!)),
        ]),
      ],
    ];
  }

  Widget _footer() {
    final ImportReview? review = _server.review;
    final String? busy = _server.busy;
    String? why;
    final FormFooter footer = switch (_step) {
      0 => FormFooter(
          cancel: RowAction('Cancel', () => component.onNavigate(const HomeRoute())),
          primary: RowAction('Next', _blocked(_source) == null ? () => _goTo(1) : null),
        ),
      1 => () {
          final String? blocked = _convertBlocked;
          // A refused file has its own alert above.
          why = _uploadProblems.isEmpty ? blocked : null;
          return FormFooter(
            secondary: <RowAction>[
              if (_source.onServer) RowAction('Check server', _server.online && busy == null ? _check : null, loading: busy == 'Checking'),
            ],
            cancel: RowAction('Back', () => _goTo(0)),
            primary: RowAction(
              _converting ? 'Converting' : 'Convert',
              blocked == null && !_converting && busy == null ? _convert : null,
              loading: _converting,
              id: 'animus-import-convert',
              attributes: <String, String>{if (why != null) 'aria-describedby': _whyId},
            ),
          );
        }(),
      2 when _source.onServer => FormFooter(
          cancel: RowAction('Back', () => _goTo(1)),
          primary: review?.state == 'applied'
              ? RowAction('Next', () => _goTo(3))
              : RowAction('Install', busy == null && review?.importId != null ? _install : null, loading: busy == 'Installing', id: 'animus-import-install'),
        ),
      2 => FormFooter(cancel: RowAction('Back', () => _goTo(1)), primary: RowAction('Next', () => _goTo(3))),
      _ when _source.onServer => FormFooter(
          secondary: <RowAction>[RowAction('Roll back', busy == null ? _rollback : null, loading: busy == 'Rolling back', destructive: true, id: 'animus-import-rollback')],
          primary: RowAction('Open server', _openServer),
        ),
      _ => FormFooter(
          secondary: <RowAction>[
            RowAction('Add a source', () => _goTo(0)),
            if (_paired) RowAction('Send to server', _opening ? null : () => _openWorkspace(send: true)),
          ],
          primary: RowAction('Open as workspace', _opening ? null : () => _openWorkspace(send: false), loading: _opening),
        ),
    };
    return stack(spacing: 8, <Widget>[
      if (why != null) dom.div(id: _whyId, <Widget>[mutedText(why!)]),
      footer,
    ]);
  }
}
