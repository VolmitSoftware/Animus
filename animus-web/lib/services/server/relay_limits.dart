/// What the protocol's `limits` allow (editor-v1.endpoints.json), and the
/// relay frame above all: the relay closes the socket on a larger frame and
/// nothing is written, so a request that would not fit is refused before it
/// is sent.
library;

import 'dart:convert';

import '../../logic/shared_schemas.dart';

/// A number from the endpoints' `limits`.
int protocolLimit(String name) => ((SharedSchemas.embedded.endpoints['limits']! as Map<String, Object?>)[name]! as num).toInt();

/// The largest relay frame, in UTF-8 bytes.
final int relayFrameBytes = protocolLimit('relayFrameBytes');

/// Room a request frame needs around its body: its type, id, method, path
/// and headers.
const int relayEnvelopeBytes = 16384;

/// Whether [body], the JSON body of a request over the relay, fits in one
/// frame.
bool fitsRelayFrame(Object? body) => utf8.encode(jsonEncode(body)).length <= relayFrameBytes - relayEnvelopeBytes;

/// What the editor says when a request is too large for the relay; [verb]
/// is what sending fewer documents at once would still let the user do.
String tooLargeForRelay(String verb) => 'Too large for the relay. Connect directly, or $verb fewer documents.';
