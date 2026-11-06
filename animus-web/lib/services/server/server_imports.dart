/// The editor side of `/v1/imports`: upload a Citizens saves.yml, a
/// Shopkeepers save.yml and config.yml and dtlTraders files to a paired
/// server, stage them, and apply, accept and roll back the server's drop-in
/// import (wiki animus/13-migrating-from-citizens). Every answer is the server's
/// `import-review`.
library;

import 'dart:typed_data';

import '../../logic/shared_schemas.dart';
import 'error_copy.dart';
import 'transport.dart';

/// What a file is to the server (`file=` of `POST /v1/imports`).
enum ImportFile {
  citizens('citizens', 'Citizens saves.yml'),
  shopkeepers('shopkeepers', 'Shopkeepers save.yml'),
  shopkeepersConfig('shopkeepers-config', 'Shopkeepers config.yml'),
  dtltraders('dtltraders', 'dtlTraders file');

  const ImportFile(this.wireName, this.label);

  final String wireName;
  final String label;
}

/// One file to upload. [name] is the path inside the dtlTraders folder
/// (`guis.yml`, `shops/baker/baker.yml`) and only set for dtlTraders files.
class ImportUpload {
  const ImportUpload(this.file, this.bytes, {this.name});

  final ImportFile file;
  final Uint8List bytes;
  final String? name;

  String get label => name == null ? file.label : '${file.label} $name';
}

/// Where a picked dtlTraders file goes inside the dtlTraders folder:
/// `guis.yml`, `config.yml`, `messages.yml` and `data.dat` stay at the top,
/// any other `<shop>.yml` is that shop's file `shops/<shop>/<shop>.yml`.
/// Null for files dtlTraders does not keep.
String? dtlTradersName(String fileName) {
  if (const <String>{'guis.yml', 'config.yml', 'messages.yml', 'data.dat'}.contains(fileName)) return fileName;
  final RegExpMatch? shop = RegExp(r'^([A-Za-z0-9_.-]{1,64})\.yml$').firstMatch(fileName);
  return shop == null ? null : 'shops/${shop.group(1)}/$fileName';
}

/// The review categories `accept` turns on, in the server's order.
const List<String> importCategories = <String>['spawn', 'patrols', 'combat', 'riding', 'shops', 'builder', 'denizen'];

/// The server's `import-review`.
class ImportReview {
  const ImportReview({
    required this.state,
    required this.lines,
    this.citizens,
    this.shopkeepers,
    this.dtltraders,
    this.pending = const <String, int>{},
    this.preserved = const <String, int>{},
    this.npcs,
    this.transactions = const <String>[],
    this.uploads = const <String, String>{},
    this.reports = const <String, Map<String, Object?>>{},
  });

  /// none, staged, applied or rolled-back.
  final String state;
  final List<String> lines;
  final Map<String, Object?>? citizens;
  final Map<String, Object?>? shopkeepers;
  final Map<String, Object?>? dtltraders;
  final Map<String, int> pending;
  final Map<String, int> preserved;
  final int? npcs;
  final List<String> transactions;

  /// Uploaded files by `file=` name, with where the server keeps them.
  final Map<String, String> uploads;
  final Map<String, Map<String, Object?>> reports;

  /// The id apply, accept and rollback name: the Citizens import, or the
  /// Shopkeepers import when there is no Citizens save.
  String? get importId => (citizens?['importId'] ?? shopkeepers?['importId']) as String?;

  int get waiting => pending.values.fold(0, (int sum, int count) => sum + count);

  static Map<String, int> _counts(Object? value) => <String, int>{
        if (value is Map<String, Object?>)
          for (final MapEntry<String, Object?> entry in value.entries)
            if (entry.value is num) entry.key: (entry.value! as num).toInt(),
      };

  static Map<String, Object?>? _object(Object? value) => value is Map<String, Object?> ? value : null;

  static ImportReview fromJson(Map<String, Object?> json) => ImportReview(
        state: json['state'] is String ? json['state']! as String : 'none',
        lines: <String>[for (final Object? line in (json['lines'] as List<Object?>?) ?? const <Object?>[]) '$line'],
        citizens: _object(json['citizens']),
        shopkeepers: _object(json['shopkeepers']),
        dtltraders: _object(json['dtltraders']),
        pending: _counts(json['pending']),
        preserved: _counts(json['preserved']),
        npcs: json['npcs'] is num ? (json['npcs']! as num).toInt() : null,
        transactions: <String>[for (final Object? id in (json['transactions'] as List<Object?>?) ?? const <Object?>[]) '$id'],
        uploads: <String, String>{
          if (json['uploads'] is Map<String, Object?>)
            for (final MapEntry<String, Object?> entry in (json['uploads']! as Map<String, Object?>).entries) entry.key: '${entry.value}',
        },
        reports: <String, Map<String, Object?>>{
          if (json['reports'] is Map<String, Object?>)
            for (final MapEntry<String, Object?> entry in (json['reports']! as Map<String, Object?>).entries)
              if (entry.value is Map<String, Object?>) entry.key: entry.value! as Map<String, Object?>,
        },
      );
}

/// The server refused a step; [message] is its explanation.
class ImportRefused implements Exception {
  const ImportRefused(this.code, this.message, {this.detail});

  final String code;

  /// What happened and what to do next.
  final String message;

  /// The server's own words, when it gave any.
  final String? detail;

  @override
  String toString() => message;
}

int _limit(String name) => ((SharedSchemas.embedded.endpoints['limits']! as Map<String, Object?>)[name]! as num).toInt();

/// The largest request body: `importBodyBytes`.
final int importBodyBytes = _limit('importBodyBytes');

/// The largest file the relay carries: a relay frame holds the upload as
/// base64 (4 bytes per 3) inside a JSON request frame, which needs some room.
final int relayUploadBytes = (_limit('relayFrameBytes') - 16384) ~/ 4 * 3;

/// Why [upload] cannot go over [path], or null when it can.
String? uploadProblem(ImportUpload upload, ConnectionPath? path) {
  final int size = upload.bytes.length;
  if (size == 0) return '${upload.label} is empty. Choose it again.';
  if (size > importBodyBytes) {
    return '${upload.label} is over the ${formatSize(importBodyBytes)} upload limit. '
        'Copy it to the server\'s imports folder and run /animus import citizens.';
  }
  if (path == ConnectionPath.relay && size > relayUploadBytes) {
    return '${upload.label} is over the ${formatSize(relayUploadBytes)} relay limit. Connect over the direct path.';
  }
  return null;
}

/// A file size for people: bytes, KiB or MiB.
String formatSize(int bytes) => bytes >= 1048576
    ? '${(bytes / 1048576).toStringAsFixed(1)} MiB'
    : bytes >= 1024
        ? '${(bytes / 1024).toStringAsFixed(0)} KiB'
        : '$bytes bytes';

/// `/v1/imports` over one server connection.
class ServerImports {
  const ServerImports({required this.send, required this.path});

  /// An authorized request on the open path (ServerConnection.request).
  final Future<ApiResponse> Function(ApiRequest request) send;

  /// The path in use, which decides how large an upload may be.
  final ConnectionPath? Function() path;

  Future<ImportReview> _review(ApiRequest request) async {
    final ApiResponse response = await send(request);
    final Object? body = response.body;
    if (!response.ok || body is! Map<String, Object?>) throw ImportRefused(response.errorCode ?? codeForStatus(response.status), response.problem.text, detail: response.serverMessage);
    return ImportReview.fromJson(body);
  }

  Future<ImportReview> status() => _review(const ApiRequest('GET', '/v1/imports'));

  Future<ImportReview> upload(ImportUpload upload) async {
    final String? problem = uploadProblem(upload, path());
    if (problem != null) throw ImportRefused('too-large', problem);
    final String name = upload.name == null ? '' : '&name=${Uri.encodeQueryComponent(upload.name!)}';
    return await _review(ApiRequest('POST', '/v1/imports?file=${upload.file.wireName}$name', bytes: upload.bytes));
  }

  Future<ImportReview> stage({String mode = 'reject', String? label, Map<String, String> worlds = const <String, String>{}}) =>
      _review(ApiRequest('POST', '/v1/imports/stage', body: <String, Object?>{
        'mode': mode,
        'label': ?label,
        if (worlds.isNotEmpty) 'worlds': worlds,
      }));

  Future<ImportReview> apply(String importId) => _review(ApiRequest('POST', '/v1/imports/${Uri.encodeComponent(importId)}/apply'));

  Future<ImportReview> accept(String importId, List<String> categories) =>
      _review(ApiRequest('POST', '/v1/imports/${Uri.encodeComponent(importId)}/accept', body: <String, Object?>{'categories': categories}));

  Future<ImportReview> rollback(String importId) => _review(ApiRequest('POST', '/v1/imports/${Uri.encodeComponent(importId)}/rollback'));
}
