import 'dart:convert';
import 'dart:typed_data';

import 'package:animus_editor/logic/citizens/citizens_converter.dart';
import 'package:animus_editor/logic/data_folder.dart';
import 'package:animus_editor/logic/shopkeepers/shopkeepers_converter.dart';

import 'documents.dart';
import 'protocol.dart';

/// The fake server's drop-in import: the same steps and `import-review`
/// answers as core's EditorImports, kept in memory. Citizens and Shopkeepers
/// saves go through the editor's own converters (which match the server's
/// byte for byte); dtlTraders files are only counted. Apply installs the
/// staged NPCs, skins, behaviors and shops with everything that converted on;
/// rollback restores every document the import touched.
class FakeImports {
  FakeImports(this.documents, this.publish);

  final FakeDocuments documents;

  /// Sends a catalog event for changed and deleted documents.
  final void Function(List<StoredDocument> changed, List<StoredDocument> deleted) publish;

  static const Map<String, String> uploadPaths = <String, String>{
    'citizens': 'imports/saves.yml',
    'shopkeepers': 'imports/shopkeepers/save.yml',
    'shopkeepers-config': 'imports/shopkeepers/config.yml',
    'dtltraders': 'imports/dtltraders',
  };
  static final RegExp traderFile = RegExp(r'^(?:(?:guis|config|messages)\.yml|data\.dat|shops/([A-Za-z0-9_.-]{1,64})/\1\.yml)$');

  final Map<String, Uint8List> _uploads = <String, Uint8List>{};
  final Map<String, Uint8List> _traders = <String, Uint8List>{};
  CitizensPreview? _citizens;
  ShopkeepersPreview? _shopkeepers;
  String? _mode;
  String _state = 'none';
  final List<String> _transactions = <String>[];

  /// Every path the import wrote, with its content before (null when new).
  final Map<String, String?> _before = <String, String?>{};

  void reset() {
    _uploads.clear();
    _traders.clear();
    _citizens = null;
    _shopkeepers = null;
    _mode = null;
    _state = 'none';
    _transactions.clear();
    _before.clear();
  }

  static String target(String file, String? name) {
    final String? path = uploadPaths[file];
    if (path == null) throw invalid('file= is citizens, shopkeepers, shopkeepers-config or dtltraders.');
    if (file != 'dtltraders') {
      if (name != null) throw invalid('Only dtltraders uploads take a name.');
      return path;
    }
    if (name == null) throw invalid('A dtltraders upload needs name=, such as guis.yml or shops/baker/baker.yml.');
    if (!traderFile.hasMatch(name)) throw invalid('name= is guis.yml, config.yml, messages.yml, data.dat or shops/<shop>/<shop>.yml.');
    return '$path/$name';
  }

  Map<String, Object?> upload(String file, String? name, Uint8List bytes) {
    final String path = target(file, name);
    if (_state == 'applied') throw ApiError('conflict', 'An import is applied. Roll it back before uploading another save.');
    if (file == 'dtltraders') {
      _traders[name!] = bytes;
    } else {
      _uploads[file] = bytes;
    }
    return review(<String>['Uploaded ${bytes.length} bytes to $path. Stage the import to convert it; nothing is installed.']);
  }

  Map<String, Object?> stage(String mode, String? label, Map<String, String> worlds) {
    if (_state == 'applied') throw invalid('A Citizens import is already applied. Roll it back with /animus import citizens rollback before staging another.');
    final Uint8List? saves = _uploads['citizens'];
    final Uint8List? shops = _uploads['shopkeepers'];
    if (saves == null && shops == null) {
      throw invalid('No Citizens save found and no Shopkeepers save found. Put saves.yml in imports or a Shopkeepers save.yml in imports/shopkeepers.');
    }
    final List<String> lines = <String>[];
    try {
      final CitizensPreview? citizens = saves == null ? null : convertCitizens(decodeSaves(saves), worlds);
      if (citizens != null && citizens.valid != citizens.records) {
        throw invalid('${citizens.records - citizens.valid} of ${citizens.records} records do not convert; see imports/${citizens.id}/report.json');
      }
      final Uint8List? config = _uploads['shopkeepers-config'];
      final ShopkeepersPreview? shopkeepers = shops == null
          ? null
          : convertShopkeepers(
              decodeShopkeepersSource(shops, 'save.yml'),
              config: config == null ? null : decodeShopkeepersSource(config, 'config.yml'),
              citizens: saves == null ? null : decodeShopkeepersSource(saves, 'saves.yml'),
            );
      _citizens = citizens;
      _shopkeepers = shopkeepers;
      if (citizens != null) {
        lines.add('Citizens save imports/saves.yml: ${citizens.records} NPCs, ${citizens.valid} valid, ${citizens.skins} unique skins');
        lines.add('Application $mode: ${citizens.records} definitions, 0 replacements');
      }
      if (_traders.isNotEmpty) lines.add('dtlTraders imports/dtltraders: ${_traders.length} files');
      if (shopkeepers != null) {
        lines.add('Shopkeepers imports/shopkeepers/save.yml: ${shopkeepers.shops} shops, ${shopkeepers.tradeOffers} trading offers; ${shopkeepers.npcs} new mob NPCs; ${shopkeepers.issueCount} issues');
      }
    } on CitizensImportException catch (failure) {
      throw invalid(failure.message);
    } on ShopkeepersImportException catch (failure) {
      throw invalid(failure.message);
    }
    _mode = mode;
    _state = 'staged';
    lines.add('Nothing installed. Next: /animus import citizens apply');
    return review(lines);
  }

  void _expect(String importId, {required bool applied}) {
    if (applied && _state != 'applied') throw ApiError('conflict', 'No import is applied.');
    if (!applied && _state == 'none') throw ApiError('conflict', 'Nothing is staged. Upload a save and stage it first.');
    if (_citizens?.id == importId || _shopkeepers?.id == importId) return;
    throw ApiError('conflict', 'Import $importId is not the staged import; reload the review.');
  }

  Map<String, Object?> apply(String importId) {
    _expect(importId, applied: false);
    if (_state == 'applied') throw ApiError('conflict', 'Already applied. Use /animus import citizens review or rollback.');
    final Map<String, String> staged = <String, String>{
      for (final MapEntry<String, String> file in (_citizens?.files ?? const <String, String>{}).entries) file.key: file.value,
      for (final MapEntry<String, String> file in (_shopkeepers?.files ?? const <String, String>{}).entries) file.key: file.value,
    };
    final List<StoredDocument> changed = <StoredDocument>[];
    for (final MapEntry<String, String> file in staged.entries) {
      final DocumentLocation? location = locateDocument(file.key);
      if (location == null) continue;
      _before.putIfAbsent(file.key, () => documents.at(file.key)?.content);
      changed.add(documents.write(Located(location.kind, location.id, file.key), file.value));
    }
    _transactions.add(randomUuid());
    _state = 'applied';
    publish(changed, const <StoredDocument>[]);
    return review(<String>[
      'Installed import ${importId.substring(0, 12)}; NPCs spawn with their patrols, schedules, combat, riding, shops and builds on; tx ${_transactions.last}',
      if (_commandNamespaces().isNotEmpty) 'Commands: added ${_commandNamespaces().join(', ')} to config.toml [behaviors.actions] commandNamespaces',
      'Next: /animus import citizens review lists what converted; /animus import citizens rollback undoes the import',
    ]);
  }

  Map<String, Object?> rollback(String importId) {
    _expect(importId, applied: true);
    final List<StoredDocument> changed = <StoredDocument>[], deleted = <StoredDocument>[];
    final List<String> lines = <String>[
      for (final String transaction in _transactions.reversed) 'Rolled back $transaction',
    ];
    for (final MapEntry<String, String?> entry in _before.entries) {
      final StoredDocument? current = documents.at(entry.key);
      if (entry.value == null) {
        if (current != null) {
          documents.delete(entry.key);
          deleted.add(current);
        }
      } else if (current != null) {
        changed.add(documents.write(Located(current.kind, current.id, entry.key), entry.value!));
      }
    }
    _before.clear();
    _transactions.clear();
    _state = 'rolled-back';
    publish(changed, deleted);
    lines.add('The Citizens import is undone. The staged preview stays; /animus import citizens apply installs it again.');
    return review(lines);
  }

  Map<String, Object?> status() => review(_state == 'applied' ? <String>['Citizens import: ${_imported().length} NPCs installed'] : const <String>[]);

  List<StoredDocument> _imported() => <StoredDocument>[
        for (final String path in _before.keys)
          if (path.startsWith('npcs/') && documents.at(path) != null) documents.at(path)!,
      ];

  /// The namespaces whose staged behaviors run commands, which apply adds to
  /// the command allow-list on the real server.
  List<String> _commandNamespaces() => <String>[
        if ((_citizens?.files ?? const <String, String>{}).entries.any((MapEntry<String, String> file) => file.key.startsWith('behaviors/citizens/') && file.value.contains('"animus:command"')))
          'citizens',
      ];

  Map<String, Object?> review(List<String> lines) {
    final CitizensPreview? citizens = _citizens;
    final ShopkeepersPreview? shopkeepers = _shopkeepers;
    final int imported = _imported().length;
    Map<String, Object?> summary(String? text, Set<String> keys) {
      final Map<String, Object?> full = text == null ? const <String, Object?>{} : jsonDecode(text) as Map<String, Object?>;
      return <String, Object?>{
        for (final MapEntry<String, Object?> entry in full.entries)
          if (keys.contains(entry.key)) entry.key: entry.value is List<Object?> && (entry.value! as List<Object?>).length > 100 ? (entry.value! as List<Object?>).sublist(0, 100) : entry.value,
      };
    }

    const Set<String> citizensKeys = <String>{'importId', 'converterVersion', 'notice', 'records', 'valid', 'skins', 'behaviors', 'conversions', 'traits', 'types', 'issues', 'migrationWarnings', 'unresolvedReferences', 'worldMap'};
    const Set<String> shopKeys = <String>{'importId', 'importerVersion', 'notice', 'shops', 'npcs', 'types', 'offers', 'links', 'currency', 'issues'};
    return <String, Object?>{
      'state': _state,
      if (citizens != null && _state != 'none')
        'citizens': <String, Object?>{'importId': citizens.id, 'source': 'imports/saves.yml', 'mode': _mode ?? 'reject', 'report': 'imports/${citizens.id}/report.json'},
      if (shopkeepers != null && _state != 'none')
        'shopkeepers': <String, Object?>{
          'importId': shopkeepers.id,
          'source': 'imports/shopkeepers/save.yml',
          'state': _state == 'applied' ? 'applied' : (_state == 'rolled-back' ? 'rolled-back' : 'staged'),
          'report': 'imports/shopkeepers/${shopkeepers.id}/report.json',
          'waiting': 0,
        },
      'commandNamespaces': _state == 'none' ? const <String>[] : _commandNamespaces(),
      if (_state == 'applied') ...<String, Object?>{
        'preserved': const <String, Object?>{},
        'npcs': imported,
      },
      'transactions': List<String>.of(_transactions),
      'lines': lines.take(200).toList(),
      'uploads': <String, Object?>{
        for (final String file in _uploads.keys) file: uploadPaths[file],
        if (_traders.isNotEmpty) 'dtltraders': uploadPaths['dtltraders'],
      },
      'reports': <String, Object?>{
        if (citizens != null && _state != 'none') 'citizens': summary(citizens.files['report.json'], citizensKeys),
        if (shopkeepers != null && _state != 'none') 'shopkeepers': summary(shopkeepers.files['report.json'], shopKeys),
        if (_traders.isNotEmpty && _state != 'none') 'dtltraders': <String, Object?>{'files': _traders.length},
      },
    };
  }
}
