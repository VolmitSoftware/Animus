/// What an NPC definition says about how its body looks, read the way the
/// server reads it (`NpcAppearance`, `ArmorStandLook`, `NpcItem`,
/// `BodyLook`). Invalid fields fall back to the defaults the server would
/// refuse them for; the document's own diagnostics report them.
library;

import '../../logic/minecraft_ids.dart';
import '../../logic/npc/npc_appearance.dart';
import '../../logic/npc/npc_attributes.dart';
import '../../logic/gui/gui_text.dart';
import '../rigs/mc_rigs.dart';
import 'mc_text.dart';

/// `NpcPose`.
enum NpcPose {
  standing,
  sneaking,
  sleeping,
  swimming,
  sitting;

  static NpcPose? parse(Object? value) {
    for (final NpcPose pose in values) {
      if (pose.name == value) return pose;
    }
    return null;
  }
}

/// `EquipmentSlot`, in the definition's key order.
enum NpcSlot {
  mainHand('main_hand'),
  offHand('off_hand'),
  head('head'),
  chest('chest'),
  legs('legs'),
  feet('feet'),
  body('body'),
  saddle('saddle');

  const NpcSlot(this.key);

  final String key;

  static NpcSlot? parse(String key) {
    for (final NpcSlot slot in values) {
      if (slot.key == key) return slot;
    }
    return null;
  }
}

/// The equipment slots the client draws on each body, in slot order
/// (`BodyRules.equipmentSlots`). Other bodies (types Animus does not
/// present) keep every slot.
List<NpcSlot> mcDrawnSlots(String type) => switch (type) {
      'minecraft:player' || 'minecraft:armor_stand' || 'minecraft:zombie_villager' || 'minecraft:piglin' || 'minecraft:zombie' || 'minecraft:husk' ||
      'minecraft:drowned' || 'minecraft:giant' || 'minecraft:zombified_piglin' || 'minecraft:skeleton' || 'minecraft:stray' || 'minecraft:bogged' ||
      'minecraft:wither_skeleton' || 'minecraft:parched' || 'minecraft:piglin_brute' || 'minecraft:mannequin' => const <NpcSlot>[
          NpcSlot.mainHand, NpcSlot.offHand, NpcSlot.head, NpcSlot.chest, NpcSlot.legs, NpcSlot.feet, //
        ],
      'minecraft:horse' => const <NpcSlot>[NpcSlot.body, NpcSlot.saddle],
      'minecraft:llama' || 'minecraft:wolf' => const <NpcSlot>[NpcSlot.body],
      'minecraft:villager' => const <NpcSlot>[NpcSlot.mainHand, NpcSlot.head],
      'minecraft:vex' => const <NpcSlot>[NpcSlot.mainHand, NpcSlot.offHand],
      'minecraft:allay' || 'minecraft:fox' || 'minecraft:evoker' || 'minecraft:copper_golem' || 'minecraft:pillager' || 'minecraft:vindicator' ||
      'minecraft:illusioner' || 'minecraft:witch' => const <NpcSlot>[NpcSlot.mainHand],
      'minecraft:pig' || 'minecraft:donkey' || 'minecraft:mule' || 'minecraft:camel' => const <NpcSlot>[NpcSlot.saddle],
      'minecraft:turtle' || 'minecraft:frog' || 'minecraft:armadillo' || 'minecraft:ender_dragon' || 'minecraft:sheep' || 'minecraft:cat' ||
      'minecraft:parrot' || 'minecraft:axolotl' || 'minecraft:rabbit' || 'minecraft:creeper' || 'minecraft:slime' || 'minecraft:magma_cube' ||
      'minecraft:phantom' || 'minecraft:mooshroom' || 'minecraft:panda' || 'minecraft:goat' || 'minecraft:cow' || 'minecraft:chicken' ||
      'minecraft:shulker' || 'minecraft:pufferfish' || 'minecraft:tropical_fish' || 'minecraft:snow_golem' || 'minecraft:polar_bear' ||
      'minecraft:bee' || 'minecraft:sniffer' || 'minecraft:bat' || 'minecraft:enderman' || 'minecraft:wither' || 'minecraft:painting' ||
      'minecraft:item_frame' || 'minecraft:glow_item_frame' || 'minecraft:falling_block' || 'minecraft:end_crystal' || 'minecraft:block_display' ||
      'minecraft:item_display' || 'minecraft:text_display' || 'minecraft:item' => const <NpcSlot>[],
      _ => NpcSlot.values,
    };

/// One equipped item: its bare id (`diamond_sword`) and the `components`
/// text, of which the preview reads the few parts it can show.
final class NpcItem {
  const NpcItem({required this.id, this.count = 1, this.components});

  /// Null for a value the server refuses (`NpcItem.parse`).
  static NpcItem? parse(Object? value) {
    if (value is! Map<String, Object?>) return null;
    final Object? id = value['id'];
    if (id is! String || !validMinecraftId(id)) return null;
    final Object? count = value['count'];
    final Object? components = value['components'];
    return NpcItem(
      id: normalizeMinecraftId(id),
      count: count is int ? count : 1,
      components: components is String && components.startsWith('[') && components.endsWith(']') ? components : null,
    );
  }

  /// `minecraft:diamond_sword`.
  final String id;
  final int count;
  final String? components;

  /// The id without the `minecraft:` namespace; other namespaces stay.
  String get key => id.startsWith('minecraft:') ? id.substring(10) : id;

  /// The model the client draws: `item_model` when the components name one.
  String get modelKey {
    final String? model = component('item_model');
    if (model == null) return key;
    final String unquoted = model.replaceAll('"', '').replaceAll("'", '');
    return unquoted.startsWith('minecraft:') ? unquoted.substring(10) : unquoted;
  }

  /// Whether the client draws the enchantment glint: an explicit
  /// `enchantment_glint_override`, else any `enchantments`.
  bool get glint {
    final String? override = component('enchantment_glint_override');
    if (override != null) return override.trim() == 'true';
    final String? enchantments = component('enchantments');
    return enchantments != null && enchantments.replaceAll(RegExp(r'[\s{}]'), '').isNotEmpty;
  }

  /// `dyed_color` as 0xRRGGBB: `dyed_color=16711680` or `dyed_color={rgb:16711680}`.
  int? get dyedColor {
    final String? value = component('dyed_color');
    if (value == null) return null;
    final RegExpMatch? number = RegExp(r'-?\d+').firstMatch(value);
    return number == null ? null : int.parse(number.group(0)!) & 0xFFFFFF;
  }

  /// The raw value of one top-level component, or null. Nested brackets,
  /// braces and quotes are skipped, so commas inside values do not split.
  String? component(String name) {
    final String? text = components;
    if (text == null) return null;
    final String body = text.substring(1, text.length - 1);
    int depth = 0;
    String? quote;
    int start = 0;
    final List<String> entries = <String>[];
    for (int i = 0; i < body.length; i++) {
      final String c = body[i];
      if (quote != null) {
        if (c == '\\') {
          i++;
        } else if (c == quote) {
          quote = null;
        }
        continue;
      }
      if (c == '"' || c == "'") {
        quote = c;
      } else if (c == '[' || c == '{') {
        depth++;
      } else if (c == ']' || c == '}') {
        depth--;
      } else if (c == ',' && depth == 0) {
        entries.add(body.substring(start, i));
        start = i + 1;
      }
    }
    entries.add(body.substring(start));
    for (final String entry in entries) {
      final int equals = entry.indexOf('=');
      if (equals < 0) continue;
      String key = entry.substring(0, equals).trim();
      if (key.startsWith('minecraft:')) key = key.substring(10);
      if (key == name) return entry.substring(equals + 1).trim();
    }
    return null;
  }
}

/// `ArmorStandLook`: flags and part angles in degrees. Undeclared angles
/// keep the client's defaults.
final class NpcArmorStand {
  const NpcArmorStand({
    this.small = false,
    this.arms = false,
    this.basePlate = true,
    this.invisible = false,
    this.angles = const <String, List<double>>{},
  });

  /// Definition keys to rig part names.
  static const Map<String, String> partKeys = <String, String>{
    'head': 'head',
    'body': 'body',
    'leftArm': 'left_arm',
    'rightArm': 'right_arm',
    'leftLeg': 'left_leg',
    'rightLeg': 'right_leg',
  };

  static NpcArmorStand parse(Object? value) {
    if (value is! Map<String, Object?>) return const NpcArmorStand();
    bool flag(String key, bool fallback) => value[key] is bool ? value[key]! as bool : fallback;
    final Map<String, List<double>> angles = <String, List<double>>{};
    for (final MapEntry<String, String> part in partKeys.entries) {
      final Object? raw = value[part.key];
      if (raw is! List<Object?> || raw.length != 3) continue;
      final List<double> degrees = <double>[
        for (final Object? angle in raw)
          if (angle is num && angle.isFinite && angle.abs() <= 360) angle.toDouble(),
      ];
      if (degrees.length == 3) angles[part.value] = degrees;
    }
    return NpcArmorStand(
      small: flag('small', false),
      arms: flag('arms', false),
      basePlate: flag('basePlate', true),
      invisible: flag('invisible', false),
      angles: angles,
    );
  }

  final bool small;
  final bool arms;
  final bool basePlate;
  final bool invisible;

  /// Rig part name to `[x, y, z]` degrees.
  final Map<String, List<double>> angles;
}

/// Everything the preview draws for one definition.
final class NpcLook {
  const NpcLook({
    required this.type,
    required this.name,
    this.pose = NpcPose.standing,
    this.equipment = const <NpcSlot, NpcItem>{},
    this.hologram = const <String>[],
    this.separateRows = false,
    this.nameplateVisible = true,
    this.armorStand,
    this.skin,
    this.yawDeg = 0,
    this.scale = 1,
    this.settings = const <String, Object?>{},
    this.glowColor,
    this.teamCode,
    this.disguise,
    this.baby = false,
  });

  static const String player = 'minecraft:player';
  static const int maxText = 256;
  static const int maxLines = 16;

  static final RegExp _skin = RegExp(r'^[a-f0-9]{64}$');

  factory NpcLook.fromDefinition(Map<String, Object?> json) {
    final String? disguise = json['disguise'] is String && (json['disguise']! as String).trim().isNotEmpty ? json['disguise']! as String : null;
    final String own = json['type'] is String ? json['type']! as String : '';
    final String type = (disguise == null ? null : disguiseType(disguise)) ?? own;
    final Map<NpcSlot, NpcItem> equipment = <NpcSlot, NpcItem>{};
    final Object? declared = json['equipment'];
    if (declared is Map<String, Object?>) {
      for (final MapEntry<String, Object?> entry in declared.entries) {
        final NpcSlot? slot = NpcSlot.parse(entry.key);
        final NpcItem? item = NpcItem.parse(entry.value);
        if (slot != null && item != null) equipment[slot] = item;
      }
    }
    final List<String> lines = <String>[];
    final Object? hologram = json['hologram'];
    if (hologram is Map<String, Object?> && hologram['lines'] is List<Object?>) {
      for (final Object? line in (hologram['lines']! as List<Object?>).take(maxLines)) {
        final Object? text = line is Map<String, Object?> ? line['text'] : line;
        if (text is String && displayText(text)) lines.add(text);
      }
    }
    final Object? nameplate = json['nameplate'];
    final bool visible = nameplate is Map<String, Object?> && nameplate['visible'] is bool ? nameplate['visible']! as bool : true;
    final NpcPose parsedPose = NpcPose.parse(json['pose']) ?? NpcPose.standing;
    final Object? position = json['position'];
    final Object? yaw = position is Map<String, Object?> ? position['yaw'] : null;
    final Object? skin = json['skin'];
    return NpcLook(
      type: type,
      name: json['name'] is String ? json['name']! as String : '',
      pose: parsedPose == NpcPose.standing || type == player ? parsedPose : NpcPose.standing,
      equipment: equipment,
      hologram: lines,
      separateRows: separateRowsOf(hologram),
      nameplateVisible: visible,
      armorStand: type == 'minecraft:armor_stand' ? NpcArmorStand.parse(json['armorStand']) : null,
      skin: skin is String && _skin.hasMatch(skin) ? skin : null,
      yawDeg: yaw is num && yaw.isFinite ? yaw.toDouble() : 0,
      scale: definitionScale(json),
      glowColor: json['glowing'] == true ? _teamColor(json['teamColor']) : null,
      teamCode: _teamCode(json['teamColor']),
      settings: switch (json[bodySettingsField(type) ?? '']) {
        final Map<String, Object?> own => own,
        _ => const <String, Object?>{},
      },
      disguise: disguise,
      baby: json['baby'] == true,
    );
  }

  /// LibsDisguises' older names for vanilla types.
  static const Map<String, String> _disguiseNames = <String, String>{
    'mushroom_cow': 'mooshroom',
    'snowman': 'snow_golem',
    'pig_zombie': 'zombified_piglin',
    'dropped_item': 'item',
    'ender_crystal': 'end_crystal',
    'zombie_pigman': 'zombified_piglin',
  };

  /// The vanilla body type the first word of LibsDisguises [disguise] text
  /// names (`zombie setBaby true`), or null for a custom disguise or another
  /// entity. Only the server reads the rest of the options.
  static String? disguiseType(String disguise) {
    final String first = disguise.trim().split(RegExp(r'\s+')).first.toLowerCase().replaceAll('-', '_');
    final String bare = first.startsWith('minecraft:') ? first.substring(10) : first;
    final String type = 'minecraft:${_disguiseNames[bare] ?? bare}';
    return mcPresentedBodyTypes.contains(type) ? type : null;
  }

  final String type;
  final String name;
  final NpcPose pose;
  final Map<NpcSlot, NpcItem> equipment;
  final List<String> hologram;

  /// Each hologram line has a text display of its own, so none carries its
  /// style into the next (see [separateRowsOf]).
  final bool separateRows;
  final bool nameplateVisible;
  final NpcArmorStand? armorStand;

  /// SHA-256 of a skin document under `skins/`.
  final String? skin;
  final double yawDeg;

  /// The `scale` attribute: the body and everything it wears, and the text
  /// over it, grow with it.
  final double scale;

  /// The body type's own settings (`horse`, `llama`, ...) as written;
  /// settings of another body are ignored, as the server refuses them.
  final Map<String, Object?> settings;

  /// The outline colour (`0xRRGGBB`) of a glowing body: its `teamColor`,
  /// white without one, as the client draws it. Null when it does not glow.
  final int? glowColor;

  /// The `teamColor` as a colour code (`c` for red), or null without one;
  /// the server writes it before the name.
  final String? teamCode;

  /// The definition's LibsDisguises text, or null.
  final String? disguise;

  /// The `baby` trait.
  final bool baby;

  /// `teamColor` names in `ChatFormatting` order, codes 0 to f.
  static const List<String> teamColors = <String>[
    'black', 'dark_blue', 'dark_green', 'dark_aqua', 'dark_red', 'dark_purple', 'gold', 'gray', //
    'dark_gray', 'blue', 'green', 'aqua', 'red', 'light_purple', 'yellow', 'white',
  ];

  static String? _teamCode(Object? name) {
    final int index = name is String ? teamColors.indexOf(name) : -1;
    return index < 0 ? null : index.toRadixString(16);
  }

  static int _teamColor(Object? name) {
    final int index = teamColors.indexOf(name is String ? name : 'white');
    return mcLegacyColors[(index < 0 ? 15 : index).toRadixString(16)]!;
  }

  bool get isPlayer => type == player;

  /// At most 256 UTF-16 units, no control characters.
  static bool displayText(String text) =>
      text.length <= maxText && !text.runes.any((int c) => c < 0x20 || (c >= 0x7F && c < 0xA0));

  /// `BodyRules.validateProfileName`: 1..16 characters, no control characters.
  static bool fitsProfile(String name) => name.trim().isNotEmpty && name.length <= 16 && displayText(name);

  /// A player whose name fits a profile and carries no formatting shows the
  /// client's own nameplate (`BodyLook.nativeName`).
  bool get nativeName => isPlayer && nameplateVisible && fitsProfile(name) && !guiFormatted(name);

  /// `HologramStyle.separateRows`: a `hologram` with a line height or a
  /// line with a margin gives every line its own text display.
  static bool separateRowsOf(Object? hologram) {
    if (hologram is! Map<String, Object?>) return false;
    if (hologram['lineHeight'] is num) return true;
    final Object? lines = hologram['lines'];
    return lines is List<Object?> &&
        lines.any((Object? line) => line is Map<String, Object?> && <Object?>[line['marginTop'], line['marginBottom']].any((Object? margin) => margin is num && margin != 0));
  }

  /// The text display's lines, section-coded as `BodyLook` sends them: the
  /// hologram, then the name (after its team colour) when the client does
  /// not draw it itself. The name is its own field, so in one display it
  /// starts with a reset when a line above it is styled.
  List<String> get textLines {
    final List<String> lines = <String>[for (final String line in hologram) guiColorize(line)];
    if (nameplateVisible && !nativeName) {
      final String coloured = guiColorize(teamCode == null ? name : '&$teamCode$name');
      final bool styledAbove = lines.any((String line) => line.contains('§'));
      lines.add(styledAbove && !separateRows ? '§r$coloured' : coloured);
    }
    return lines;
  }

  /// [textLines] as the client draws them: one display carries a style on
  /// down its rows, a display per line starts each fresh.
  List<List<McTextRun>> get textRows =>
      <List<McTextRun>>[for (final List<List<McTextRun>> line in guiDisplayRows(textLines, separate: separateRows, sample: false)) ...line];
}
