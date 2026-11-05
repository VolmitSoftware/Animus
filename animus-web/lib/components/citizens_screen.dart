/// Convert Citizens save: a saves.yml read from disk is converted in this browser to the same staged import the
/// server's converter writes, reviewed here, and downloaded as a zip that unzips into the data folder. Nothing is uploaded.
library;

import 'dart:convert';
import 'dart:js_interop';
import 'dart:typed_data';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../logic/citizens/citizens_converter.dart';
import '../logic/citizens/citizens_review.dart';
import '../logic/shopkeepers/shopkeepers_converter.dart';
import '../services/browser.dart';

@JS('animusCitizensResult')
external set _citizensResult(JSString? value);

@JS('animusShopkeepersResult')
external set _shopkeepersResult(JSString? value);

class CitizensScreen extends StatefulWidget {
  const CitizensScreen({super.key});

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

  static const String _yaml = '.yml,.yaml,application/x-yaml,text/yaml';

  Future<void> _choose() async {
    final (String, Uint8List)? picked = await pickFile(_yaml);
    if (picked == null) return;
    setState(() {
      _fileName = picked.$1;
      _bytes = picked.$2;
      _review = null;
      _error = null;
      // Links were checked against the previous Citizens save.
      _shops = null;
    });
    _citizensResult = null;
    _shopkeepersResult = null;
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
      setState(() => _shopError = failure.message);
    } on Object catch (failure) {
      _shopkeepersResult = jsonEncode(<String, Object?>{'error': '$failure'}).toJS;
      setState(() => _shopError = 'The conversion failed: $failure');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _downloadShops(ShopkeepersPreview preview) {
    downloadBytes('animus-shopkeepers-import-${preview.id.substring(0, 12)}.zip', shopkeepersBundleZip(preview));
    ArcaneSonner.success('Unzip it into the Animus data folder, then run /animus import citizens on the server to review and apply it.');
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
      setState(() => _error = failure.message);
    } on Object catch (failure) {
      _citizensResult = jsonEncode(<String, Object?>{'error': '$failure'}).toJS;
      setState(() => _error = 'The conversion failed: $failure');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _download(CitizensReview review) {
    downloadBytes('animus-citizens-import-${review.preview.id.substring(0, 12)}.zip', citizensBundleZip(review.preview));
    ArcaneSonner.success('Unzip it into the Animus data folder, then run /animus import citizens on the server to review and apply it.');
  }

  @override
  Widget build(BuildContext context) {
    final CitizensReview? review = _review;
    return dom.main_(classes: 'animus-home animus-citizens', <Widget>[
      dom.section(classes: 'animus-panel animus-home-intro', <Widget>[
        const dom.h1(<Widget>[Component.text('Convert a Citizens save')]),
        const dom.p(<Widget>[
          Component.text('Pick a Citizens saves.yml. It is converted in this browser with the same rules as the server converter, '
              'and nothing is uploaded. Imported NPCs stay dormant and combat stays disabled until reviewed on the server.'),
        ]),
        dom.div(classes: 'animus-row', <Widget>[
          Button.secondary(label: 'Choose saves.yml', icon: ArcaneIcon.fileUp(), disabled: _busy, onPressed: _choose),
          dom.span(classes: 'animus-muted animus-citizens-file', <Widget>[Component.text(_fileName ?? 'No file chosen')]),
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
          if (review != null)
            Button.secondary(label: 'Download data-folder bundle', icon: ArcaneIcon.download(), onPressed: () => _download(review)),
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
        dom.span(classes: 'animus-muted animus-citizens-file', <Widget>[Component.text(_shopSave?.$1 ?? 'No file chosen')]),
      ]),
      dom.div(classes: 'animus-row', <Widget>[
        Button.secondary(label: 'Choose config.yml', icon: ArcaneIcon.settings(), disabled: _busy, onPressed: () => _chooseShops(true)),
        dom.span(classes: 'animus-muted animus-citizens-file', <Widget>[Component.text(_shopConfig?.$1 ?? 'Default currency (emerald)')]),
      ]),
      dom.div(classes: 'animus-row', <Widget>[
        Button.primary(label: _busy ? 'Converting' : 'Convert shops', icon: ArcaneIcon.store(), disabled: _busy || _shopSave == null, onPressed: _convertShops),
        if (shops != null)
          Button.secondary(label: 'Download shops bundle', icon: ArcaneIcon.download(), onPressed: () => _downloadShops(shops)),
      ]),
      if (_shopError != null)
        dom.div(classes: 'animus-citizens-error', attributes: const <String, String>{'role': 'alert'}, <Widget>[
          const dom.h3(<Widget>[Component.text('Shops not converted')]),
          dom.p(<Widget>[Component.text(_shopError!)]),
        ]),
      if (shops != null) ...<Widget>[
        dom.div(classes: 'animus-stats', <Widget>[
          _stat('${shops.shops}', 'shops'),
          _stat('${shops.tradeOffers}', 'trading offers'),
          _stat('${shops.staticOffers}', 'shown only'),
          _stat('${shops.skippedOffers}', 'kept in source.yml'),
          _stat('${shops.npcs}', 'new mob NPCs'),
          _stat('${shops.issueCount}', 'issues'),
          _stat('${shops.linksFound}', 'Citizens links found'),
          _stat('${shops.linksMissing}', 'Citizens links not found'),
          _stat('${shops.linksUnchecked}', 'Citizens links unchecked'),
        ]),
        dom.p(classes: 'animus-muted animus-citizens-id', <Widget>[
          const Component.text('Import '),
          dom.code(<Widget>[Component.text(shops.id)]),
        ]),
      ],
    ]);
  }

  List<Widget> _report(CitizensReview review) => <Widget>[
        dom.section(classes: 'animus-panel', attributes: const <String, String>{'aria-label': 'Conversion summary'}, <Widget>[
          const dom.h2(<Widget>[Component.text('Review')]),
          dom.div(classes: 'animus-stats', <Widget>[
            _stat('${review.preview.records}', 'records'),
            _stat('${review.preview.valid}', 'valid'),
            _stat('${review.preview.skins}', 'unique skins'),
            _stat('${review.issues.length}', 'issues'),
            _stat('${review.warnings.length}', 'migration warnings'),
            _stat('${review.resolvedReferences}', 'references resolved'),
            _stat('${review.unresolvedReferences}', 'references unresolved'),
            _stat('${review.remappedLocations}', 'locations remapped'),
          ]),
          dom.p(classes: 'animus-muted animus-citizens-id', <Widget>[
            const Component.text('Import '),
            dom.code(<Widget>[Component.text(review.preview.id)]),
          ]),
        ]),
        dom.section(classes: 'animus-panel', <Widget>[
          const dom.h2(<Widget>[Component.text('Fields by conversion status')]),
          _counts(review.classification, 'Status', 'Fields'),
        ]),
        dom.section(classes: 'animus-panel', <Widget>[
          dom.h2(<Widget>[Component.text('Traits (${review.traits.length})')]),
          _counts(review.traits, 'Trait', 'Records'),
        ]),
        dom.section(classes: 'animus-panel', <Widget>[
          dom.h2(<Widget>[Component.text('Body types (${review.types.length})')]),
          _counts(review.types, 'Type', 'Records'),
        ]),
        _list('Issues', review.issues, 'Every record is a valid definition.'),
        _list('Migration warnings', review.warnings, 'No migration warnings.'),
      ];

  Widget _stat(String value, String label) => dom.div(classes: 'animus-stat', <Widget>[
        dom.strong(<Widget>[Component.text(value)]),
        dom.span(<Widget>[Component.text(label)]),
      ]);

  Widget _counts(Map<String, int> counts, String key, String value) {
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
              dom.td(<Widget>[dom.code(<Widget>[Component.text(name)])]),
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
