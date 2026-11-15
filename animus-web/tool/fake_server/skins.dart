/// `POST /v1/skins/sign` and `GET /v1/skins/sign/<job>`, as EditorSkinRoutes:
/// a job is signing at its first poll and signed at the next, with a stand-in
/// signature (no MineSkin here). While [failing] is set (`/__fake/skin-sign`)
/// every job fails as MineSkin refusing the image would.
library;

import 'dart:convert';

import 'messages.dart';
import 'protocol.dart';

class FakeSkinJobs {
  final Map<String, _Job> _jobs = <String, _Job>{};
  int _next = 0;
  bool failing = false;

  FakeResponse sign(FakeRequest request, String tokenId) {
    final Map<String, Object?> body = bodyObject(request);
    fields(body, r'$', <String>{'image', 'url', 'slim', 'label'});
    if (body.containsKey('image') == body.containsKey('url')) throw ApiError('invalid-request', r'$: send exactly one of image or url');
    final Object? image = body['image'], url = body['url'], slim = body['slim'], label = body['label'];
    if (image != null && (image is! String || base64Decode(image).length > 20000)) throw ApiError('invalid-request', r'$.image: expected a base64 PNG of at most 20000 bytes');
    if (url != null && (url is! String || !RegExp(r'^https?://\S{1,248}$').hasMatch(url))) throw ApiError('invalid-request', r'$.url: expected an http or https address');
    if (slim != null && slim is! bool) throw ApiError('invalid-request', r'$.slim: expected a boolean');
    if (label != null && (label is! String || label.length > 64)) throw ApiError('invalid-request', r'$.label: expected a string of at most 64 characters');
    final String id = (++_next).toRadixString(16).padLeft(32, '0');
    _jobs[id] = _Job(tokenId, '${image ?? url}', slim == true, (label as String?)?.trim() ?? '', failing);
    return FakeResponse(202, body: <String, Object?>{'job': id, 'state': 'signing'});
  }

  FakeResponse status(String id, String tokenId) {
    final _Job? job = _jobs[id];
    if (job == null || job.tokenId != tokenId) throw ApiError('not-found', 'No skin signing job $id.');
    if (job.polls++ == 0) return FakeResponse(200, body: <String, Object?>{'job': id, 'state': 'signing'});
    if (job.fails) return FakeResponse(200, body: <String, Object?>{'job': id, 'state': 'failed', 'message': 'MineSkin could not read the skin image: Invalid image dimensions'});
    final String texture = base64Encode(utf8.encode(jsonEncode(<String, Object?>{
      'textures': <String, Object?>{
        'SKIN': <String, Object?>{
          'url': 'https://textures.minecraft.net/texture/${sha256Text(job.source).substring(0, 40)}',
          if (job.slim) 'metadata': <String, Object?>{'model': 'slim'},
        },
      },
    })));
    final Map<String, Object?> source = <String, Object?>{'textureRaw': texture, 'signature': base64Encode(utf8.encode('fake signature')), if (job.label.isNotEmpty) 'label': job.label};
    return FakeResponse(200, body: <String, Object?>{
      'job': id,
      'state': 'signed',
      'skin': <String, Object?>{'id': sha256Text(prettyJson(source)), 'source': source},
    });
  }
}

class _Job {
  _Job(this.tokenId, this.source, this.slim, this.label, this.fails);

  final String tokenId;
  final String source;
  final bool slim;
  final String label;
  final bool fails;
  int polls = 0;
}
