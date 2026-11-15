import 'dart:convert';
import 'dart:typed_data';

import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/npc/skin_edits.dart';
import 'package:animus_editor/logic/offline/new_documents.dart';
import 'package:animus_editor/model/animus_document.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:animus_editor/services/server/connection_status.dart';
import 'package:animus_editor/services/server/transport.dart';
import 'package:animus_editor/state/editor_store.dart';
import 'package:animus_editor/state/skin_signing.dart';
import 'package:crypto/crypto.dart';
import 'package:test/test.dart';

AnimusDocument _doc(DocumentKind kind, String id, Object? json, {String? source}) =>
    AnimusDocument(kind: kind, id: id, path: newDocumentPath(kind, id), source: source ?? prettyJson(json), json: json, diagnostics: const <Diagnostic>[]);

Map<String, Object?> _source(String label) => <String, Object?>{'textureRaw': 'e30=', 'signature': 'c2ln', 'label': label};

String _id(Map<String, Object?> source) => sha256.convert(utf8.encode(prettyJson(source))).toString();

void main() {
  final Uint8List png = Uint8List.fromList(List<int>.generate(64, (int i) => i));

  group('signSkin', () {
    test('posts the PNG, polls the job and returns the signed document as written', () async {
      final List<ApiRequest> sent = <ApiRequest>[];
      final List<String> states = <String>[];
      final List<ApiResponse> answers = <ApiResponse>[
        const ApiResponse(202, <String, Object?>{'job': '0123456789abcdef0123456789abcdef', 'state': 'signing'}),
        const ApiResponse(200, <String, Object?>{'job': '0123456789abcdef0123456789abcdef', 'state': 'signing'}),
        ApiResponse(200, <String, Object?>{
          'job': '0123456789abcdef0123456789abcdef',
          'state': 'signed',
          'skin': <String, Object?>{'id': _id(_source('Guard')), 'source': _source('Guard')},
        }),
      ];
      final SignedSkin signed = await signSkin((ApiRequest request) async {
        sent.add(request);
        return answers.removeAt(0);
      }, png: png, slim: true, label: ' Guard ', progress: states.add, pause: () async {});
      expect(signed.id, _id(_source('Guard')));
      expect(signed.source, prettyJson(_source('Guard')));
      expect(<String>[for (final ApiRequest request in sent) '${request.method} ${request.path}'],
          <String>['POST /v1/skins/sign', 'GET /v1/skins/sign/0123456789abcdef0123456789abcdef', 'GET /v1/skins/sign/0123456789abcdef0123456789abcdef']);
      expect(sent.first.body, <String, Object?>{'image': base64Encode(png), 'slim': true, 'label': 'Guard'});
      expect(states, contains('signing'));
    });

    test('a failed job says why in MineSkin\'s words', () async {
      final List<ApiResponse> answers = <ApiResponse>[
        ApiResponse(202, <String, Object?>{'job': 'a' * 32, 'state': 'signing'}),
        ApiResponse(200, <String, Object?>{'job': 'a' * 32, 'state': 'failed', 'message': 'MineSkin is rate limiting this server; try again in a minute'}),
      ];
      await expectLater(
        signSkin((ApiRequest request) async => answers.removeAt(0), url: 'https://example.com/a.png', slim: false, pause: () async {}),
        throwsA(isA<SkinSignFailure>().having((SkinSignFailure f) => f.message, 'message', 'MineSkin is rate limiting this server; try again in a minute')),
      );
    });

    test('a refused request shows the server\'s own line', () async {
      await expectLater(
        signSkin(
          (ApiRequest request) async => const ApiResponse(400, <String, Object?>{
            'error': <String, Object?>{'code': 'invalid-request', 'message': r'$.image: The PNG is 128x128; a skin is 64x64 (or the old 64x32)'},
          }),
          png: png,
          slim: false,
          pause: () async {},
        ),
        throwsA(isA<SkinSignFailure>().having((SkinSignFailure f) => f.message, 'message', contains('128x128'))),
      );
    });

    test('a document that does not hash to its id is refused', () async {
      final List<ApiResponse> answers = <ApiResponse>[
        ApiResponse(202, <String, Object?>{'job': 'b' * 32, 'state': 'signing'}),
        ApiResponse(200, <String, Object?>{'job': 'b' * 32, 'state': 'signed', 'skin': <String, Object?>{'id': 'c' * 64, 'source': _source('Guard')}}),
      ];
      await expectLater(signSkin((ApiRequest request) async => answers.removeAt(0), png: png, slim: false, pause: () async {}), throwsA(isA<SkinSignFailure>()));
    });

    test('failure text covers a dropped connection', () {
      expect(signFailureText(const SkinSignFailure('nope')), 'nope');
      expect(signFailureText(const NotConnected(ConnectionStatus(ConnectionPhase.offline))), 'Not connected to the server. Connect, then try again.');
    });
  });

  group('signed skin edits', () {
    test('a signed upload is created, worn and keeps its PNG for the preview', () {
      final SignedSkin signed = SignedSkin(_id(_source('Guard')), prettyJson(_source('Guard')));
      final AnimusDocument npc = _doc(DocumentKind.npc, 'town/guard', <String, Object?>{'id': 'guard', 'type': 'minecraft:player'});
      final SkinChange change = skinFromSigned(documents: <AnimusDocument>[npc], signed: signed, png: png, target: const SkinTarget(DocumentKind.npc, 'town/guard'));
      expect(change.skin, signed.id);
      expect(change.documents.first.source, signed.source, reason: 'the server\'s text, so its hash is the id');
      expect(asMap(change.documents[1].update!(npc.json))['skin'], signed.id);
      expect(change.resources[skinImagePath(signed.id)], png);
    });

    test('an unsigned skin signed in place moves every wearer and drops the old one', () {
      final ({String id, String source}) unsigned = newSkin(slim: false, image: png);
      final AnimusDocument skin = _doc(DocumentKind.skin, unsigned.id, jsonDecode(unsigned.source), source: unsigned.source);
      final AnimusDocument a = _doc(DocumentKind.npc, 'town/a', <String, Object?>{'id': 'a', 'type': 'minecraft:player', 'skin': unsigned.id});
      final AnimusDocument b = _doc(DocumentKind.npc, 'town/b', <String, Object?>{'id': 'b', 'type': 'minecraft:player', 'skin': unsigned.id});
      final SignedSkin signed = SignedSkin(_id(_source('Guard')), prettyJson(_source('Guard')));
      final SkinChange change = signedInPlace(documents: <AnimusDocument>[skin, a, b], resources: <String, Uint8List>{skinImagePath(unsigned.id): png}, hash: unsigned.id, signed: signed)!;
      expect(change.documents.where((DocumentChange c) => c.update != null).length, 2);
      expect(change.documents.last.source, isNull, reason: 'the unsigned document goes');
      expect(change.resources[skinImagePath(signed.id)], png);
      expect(change.resources.containsKey(skinImagePath(unsigned.id)), isTrue);
      expect(change.resources[skinImagePath(unsigned.id)], isNull);
    });

    test('a local upload keeps its PNG so the server signs it when applied', () {
      final ({String id, String source}) made = newSkin(slim: true, image: png, label: 'Guard');
      final JsonMap json = asMap(jsonDecode(made.source));
      expect(json['image'], base64Encode(png));
      expect(skinUnsigned(json), isTrue);
      expect(skinKeepsImage(json), isTrue);
      expect(skinKeepsImage(asMap(jsonDecode(newSkin(slim: false, image: Uint8List(keptSkinImageBytes + 1)).source))), isFalse, reason: 'too large for MineSkin');
      expect(skinUnsigned(_source('Guard')), isFalse);
    });
  });
}
