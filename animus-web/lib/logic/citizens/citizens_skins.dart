/// `CitizensSkins`: a player NPC's Citizens skin trait. The saved texture
/// becomes a skin file naming the account it came from (`player`),
/// `updateSkins` becomes `animus:skin refresh`, and a player NPC with no saved
/// texture that Citizens would dress in the skin of the account named like
/// it (`fetchDefaultSkin`, on by default) gets `animus:skin fromName`. A skin
/// name of `%player%` (the viewer's own skin) becomes `animus:mirror`; a
/// PlaceholderAPI skin name stays the skin's source.
library;

import 'field_notes.dart';
import 'java_json.dart';

/// The Mojang account names Citizens looks up.
final RegExp _account = RegExp(r'^[A-Za-z0-9_]{3,16}$');
final RegExp _colours = RegExp(r'[&§][0-9a-fk-orx]', caseSensitive: false);
final RegExp _placeholder = RegExp(r'^.*%[^%\s]+%.*$');
final RegExp _citizensPlaceholder = RegExp(r'^.*<[a-z_]+>.*$');
const Set<String> _viewer = <String>{'%player%', '%player_name%', '<player>', '<p>'};

class SkinsResult {
  const SkinsResult({required this.file, required this.options, required this.mirror, required this.notes});

  /// The skin file's text, when there is a texture to keep.
  final String? file;

  /// The `animus:skin` module; empty when it sets nothing.
  final JObject options;

  /// The skin name asked for each viewer's own skin.
  final bool mirror;
  final Map<String, FieldNote> notes;
}

String _text(JElement? value) => value is JPrimitive ? value.asString : '';

bool? _bool(JElement? value) {
  if (value is! JPrimitive) return null;
  if (value is JBool) return value.value;
  final String text = value.asString;
  return text == 'true' ? true : (text == 'false' ? false : null);
}

/// [trait] is the saved `skintrait`, or null; [name] is the converted display name.
SkinsResult mapCitizensSkin(JElement? trait, String name) {
  final JObject skin = trait is JObject ? trait : JObject();
  final Map<String, FieldNote> notes = <String, FieldNote>{};
  final JObject options = JObject();
  final String plain = name.replaceAll(_colours, '').trim();
  String skinName = _text(skin.get('skinName')).trim();
  String texture = _text(skin.get('textureRaw'));
  if (texture == 'cache') texture = '';
  final bool update = _bool(skin.get('updateSkins')) == true;
  final bool fetchDefault = _bool(skin.get('fetchDefaultSkin')) != false;
  final bool viewer = _viewer.contains(skinName.toLowerCase());
  if (viewer) notes['/traits/skintrait/skinName'] = const FieldNote(statusConverted, 'each viewer sees their own skin; became animus:mirror');
  if (skinName.toLowerCase() == '<npc>') skinName = plain;
  final bool placeholder = !viewer && _placeholder.hasMatch(skinName);
  if (!viewer && !placeholder && _citizensPlaceholder.hasMatch(skinName)) {
    notes['/traits/skintrait/skinName'] = FieldNote(requiresReview, 'the Citizens placeholder $skinName has no Animus equivalent; the skin it last filled is kept');
  }
  final String? source = placeholder ? skinName : (_account.hasMatch(skinName) ? skinName : null);
  if (texture.isEmpty) {
    final bool ownName = skinName.isEmpty || skinName.toLowerCase() == plain.toLowerCase();
    if (!viewer && ownName && (fetchDefault || update) && _account.hasMatch(plain)) {
      options.addBool('fromName', true);
      if (update) options.addBool('refresh', true);
    } else if (!viewer && !ownName && source != null) {
      notes['/traits/skintrait/skinName'] = FieldNote(requiresReview, 'the skin $skinName was saved without its texture; set it with /animus npc skin <npc> $skinName');
    }
    return SkinsResult(file: null, options: options, mirror: viewer, notes: notes);
  }
  final JObject file = JObject()..addString('textureRaw', texture);
  final String signature = _text(skin.get('signature'));
  if (signature.isNotEmpty) file.addString('signature', signature);
  if (source != null && !placeholder) file.addString('label', source);
  if (source != null) file.addString('player', source);
  if (source != null && (update || placeholder)) options.addBool('refresh', true);
  if (placeholder) {
    notes['/traits/skintrait/skinName'] = const FieldNote(statusConverted, 'filled by PlaceholderAPI for no viewer and looked up again every [skins] refreshMinutes');
  }
  return SkinsResult(file: writeJson(file), options: options, mirror: viewer, notes: notes);
}
