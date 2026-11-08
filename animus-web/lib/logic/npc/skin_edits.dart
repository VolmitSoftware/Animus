/// Skin edits that touch more than one document. A skin document's id is the
/// SHA-256 of its text (`SkinDocuments` refuses a mismatch), so changing its
/// content makes a new document: the NPCs and groups that name the old hash
/// move to the new one in the same commit, the kept PNG follows, and the old
/// document goes once nothing names it.
library;

import 'dart:convert';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';

import '../../mc/npc/npc_skin.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../../state/editor_store.dart';
import '../gui/gui_json.dart';
import '../offline/new_documents.dart';
import 'group_model.dart';

/// What a skin edit writes, as one undo step.
class SkinChange {
  const SkinChange({required this.documents, required this.resources, required this.label, required this.skin});

  final List<DocumentChange> documents;
  final Map<String, Uint8List?> resources;
  final String label;

  /// The skin the edited documents name afterwards.
  final String? skin;
}

typedef SkinCommit = Future<void> Function(SkinChange change);

/// What skin editors need beyond one document: the workspace's kept PNGs and
/// a commit for several documents and resources at once.
class SkinEditing {
  const SkinEditing({required this.resources, required this.commit});

  final Map<String, Uint8List> resources;
  final SkinCommit commit;
}

/// The NPC or group whose skin an edit sets.
class SkinTarget {
  const SkinTarget(this.kind, this.id);

  final DocumentKind kind;
  final String id;

  bool matches(AnimusDocument document) => document.kind == kind && document.id == id;
}

/// The optional `label` of a skin document, a name shown in pickers (set in
/// game from the Skins screen); null when it has none. The hash stays the id.
String? skinDocumentLabel(Object? skin) {
  final Object? label = asMap(skin)['label'];
  return label is String && label.trim().isNotEmpty ? label.trim() : null;
}

/// How every skin list names [skin]: its label, or its first 12 hex
/// characters without one; the hash (when labelled) and model are the
/// secondary detail.
({String label, String detail}) skinListing(AnimusDocument skin) {
  final NpcSkinInfo? info = NpcSkinInfo.fromDocument(skin.json);
  final String model = info == null ? 'unreadable' : '${info.slim ? 'slim' : 'classic'}${info.signed ? ', signed' : ''}';
  final String short = skin.id.substring(0, skin.id.length.clamp(0, 12));
  final String? label = skinDocumentLabel(skin.json);
  return label == null ? (label: short, detail: model) : (label: label, detail: '$short, $model');
}

/// Why the content of [skin] cannot be edited, or null when it can.
String? skinEditBlock(Object? skin) {
  final JsonMap json = asMap(skin);
  if (json['signature'] is String) return 'Signed by Mojang; the model is part of the signed data.';
  if (_texture(json) == null) return 'The texture data is unreadable. Fix it in Code.';
  return null;
}

JsonMap? _texture(JsonMap skin) {
  final Object? raw = skin['textureRaw'];
  if (raw is! String || raw.isEmpty) return null;
  try {
    final Object? decoded = jsonDecode(utf8.decode(base64.decode(base64.normalize(raw))));
    return decoded is Map<String, Object?> ? decoded : null;
  } on FormatException {
    return null;
  }
}

({String id, String source}) _made(JsonMap json) {
  final String source = prettyJson(json);
  return (id: sha256.convert(utf8.encode(source)).toString(), source: source);
}

/// [skin] with the slim or classic arm model, as a new content-addressed
/// document; null for a signed or unreadable skin.
({String id, String source})? skinWithModel(Object? skin, {required bool slim}) {
  if (skinEditBlock(skin) != null) return null;
  final JsonMap json = asMap(skin);
  final JsonMap texture = _texture(json)!;
  final JsonMap textures = <String, Object?>{...asMap(texture['textures'])};
  final JsonMap entry = <String, Object?>{...asMap(textures['SKIN'])};
  final JsonMap metadata = <String, Object?>{...asMap(entry['metadata'])};
  if (slim) {
    metadata['model'] = 'slim';
  } else {
    metadata.remove('model');
  }
  if (metadata.isEmpty) {
    entry.remove('metadata');
  } else {
    entry['metadata'] = metadata;
  }
  textures['SKIN'] = entry;
  final String raw = base64Encode(utf8.encode(jsonEncode(<String, Object?>{...texture, 'textures': textures})));
  return _made(<String, Object?>{...json, 'textureRaw': raw});
}

JsonMap _values(AnimusDocument document) => asMap(asMap(document.json)['values']);

bool _wears(AnimusDocument document, String hash) => switch (document.kind) {
      DocumentKind.npc => asMap(document.json)['skin'] == hash,
      DocumentKind.group => _values(document)['skin'] == hash || asList(_values(document)['skins']).contains(hash),
      _ => false,
    };

/// The NPCs and groups naming [hash]: an NPC's `skin`, a group's
/// `values.skin` or an entry of its `values.skins` pool.
List<AnimusDocument> skinWearers(Iterable<AnimusDocument> documents, String hash) => <AnimusDocument>[
      for (final AnimusDocument document in documents)
        if (_wears(document, hash)) document,
    ];

/// [json] of a [kind] document with skin [from] replaced by [to] (null
/// removes it).
Object? _retarget(DocumentKind kind, Object? json, String from, String? to) {
  final JsonMap doc = asMap(json);
  if (kind == DocumentKind.npc) {
    if (doc['skin'] != from) return json;
    return <String, Object?>{...doc}..remove('skin')..addAll(<String, Object?>{'skin': ?to});
  }
  final JsonMap values = <String, Object?>{...asMap(doc['values'])};
  if (values['skin'] == from) {
    values.remove('skin');
    if (to != null) values['skin'] = to;
  }
  if (values['skins'] is List<Object?>) {
    final List<Object?> pool = <Object?>[];
    for (final Object? item in asList(values['skins'])) {
      final Object? next = item == from ? to : item;
      if (next != null && !pool.contains(next)) pool.add(next);
    }
    values['skins'] = pool;
  }
  return <String, Object?>{...doc, 'values': values};
}

/// [json] of a [kind] document wearing [to] as its own skin.
Object? _setSkin(DocumentKind kind, Object? json, String to) {
  final JsonMap doc = asMap(json);
  if (kind == DocumentKind.npc) return <String, Object?>{...doc, 'skin': to};
  return <String, Object?>{
    ...doc,
    'values': <String, Object?>{...asMap(doc['values']), 'skin': to},
  };
}

AnimusDocument? _find(Iterable<AnimusDocument> documents, DocumentKind kind, String id) {
  for (final AnimusDocument document in documents) {
    if (document.kind == kind && document.id == id) return document;
  }
  return null;
}

DocumentChange _create(({String id, String source}) made) => DocumentChange(DocumentKind.skin, made.id, made.source, path: newDocumentPath(DocumentKind.skin, made.id));

/// Switches skin [hash] to the slim or classic model. Without [only] every
/// wearer moves to the new document and the old one is deleted; with [only]
/// just that NPC or group moves, and the old document stays while anything
/// else wears it. Null when nothing would change.
SkinChange? setSkinModel({
  required List<AnimusDocument> documents,
  required Map<String, Uint8List> resources,
  required String hash,
  required bool slim,
  SkinTarget? only,
}) {
  final AnimusDocument? skin = _find(documents, DocumentKind.skin, hash);
  if (skin == null) return null;
  final ({String id, String source})? made = skinWithModel(skin.json, slim: slim);
  if (made == null || made.id == hash) return null;
  final List<AnimusDocument> wearers = skinWearers(documents, hash);
  final List<AnimusDocument> moving = only == null ? wearers : <AnimusDocument>[...wearers.where(only.matches)];
  final bool dropOld = moving.length == wearers.length;
  final Uint8List? png = resources[skinImagePath(hash)];
  return SkinChange(
    documents: <DocumentChange>[
      if (_find(documents, DocumentKind.skin, made.id) == null) _create(made),
      for (final AnimusDocument wearer in moving)
        DocumentChange.update(wearer.kind, wearer.id, (Object? latest) => _retarget(wearer.kind, latest, hash, made.id)),
      if (dropOld) DocumentChange(DocumentKind.skin, hash, null),
    ],
    resources: <String, Uint8List?>{
      skinImagePath(made.id): ?png,
      if (png != null && dropOld) skinImagePath(hash): null,
    },
    label: '${slim ? 'slim' : 'classic'} arms for skin ${made.id.substring(0, 8)}',
    skin: made.id,
  );
}

/// A skin document made from [png] (and [url] when it is on Mojang's host),
/// with the PNG kept beside the workspace, worn by [target].
SkinChange skinFromPng({
  required List<AnimusDocument> documents,
  required Uint8List png,
  required bool slim,
  required SkinTarget target,
  String? url,
  String? label,
}) {
  final ({String id, String source}) made = newSkin(slim: slim, url: url != null && isMojangSkinUrl(url) ? url.trim() : null, image: png, label: label);
  return SkinChange(
    documents: <DocumentChange>[
      if (_find(documents, DocumentKind.skin, made.id) == null) _create(made),
      DocumentChange.update(target.kind, target.id, (Object? latest) => _setSkin(target.kind, latest, made.id)),
    ],
    resources: <String, Uint8List?>{skinImagePath(made.id): png},
    label: 'new skin ${made.id.substring(0, 8)} for ${target.id}',
    skin: made.id,
  );
}

/// The delete confirmation's detail for skin [hash]: who would be left
/// naming a missing skin.
String skinDeleteWarning(List<AnimusDocument> documents, String hash) {
  final List<String> names = <String>[
    for (final AnimusDocument wearer in skinWearers(documents, hash))
      wearer.kind == DocumentKind.npc ? '${asString(asMap(wearer.json)['name']) ?? wearer.id} (${wearer.id})' : 'group ${wearer.id}',
  ];
  if (names.isEmpty) return 'No NPC or group wears it. Undo brings it back.';
  final String listed = names.length == 1 ? names.single : '${names.take(names.length - 1).join(', ')} and ${names.last}';
  return 'Worn by $listed; they would name a missing skin. Undo brings it back.';
}

bool _givesSkin(JsonMap values) => values.containsKey('skin') || asList(values['skins']).isNotEmpty;

/// The groups [npc] lists, in merge order, whose values give it a skin.
List<String> skinGroupsOf(List<AnimusDocument> documents, JsonMap npc) {
  final Map<String, JsonMap> groups = workspaceGroups(documents);
  return <String>[
    for (final String id in npcGroups(npc))
      if (groups[id] case final JsonMap group when _givesSkin(asMap(group['values']))) id,
  ];
}

/// How many NPCs other than [npcId] list one of [groups].
int otherGroupMembers(List<AnimusDocument> documents, String npcId, List<String> groups) => documents
    .where((AnimusDocument document) => document.kind == DocumentKind.npc && document.id != npcId && npcGroups(asMap(document.json)).any(groups.contains))
    .length;

/// NPC [npcId] with body [type] and no skin: its own `skin` goes, and so do
/// the `skin` and `skins` values of the groups that give it one, since the
/// server refuses a skin on any body but a player's.
SkinChange typeWithoutSkin({required List<AnimusDocument> documents, required String npcId, required String type}) {
  final AnimusDocument? npc = _find(documents, DocumentKind.npc, npcId);
  final List<String> groups = npc == null ? const <String>[] : skinGroupsOf(documents, asMap(npc.json));
  return SkinChange(
    documents: <DocumentChange>[
      DocumentChange.update(DocumentKind.npc, npcId, (Object? latest) => <String, Object?>{...asMap(latest), 'type': type}..remove('skin')),
      for (final String id in groups)
        DocumentChange.update(DocumentKind.group, id, (Object? latest) {
          final JsonMap doc = asMap(latest);
          return <String, Object?>{
            ...doc,
            'values': <String, Object?>{...asMap(doc['values'])}
              ..remove('skin')
              ..remove('skins'),
          };
        }),
    ],
    resources: const <String, Uint8List?>{},
    label: 'body $type without a skin for $npcId',
    skin: null,
  );
}
