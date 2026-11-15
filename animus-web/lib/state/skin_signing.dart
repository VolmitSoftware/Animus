/// Signing a skin PNG through the paired server, which asks MineSkin for the
/// signature clients need before they draw a skin. `POST /v1/skins/sign`
/// answers at once with a job; the job is polled about once a second until it
/// is signed (with the skin document to apply, which the server does not save)
/// or failed (with one line saying why).
library;

import 'dart:convert';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';

import '../logic/gui/gui_json.dart';
import '../services/server/connection_status.dart' show NotConnected;
import '../services/server/transport.dart';
import 'live_session.dart';

/// A skin document the server signed: its SHA-256 id and its source text.
class SignedSkin {
  const SignedSkin(this.id, this.source);

  final String id;
  final String source;
}

/// Why signing did not work, as one line for the person who asked.
class SkinSignFailure implements Exception {
  const SkinSignFailure(this.message);

  final String message;

  @override
  String toString() => message;
}

/// What signing a skin needs from the editor: the PNG or its address, the arm
/// model and an optional label; [progress] hears each state the server reports.
typedef SkinSigner = Future<SignedSkin> Function({Uint8List? png, String? url, required bool slim, String? label, void Function(String state)? progress});

/// How long signing waits at most: MineSkin queues jobs and can take a minute when busy.
const int signPolls = 120;

/// Signs through [send]: one POST, then one GET per [pause] until the job ends.
Future<SignedSkin> signSkin(
  Future<ApiResponse> Function(ApiRequest request) send, {
  Uint8List? png,
  String? url,
  required bool slim,
  String? label,
  void Function(String state)? progress,
  Future<void> Function() pause = _second,
}) async {
  final String? named = label == null || label.trim().isEmpty ? null : label.trim();
  final ApiResponse queued = await send(ApiRequest('POST', '/v1/skins/sign', body: <String, Object?>{
    if (png != null) 'image': base64Encode(png) else 'url': url,
    'slim': slim,
    'label': ?named,
  }));
  if (!queued.ok) throw SkinSignFailure(_problem(queued));
  final Object? job = queued.body is Map<String, Object?> ? (queued.body! as Map<String, Object?>)['job'] : null;
  if (job is! String) throw const SkinSignFailure('The server did not start signing the skin.');
  progress?.call('signing');
  for (int poll = 0; poll < signPolls; poll++) {
    await pause();
    final ApiResponse answer = await send(ApiRequest('GET', '/v1/skins/sign/$job'));
    if (!answer.ok) throw SkinSignFailure(_problem(answer));
    final Map<String, Object?> body = asMap(answer.body);
    switch (body['state']) {
      case 'signed':
        final Map<String, Object?> skin = asMap(body['skin']);
        final String source = prettyJson(skin['source']);
        final String id = sha256.convert(utf8.encode(source)).toString();
        if (id != skin['id']) throw const SkinSignFailure('The signed skin from the server does not match its id.');
        return SignedSkin(id, source);
      case 'failed':
        throw SkinSignFailure('${body['message'] ?? 'MineSkin could not sign the skin.'}');
      default:
        progress?.call('${body['state'] ?? 'signing'}');
    }
  }
  throw const SkinSignFailure('Signing took over two minutes. Try again later.');
}

Future<void> _second() => Future<void>.delayed(const Duration(seconds: 1));

/// Signing through [session]'s server; null without a paired server, where a
/// new skin keeps its PNG and is signed once it is applied on one.
SkinSigner? serverSigner(LiveSession? session) => session == null
    ? null
    : ({Uint8List? png, String? url, required bool slim, String? label, void Function(String state)? progress}) =>
        signSkin(session.connection.request, png: png, url: url, slim: slim, label: label, progress: progress);

/// One line for a signing that did not work: the server's or MineSkin's own
/// reason, or why the server could not be asked.
String signFailureText(Object failure) => switch (failure) {
      SkinSignFailure(:final String message) => message,
      NotConnected() => 'Not connected to the server. Connect, then try again.',
      TransportFailure(:final String message) => message,
      _ => 'The skin was not signed. Try again.',
    };

/// The server's own line for a refused request, else the shared copy for its code.
String _problem(ApiResponse response) {
  final Object? error = asMap(response.body)['error'];
  final Object? message = asMap(error)['message'];
  return message is String && message.isNotEmpty ? message : response.problem.text;
}
