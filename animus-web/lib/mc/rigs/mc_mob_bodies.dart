/// The living bodies drawn from the generated layers
/// (`mc_vanilla_layers.g.dart`): which layers each type stacks, with which
/// texture, tint and pose for its body settings, as each type's renderer and
/// render layers draw it in the 26.3 client. Texture ids are jar paths under
/// `textures/` without `.png`, all listed in [mcMobTextureIds].
library;

import '../scene/mc_math.dart';
import 'mc_rig.dart';
import 'mc_vanilla_layer_pose.dart';
import 'mc_vanilla_layers.g.dart';

const List<double> _white = <double>[1, 1, 1];

/// `EnergySwirlLayer` adds its grey swirl to what is behind it; the
/// preview, which only blends, draws it half see-through.
const double _swirl = 0.5;

/// One model layer drawn over the body.
final class McMobLayer {
  const McMobLayer(this.layer, this.texture, {this.poses = const <String>[], this.tint = _white, this.opacity = 1});

  /// The generated layer key (`sheep#wool`).
  final String layer;
  final String texture;

  /// Second poses applied in order (`sitting`), each when the layer has it.
  final List<String> poses;
  final List<double> tint;

  /// Below 1 for a see-through layer.
  final double opacity;
}

/// A block model the body carries (a snow golem's pumpkin, a mooshroom's
/// mushrooms): [transform] maps block space (corner at the origin, one block
/// across) into the space of [part], or into model space with no part.
final class McMobBlock {
  const McMobBlock(this.blockKey, this.transform, {this.part});

  final String blockKey;
  final McMat4 transform;
  final String? part;
}

final class McMobBody {
  const McMobBody({
    required this.layers,
    required this.heightBlocks,
    this.scale = 1,
    this.offsetPx = McVec3.zero,
    this.frame,
    this.blocks = const <McMobBlock>[],
    this.notes = const <String>[],
    this.armor,
  });

  final List<McMobLayer> layers;

  /// The hitbox height, blocks: where names and text anchor.
  final double heightBlocks;

  /// The renderer's own `scale()` in model space, about the model origin:
  /// a uniform scale, then a move in model pixels.
  final double scale;
  final McVec3 offsetPx;

  /// The renderer's `setupRotations` after the body turn, in the preview's
  /// rig frame (feet at the origin, facing +Z).
  final McMat4? frame;
  final List<McMobBlock> blocks;

  /// What the settings ask for that the preview leaves out.
  final List<String> notes;

  /// The type whose `<type>#helmet` ... `#boots` layers carry worn armour,
  /// for humanoid mobs.
  final String? armor;
}

/// The generated layer as a rig: [scale] and [offsetPx] wrap it the way the
/// renderer's `scale()` does, about the feet.
McRig mcMobRig(String layer, {double scale = 1, McVec3 offsetPx = McVec3.zero, double heightBlocks = 0}) {
  final McRig base = mcVanillaLayers[layer]!;
  if (scale == 1 && offsetPx == McVec3.zero) return base.copyWith(heightBlocks: heightBlocks);
  // Model space has its origin 24 px over the feet, Y down: scaling about it
  // and moving by [offsetPx] is a part at (0, 24, 0) + scale * (offset - (0, 24, 0)).
  const McVec3 origin = McVec3(0, 24, 0);
  return base.copyWith(
    id: '${base.id}*$scale+${offsetPx.x},${offsetPx.y},${offsetPx.z}',
    heightBlocks: heightBlocks,
    parts: <McRigPart>[McRigPart(name: '_scale', pivot: origin + (offsetPx - origin) * scale, scale: scale, children: base.parts)],
  );
}

/// The pose of [layer] with its second poses [names] applied.
McRigPose mcMobPose(String layer, List<String> names) => mcLayerPose(mcVanillaLayerHidden[layer] ?? const <String>{}, <McVanillaLayerPose>[
      for (final String name in names) ?mcVanillaLayerPoses['$layer@$name'],
    ]);

/// The preview's frame for a client `setupRotations` step [clientSide],
/// which runs after the client's `180 - yaw` turn: the same step seen from
/// the preview's frame, which faces the other way.
McMat4 mcClientFrame(McMat4 clientSide) => McMat4.rotationY(180).multiply(clientSide).multiply(McMat4.rotationY(-180));

const List<String> mcDyes = <String>[
  'white', 'orange', 'magenta', 'light_blue', 'yellow', 'lime', 'pink', 'gray', //
  'light_gray', 'cyan', 'purple', 'blue', 'brown', 'green', 'red', 'black',
];

/// `DyeColor.getTextureDiffuseColor`, in [mcDyes] order.
const List<int> mcDyeDiffuse = <int>[
  0xF9FFFE, 0xF9801D, 0xC74EBD, 0x3AB3DA, 0xFED83D, 0x80C71F, 0xF38BAA, 0x474F52, //
  0x9D9D97, 0x169C9C, 0x8932B8, 0x3C44AA, 0x835432, 0x5E7C16, 0xB02E26, 0x1D1D21,
];

List<double> mcRgb(int color) => <double>[((color >> 16) & 0xFF) / 255, ((color >> 8) & 0xFF) / 255, (color & 0xFF) / 255];

/// A dye's diffuse colour as a tint; white for an unknown name.
List<double> mcDyeTint(String dye) {
  final int index = mcDyes.indexOf(dye);
  return index < 0 ? _white : mcRgb(mcDyeDiffuse[index]);
}

/// `ColorLerper.Type.SHEEP`: the diffuse colour at three quarters, white
/// wool a fixed light grey.
List<double> mcWoolTint(String dye) {
  final int index = mcDyes.indexOf(dye);
  if (index <= 0) return mcRgb(0xE6E6E6);
  final int c = mcDyeDiffuse[index];
  int part(int shift) => (((c >> shift) & 0xFF) * 0.75).floor();
  return mcRgb(part(16) << 16 | part(8) << 8 | part(0));
}

String _choice(Map<String, Object?> settings, String key, List<String> values) => switch (settings[key]) {
      final String value when values.contains(value) => value,
      _ => values.first,
    };

bool _flag(Map<String, Object?> settings, String key) => settings[key] == true;

int _number(Map<String, Object?> settings, String key, int fallback, int min, int max) => switch (settings[key]) {
      final int value => value.clamp(min, max),
      _ => fallback,
    };

const List<String> _climates = <String>['temperate', 'warm', 'cold'];
const List<String> mcCatVariants = <String>[
  'tabby', 'black', 'red', 'siamese', 'british_shorthair', 'calico', 'persian', 'ragdoll', 'white', 'jellie', 'all_black', //
];
const List<String> mcWolfVariants = <String>['pale', 'spotted', 'snowy', 'black', 'ashen', 'rusty', 'woods', 'chestnut', 'striped'];
const List<String> mcParrotVariants = <String>['red_blue', 'blue', 'green', 'yellow_blue', 'gray'];
const List<String> mcAxolotlVariants = <String>['lucy', 'wild', 'gold', 'cyan', 'blue'];
const List<String> mcRabbitTypes = <String>['brown', 'white', 'black', 'white_splotched', 'gold', 'salt', 'evil'];
const List<String> mcVillagerTypes = <String>['plains', 'desert', 'jungle', 'savanna', 'snow', 'swamp', 'taiga'];
const List<String> mcVillagerProfessions = <String>[
  'none', 'armorer', 'butcher', 'cartographer', 'cleric', 'farmer', 'fisherman', 'fletcher', 'leatherworker', 'librarian', //
  'mason', 'nitwit', 'shepherd', 'toolsmith', 'weaponsmith',
];
const List<String> mcVillagerLevels = <String>['novice', 'apprentice', 'journeyman', 'expert', 'master'];
const List<String> _levelBadges = <String>['stone', 'iron', 'gold', 'emerald', 'diamond'];
const List<String> mcPandaGenes = <String>['normal', 'lazy', 'worried', 'playful', 'brown', 'weak', 'aggressive'];
const List<String> mcTropicalPatterns = <String>[
  'kob', 'sunstreak', 'snooper', 'dasher', 'brinely', 'spotty', 'flopper', 'stripey', 'glitter', 'blockfish', 'betty', 'clayfish', //
];
const List<String> mcCopperWeather = <String>['unaffected', 'exposed', 'weathered', 'oxidized'];

/// Hats drawn by a villager's clothes (`.png.mcmeta` `villager.hat`).
const Set<String> _fullHats = <String>{'desert', 'snow', 'farmer', 'fisherman', 'fletcher', 'librarian', 'shepherd'};

/// `Panda.getVariant`: the main gene shows unless it is recessive and the
/// hidden gene differs.
String mcPandaLook(String main, String hidden) {
  const Set<String> recessive = <String>{'brown', 'weak'};
  if (!recessive.contains(main)) return main;
  return main == hidden ? main : 'normal';
}

String _pandaTexture(String gene) => gene == 'normal' ? 'entity/panda/panda' : 'entity/panda/panda_$gene';

String _rabbitTexture(String type) => switch (type) {
      'evil' => 'entity/rabbit/rabbit_caerbannog',
      _ => 'entity/rabbit/rabbit_$type',
    };

String _wolfTexture(String variant, {required bool tame, required bool angry}) {
  final String base = variant == 'pale' ? 'entity/wolf/wolf' : 'entity/wolf/wolf_$variant';
  return angry ? '${base}_angry' : (tame ? '${base}_tame' : base);
}

String _copperSuffix(String weather) => weather == 'unaffected' ? '' : '_$weather';

/// The villager clothes over [prefix]'s base (`villager` or
/// `zombie_villager`), as `VillagerProfessionLayer` stacks them.
List<McMobLayer> _clothes(String prefix, String layer, String noHatLayer, Map<String, Object?> settings) {
  final String type = _choice(settings, 'type', mcVillagerTypes);
  final String profession = _choice(settings, 'profession', mcVillagerProfessions);
  final String level = _choice(settings, 'level', mcVillagerLevels);
  final bool professionHat = _fullHats.contains(profession);
  final bool partialHat = profession == 'butcher';
  final bool typeHat = !professionHat && !(partialHat && _fullHats.contains(type));
  return <McMobLayer>[
    McMobLayer(typeHat ? layer : noHatLayer, 'entity/$prefix/type/$type'),
    if (profession != 'none') McMobLayer(layer, 'entity/$prefix/profession/$profession'),
    if (profession != 'none' && profession != 'nitwit') McMobLayer(layer, 'entity/$prefix/profession_level/${_levelBadges[mcVillagerLevels.indexOf(level)]}'),
  ];
}

/// The body of [type] with its own [settings] (`sheep`, `wolf`, ...), or null
/// when the type is not one of these mobs.
McMobBody? mcMobBody(String type, Map<String, Object?> settings) {
  McMobLayer one(String layer, String texture, {List<String> poses = const <String>[]}) => McMobLayer(layer, texture, poses: poses);
  McMobBody simple(String layer, String texture, double height, {double scale = 1}) =>
      McMobBody(layers: <McMobLayer>[one(layer, texture)], heightBlocks: height, scale: scale);
  List<String> flags(List<String> keys) => <String>[for (final String key in keys) if (_flag(settings, key)) key];

  switch (type) {
    case 'minecraft:allay':
      return McMobBody(layers: <McMobLayer>[one('allay#main', 'entity/allay/allay', poses: flags(<String>['dancing']))], heightBlocks: 0.6);
    case 'minecraft:axolotl':
      return McMobBody(
        layers: <McMobLayer>[one('axolotl#main', 'entity/axolotl/axolotl_${_choice(settings, 'variant', mcAxolotlVariants)}', poses: flags(<String>['playingDead']))],
        heightBlocks: 0.42,
      );
    case 'minecraft:bat':
      return McMobBody(layers: <McMobLayer>[one('bat#main', 'entity/bat/bat', poses: flags(<String>['resting']))], heightBlocks: 0.9);
    case 'minecraft:bee':
      final bool angry = _flag(settings, 'angry'), nectar = _flag(settings, 'nectar');
      return McMobBody(
        layers: <McMobLayer>[
          one('bee#main', 'entity/bee/bee${angry ? '_angry' : ''}${nectar ? '_nectar' : ''}', poses: <String>[if (_flag(settings, 'stung')) 'stung']),
        ],
        heightBlocks: 0.5,
      );
    case 'minecraft:blaze':
      return simple('blaze#main', 'entity/blaze/blaze', 1.8);
    case 'minecraft:bogged':
      return McMobBody(
        layers: <McMobLayer>[one('bogged#main', 'entity/skeleton/bogged'), one('bogged#outer', 'entity/skeleton/bogged_overlay')],
        heightBlocks: 1.99,
        armor: 'bogged',
      );
    case 'minecraft:breeze':
      return McMobBody(
        layers: <McMobLayer>[
          one('breeze#main', 'entity/breeze/breeze'),
          one('breeze#wind', 'entity/breeze/breeze_wind'),
          one('breeze#eyes', 'entity/breeze/breeze_eyes'),
        ],
        heightBlocks: 1.77,
      );
    case 'minecraft:camel':
    case 'minecraft:camel_husk':
      return McMobBody(
        layers: <McMobLayer>[
          one('camel#main', type == 'minecraft:camel' ? 'entity/camel/camel' : 'entity/camel/camel_husk', poses: flags(<String>['sitting', 'dashing'])),
        ],
        heightBlocks: 2.375,
      );
    case 'minecraft:cat':
      final String? collar = settings['collar'] is String && mcDyes.contains(settings['collar']) ? settings['collar']! as String : null;
      final List<String> catPoses = flags(<String>['sitting', 'lying']);
      final bool lying = catPoses.contains('lying');
      return McMobBody(
        layers: <McMobLayer>[
          one('cat#main', 'entity/cat/cat_${_choice(settings, 'variant', mcCatVariants)}', poses: catPoses),
          if (collar != null) McMobLayer('cat#collar', 'entity/cat/cat_collar', poses: catPoses, tint: mcDyeTint(collar)),
        ],
        heightBlocks: 0.7,
        // `CatRenderer.setupRotations` at a full lie-down.
        frame: lying ? mcClientFrame(McMat4.translation(0.4, 0.15, 0.1).multiply(McMat4.rotationZ(90))) : null,
      );
    case 'minecraft:cave_spider':
      return McMobBody(layers: <McMobLayer>[one('cave_spider#main', 'entity/spider/cave_spider'), one('cave_spider#main', 'entity/spider/spider_eyes')], heightBlocks: 0.5);
    case 'minecraft:chicken':
      final String climate = _choice(settings, 'variant', _climates);
      return simple(climate == 'cold' ? 'cold_chicken#main' : 'chicken#main', 'entity/chicken/chicken_$climate', 0.7);
    case 'minecraft:cod':
      return McMobBody(layers: <McMobLayer>[one('cod#main', 'entity/fish/cod')], heightBlocks: 0.3, frame: _landed(0.1, 0.1, -0.1));
    case 'minecraft:copper_golem':
      final String weather = _copperSuffix(_choice(settings, 'weather', mcCopperWeather));
      return McMobBody(
        layers: <McMobLayer>[
          one('copper_golem#main', 'entity/copper_golem/copper_golem$weather'),
          one('copper_golem#main', 'entity/copper_golem/copper_golem_eyes$weather'),
        ],
        heightBlocks: 0.98,
      );
    case 'minecraft:cow':
      final String climate = _choice(settings, 'variant', _climates);
      return simple(climate == 'temperate' ? 'cow#main' : '${climate}_cow#main', 'entity/cow/cow_$climate', 1.4);
    case 'minecraft:creaking':
      return simple('creaking#main', 'entity/creaking/creaking', 2.7);
    case 'minecraft:creeper':
      return McMobBody(
        layers: <McMobLayer>[
          one('creeper#main', 'entity/creeper/creeper'),
          if (_flag(settings, 'powered')) const McMobLayer('creeper#armor', 'entity/creeper/creeper_armor', tint: <double>[0.5, 0.5, 0.5], opacity: _swirl),
        ],
        heightBlocks: 1.7,
      );
    case 'minecraft:dolphin':
      return simple('dolphin#main', 'entity/dolphin/dolphin', 0.6);
    case 'minecraft:donkey':
    case 'minecraft:mule':
      final String name = type.substring(10);
      return McMobBody(
        layers: <McMobLayer>[one('$name#main', 'entity/horse/$name', poses: <String>[if (_flag(settings, 'chest')) 'chest'])],
        heightBlocks: name == 'donkey' ? 1.5 : 1.6,
      );
    case 'minecraft:drowned':
      return McMobBody(
        layers: <McMobLayer>[one('drowned#main', 'entity/zombie/drowned'), one('drowned#outer', 'entity/zombie/drowned_outer_layer')],
        heightBlocks: 1.95,
        armor: 'drowned',
      );
    case 'minecraft:elder_guardian':
      return simple('elder_guardian#main', 'entity/guardian/guardian_elder', 1.9975);
    case 'minecraft:enderman':
      final List<String> angry = flags(<String>['angry']);
      return McMobBody(
        layers: <McMobLayer>[one('enderman#main', 'entity/enderman/enderman', poses: angry), one('enderman#main', 'entity/enderman/enderman_eyes', poses: angry)],
        heightBlocks: 2.9,
      );
    case 'minecraft:endermite':
      return simple('endermite#main', 'entity/endermite/endermite', 0.3);
    case 'minecraft:evoker':
      final bool casting = _choice(settings, 'spell', const <String>['none', 'summon_vex', 'fangs', 'wololo', 'disappear', 'blindness']) != 'none';
      return McMobBody(layers: <McMobLayer>[one('evoker#main', 'entity/illager/evoker', poses: <String>[if (casting) 'spell'])], heightBlocks: 1.95);
    case 'minecraft:fox':
      final bool snow = _choice(settings, 'type', const <String>['red', 'snow']) == 'snow';
      final bool sleeping = _flag(settings, 'sleeping');
      final List<String> foxPoses = flags(<String>['sitting', 'crouching', 'interested', 'pouncing', 'sleeping', 'faceplanted']);
      return McMobBody(
        layers: <McMobLayer>[one('fox#main', 'entity/fox/fox${snow ? '_snow' : ''}${sleeping ? '_sleep' : ''}', poses: foxPoses)],
        heightBlocks: 0.7,
      );
    case 'minecraft:ghast':
      return simple('ghast#main', 'entity/ghast/ghast', 4);
    case 'minecraft:giant':
      return McMobBody(layers: <McMobLayer>[one('giant#main', 'entity/zombie/zombie')], heightBlocks: 12, armor: 'giant');
    case 'minecraft:glow_squid':
      return McMobBody(layers: <McMobLayer>[one('glow_squid#main', 'entity/squid/glow_squid')], heightBlocks: 0.8, frame: _squid);
    case 'minecraft:goat':
      return McMobBody(
        layers: <McMobLayer>[
          one('goat#main', 'entity/goat/goat', poses: <String>[
            if (settings['leftHorn'] == 'hidden') 'leftHornless',
            if (settings['rightHorn'] == 'hidden') 'rightHornless',
          ]),
        ],
        heightBlocks: 1.3,
      );
    case 'minecraft:guardian':
      return simple('guardian#main', 'entity/guardian/guardian', 0.85);
    case 'minecraft:happy_ghast':
      return simple('happy_ghast#main', 'entity/ghast/happy_ghast', 4);
    case 'minecraft:hoglin':
      return simple('hoglin#main', 'entity/hoglin/hoglin', 1.4);
    case 'minecraft:husk':
      return McMobBody(layers: <McMobLayer>[one('husk#main', 'entity/zombie/husk')], heightBlocks: 1.95, armor: 'husk');
    case 'minecraft:illusioner':
      return simple('illusioner#main', 'entity/illager/illusioner', 1.95);
    case 'minecraft:iron_golem':
      return simple('iron_golem#main', 'entity/iron_golem/iron_golem', 2.7);
    case 'minecraft:magma_cube':
      final int size = _number(settings, 'size', 1, 1, 127);
      return McMobBody(layers: <McMobLayer>[one('magma_cube#main', 'entity/slime/magmacube')], heightBlocks: 0.52 * size, scale: size.toDouble());
    case 'minecraft:mooshroom':
      final String variant = _choice(settings, 'variant', const <String>['red', 'brown']);
      final String mushroom = '${variant}_mushroom';
      // `MushroomCowMushroomLayer`: two on the back, one on the head.
      final McMat4 block = McMat4.rotationY(-48).multiply(McMat4.scale(-1, -1, 1)).multiply(McMat4.translation(-0.5, -0.5, -0.5));
      return McMobBody(
        layers: <McMobLayer>[one('mooshroom#main', 'entity/cow/mooshroom_$variant')],
        heightBlocks: 1.4,
        blocks: <McMobBlock>[
          McMobBlock(mushroom, McMat4.translation(0.2, -0.35, 0.5).multiply(block)),
          McMobBlock(mushroom, McMat4.translation(0.2, -0.35, 0.5).multiply(McMat4.rotationY(42)).multiply(McMat4.translation(0.1, 0, -0.6)).multiply(block)),
          McMobBlock(
            mushroom,
            McMat4.translation(0, -0.7, -0.2).multiply(McMat4.rotationY(-78)).multiply(McMat4.scale(-1, -1, 1)).multiply(McMat4.translation(-0.5, -0.5, -0.5)),
            part: 'head',
          ),
        ],
      );
    case 'minecraft:nautilus':
      return simple('nautilus#main', 'entity/nautilus/nautilus', 0.95);
    case 'minecraft:zombie_nautilus':
      return McMobBody(
        layers: <McMobLayer>[one('zombie_nautilus#main', 'entity/nautilus/zombie_nautilus'), one('zombie_nautilus_coral#main', 'entity/nautilus/zombie_nautilus_coral')],
        heightBlocks: 0.95,
      );
    case 'minecraft:ocelot':
      return simple('ocelot#main', 'entity/cat/ocelot', 0.7);
    case 'minecraft:panda':
      final String gene = mcPandaLook(_choice(settings, 'mainGene', mcPandaGenes), _choice(settings, 'hiddenGene', mcPandaGenes));
      final List<String> pandaPoses = flags(<String>['sitting', 'rolling', 'sneezing', 'eating']);
      final bool sits = pandaPoses.contains('sitting') || pandaPoses.contains('eating');
      // `PandaRenderer.setupRotations`: sitting tips the body up on its
      // haunches; rolling is drawn a quarter through its roll.
      McMat4? frame;
      if (pandaPoses.contains('rolling')) frame = McMat4.translation(0, 1, 0).multiply(McMat4.rotationX(-90));
      if (sits) {
        frame = (frame ?? McMat4.identity())
            .multiply(McMat4.translation(0, 0.8, 0))
            .multiply(McMat4.rotationX(90))
            .multiply(McMat4.translation(0, -1, 0));
      }
      return McMobBody(
        layers: <McMobLayer>[one('panda#main', _pandaTexture(gene), poses: pandaPoses)],
        heightBlocks: 1.25,
        frame: frame == null ? null : mcClientFrame(frame),
      );
    case 'minecraft:parched':
      return McMobBody(layers: <McMobLayer>[one('parched#main', 'entity/skeleton/parched')], heightBlocks: 1.99, armor: 'parched');
    case 'minecraft:parrot':
      final String variant = _choice(settings, 'variant', mcParrotVariants);
      return McMobBody(
        layers: <McMobLayer>[one('parrot#main', 'entity/parrot/parrot_${variant == 'gray' ? 'grey' : variant}', poses: flags(<String>['sitting']))],
        heightBlocks: 0.9,
      );
    case 'minecraft:phantom':
      final int size = _number(settings, 'size', 0, 0, 64);
      final double scale = 1 + 0.15 * size;
      return McMobBody(
        layers: <McMobLayer>[one('phantom#main', 'entity/phantom/phantom'), one('phantom#main', 'entity/phantom/phantom_eyes')],
        heightBlocks: 0.5 * scale,
        scale: scale,
        offsetPx: const McVec3(0, 21, 3),
      );
    case 'minecraft:pig':
      final String climate = _choice(settings, 'variant', _climates);
      return simple(climate == 'cold' ? 'cold_pig#main' : 'pig#main', 'entity/pig/pig_$climate', 0.9);
    case 'minecraft:piglin':
      return McMobBody(
        layers: <McMobLayer>[one('piglin#main', 'entity/piglin/piglin', poses: flags(<String>['dancing']))],
        heightBlocks: 1.95,
        armor: 'piglin',
      );
    case 'minecraft:piglin_brute':
      return McMobBody(layers: <McMobLayer>[one('piglin_brute#main', 'entity/piglin/piglin_brute')], heightBlocks: 1.95, armor: 'piglin_brute');
    case 'minecraft:pillager':
      return simple('pillager#main', 'entity/illager/pillager', 1.95);
    case 'minecraft:polar_bear':
      return McMobBody(layers: <McMobLayer>[one('polar_bear#main', 'entity/bear/polarbear', poses: flags(<String>['standing']))], heightBlocks: 1.4);
    case 'minecraft:pufferfish':
      final String puff = _choice(settings, 'puff', const <String>['deflated', 'half', 'full']);
      final String layer = switch (puff) {
        'full' => 'pufferfish_big#main',
        'half' => 'pufferfish_medium#main',
        _ => 'pufferfish_small#main',
      };
      // `PufferfishRenderer.setupRotations` bobs it 0.08 up at tick 0.
      return McMobBody(layers: <McMobLayer>[one(layer, 'entity/fish/pufferfish')], heightBlocks: 0.7, frame: McMat4.translation(0, 0.08, 0));
    case 'minecraft:rabbit':
      return simple('rabbit#main', _rabbitTexture(_choice(settings, 'type', mcRabbitTypes)), 0.6);
    case 'minecraft:ravager':
      return simple('ravager#main', 'entity/illager/ravager', 2.2);
    case 'minecraft:salmon':
      return McMobBody(layers: <McMobLayer>[one('salmon#main', 'entity/fish/salmon')], heightBlocks: 0.4, frame: _landed(0.2, 0.1, 0));
    case 'minecraft:sheep':
      final String color = _choice(settings, 'color', mcDyes);
      final bool sheared = _flag(settings, 'sheared');
      return McMobBody(
        layers: <McMobLayer>[
          one('sheep#main', 'entity/sheep/sheep'),
          if (color != 'white') McMobLayer('sheep#wool_undercoat', 'entity/sheep/sheep_wool_undercoat', tint: mcWoolTint(color)),
          if (!sheared) McMobLayer('sheep#wool', 'entity/sheep/sheep_wool', tint: mcWoolTint(color)),
        ],
        heightBlocks: 1.3,
      );
    case 'minecraft:shulker':
      final String? color = settings['color'] is String && mcDyes.contains(settings['color']) ? settings['color']! as String : null;
      return McMobBody(
        layers: <McMobLayer>[one('shulker#main', color == null ? 'entity/shulker/shulker' : 'entity/shulker/shulker_$color')],
        heightBlocks: 1,
        // `ShulkerRenderer.setupRotations` turns it a further half turn.
        frame: McMat4.rotationY(180),
      );
    case 'minecraft:silverfish':
      return simple('silverfish#main', 'entity/silverfish/silverfish', 0.3);
    case 'minecraft:skeleton':
      return McMobBody(layers: <McMobLayer>[one('skeleton#main', 'entity/skeleton/skeleton')], heightBlocks: 1.99, armor: 'skeleton');
    case 'minecraft:skeleton_horse':
      return simple('skeleton_horse#main', 'entity/horse/horse_skeleton', 1.6);
    case 'minecraft:slime':
      final int size = _number(settings, 'size', 1, 1, 127);
      return McMobBody(
        layers: <McMobLayer>[one('slime#main', 'entity/slime/slime'), one('slime#outer', 'entity/slime/slime')],
        heightBlocks: 0.52 * size,
        scale: size * 0.999,
      );
    case 'minecraft:sniffer':
      final String state = _choice(settings, 'state', const <String>['idling', 'feeling_happy', 'scenting', 'sniffing', 'searching', 'digging', 'rising']);
      return McMobBody(layers: <McMobLayer>[one('sniffer#main', 'entity/sniffer/sniffer', poses: <String>[if (state != 'idling') state])], heightBlocks: 1.75);
    case 'minecraft:snow_golem':
      return McMobBody(
        layers: <McMobLayer>[one('snow_golem#main', 'entity/snow_golem/snow_golem')],
        heightBlocks: 1.9,
        blocks: <McMobBlock>[
          // `SnowGolemHeadLayer`.
          if (settings['pumpkin'] != 'hidden')
            McMobBlock(
              'carved_pumpkin',
              McMat4.translation(0, -0.34375, 0)
                  .multiply(McMat4.rotationY(180))
                  .multiply(McMat4.scale(0.625, -0.625, -0.625))
                  .multiply(McMat4.translation(-0.5, -0.5, -0.5)),
              part: 'head',
            ),
        ],
      );
    case 'minecraft:spider':
      return McMobBody(layers: <McMobLayer>[one('spider#main', 'entity/spider/spider'), one('spider#main', 'entity/spider/spider_eyes')], heightBlocks: 0.9);
    case 'minecraft:squid':
      return McMobBody(layers: <McMobLayer>[one('squid#main', 'entity/squid/squid')], heightBlocks: 0.8, frame: _squid);
    case 'minecraft:stray':
      return McMobBody(
        layers: <McMobLayer>[one('stray#main', 'entity/skeleton/stray'), one('stray#outer', 'entity/skeleton/stray_overlay')],
        heightBlocks: 1.99,
        armor: 'stray',
      );
    case 'minecraft:strider':
      return simple('strider#main', 'entity/strider/strider', 1.7);
    case 'minecraft:sulfur_cube':
      return McMobBody(
        layers: <McMobLayer>[one('sulfur_cube#inner', 'entity/sulfur_cube/sulfur_cube_inner'), one('sulfur_cube#main', 'entity/sulfur_cube/sulfur_cube_outer')],
        heightBlocks: 0.49,
        // `SulfurCubeRenderer.scale` at the synced size of 1: half the
        // model, lowered 0.98 less a pixel.
        scale: 0.999 * 0.5,
        offsetPx: const McVec3(0, (0.98 - 1 / 16) * 16, 0),
      );
    case 'minecraft:tadpole':
      return simple('tadpole#main', 'entity/tadpole/tadpole', 0.3);
    case 'minecraft:trader_llama':
      return McMobBody(
        layers: <McMobLayer>[one('trader_llama#main', 'entity/llama/llama_creamy'), one('llama#decor', 'entity/equipment/llama_body/trader_llama')],
        heightBlocks: 1.87,
      );
    case 'minecraft:tropical_fish':
      final String pattern = _choice(settings, 'pattern', mcTropicalPatterns);
      final int index = mcTropicalPatterns.indexOf(pattern);
      final String shape = index < 6 ? 'small' : 'large';
      final String letter = index < 6 ? 'a' : 'b';
      return McMobBody(
        layers: <McMobLayer>[
          McMobLayer('tropical_fish_$shape#main', 'entity/fish/tropical_$letter', tint: mcDyeTint(_choice(settings, 'baseColor', mcDyes))),
          McMobLayer(
            'tropical_fish_$shape#pattern',
            'entity/fish/tropical_${letter}_pattern_${index % 6 + 1}',
            tint: mcDyeTint(_choice(settings, 'patternColor', mcDyes)),
          ),
        ],
        heightBlocks: 0.4,
        frame: _landed(0.2, 0.1, 0),
      );
    case 'minecraft:vex':
      final bool charging = _flag(settings, 'charging');
      return McMobBody(
        layers: <McMobLayer>[one('vex#main', charging ? 'entity/illager/vex_charging' : 'entity/illager/vex', poses: <String>[if (charging) 'charging'])],
        heightBlocks: 0.8,
      );
    case 'minecraft:villager':
      return McMobBody(
        layers: <McMobLayer>[one('villager#main', 'entity/villager/villager'), ..._clothes('villager', 'villager#main', 'villager_no_hat#main', settings)],
        heightBlocks: 1.95,
      );
    case 'minecraft:vindicator':
      return simple('vindicator#main', 'entity/illager/vindicator', 1.95);
    case 'minecraft:wandering_trader':
      return simple('wandering_trader#main', 'entity/wandering_trader/wandering_trader', 1.95);
    case 'minecraft:warden':
      return McMobBody(
        layers: <McMobLayer>[one('warden#main', 'entity/warden/warden'), one('warden#bioluminescent', 'entity/warden/warden_bioluminescent_layer')],
        heightBlocks: 2.9,
      );
    case 'minecraft:witch':
      return simple('witch#main', 'entity/witch/witch', 1.95);
    case 'minecraft:wither':
      final int ticks = _number(settings, 'invulnerableTicks', 0, 0, 1000);
      // `WitherBossRenderer`: twice the model, less while it charges; the
      // blue skin flashes, shown here as it starts.
      final double scale = 2 - (ticks > 0 ? ticks / 220 * 0.5 : 0);
      return McMobBody(
        layers: <McMobLayer>[
          one('wither#main', ticks > 0 ? 'entity/wither/wither_invulnerable' : 'entity/wither/wither'),
          if (_flag(settings, 'armored')) const McMobLayer('wither#armor', 'entity/wither/wither_armor', tint: <double>[0.5, 0.5, 0.5], opacity: _swirl),
        ],
        heightBlocks: 3.5,
        scale: scale,
      );
    case 'minecraft:wither_skeleton':
      return McMobBody(layers: <McMobLayer>[one('wither_skeleton#main', 'entity/skeleton/wither_skeleton')], heightBlocks: 2.4, armor: 'wither_skeleton');
    case 'minecraft:wolf':
      final String? collar = settings['collar'] is String && mcDyes.contains(settings['collar']) ? settings['collar']! as String : null;
      final bool angry = _flag(settings, 'angry');
      final List<String> wolfPoses = flags(<String>['sitting', 'interested', 'angry']);
      return McMobBody(
        layers: <McMobLayer>[
          one('wolf#main', _wolfTexture(_choice(settings, 'variant', mcWolfVariants), tame: collar != null, angry: angry), poses: wolfPoses),
          if (collar != null) McMobLayer('wolf#main', 'entity/wolf/wolf_collar', poses: wolfPoses, tint: mcDyeTint(collar)),
        ],
        heightBlocks: 0.85,
      );
    case 'minecraft:zoglin':
      return simple('zoglin#main', 'entity/hoglin/zoglin', 1.4);
    case 'minecraft:zombie':
      return McMobBody(layers: <McMobLayer>[one('zombie#main', 'entity/zombie/zombie')], heightBlocks: 1.95, armor: 'zombie');
    case 'minecraft:zombie_horse':
      return simple('zombie_horse#main', 'entity/horse/horse_zombie', 1.6);
    case 'minecraft:zombie_villager':
      return McMobBody(
        layers: <McMobLayer>[
          one('zombie_villager#main', 'entity/zombie_villager/zombie_villager'),
          ..._clothes('zombie_villager', 'zombie_villager#main', 'zombie_villager_no_hat#main', settings),
        ],
        heightBlocks: 1.95,
        armor: 'zombie_villager',
      );
    case 'minecraft:zombified_piglin':
      return McMobBody(layers: <McMobLayer>[one('zombified_piglin#main', 'entity/piglin/zombified_piglin')], heightBlocks: 1.95, armor: 'zombified_piglin');
  }
  return null;
}

/// `CodRenderer` and the other fish out of water lie on their side.
McMat4 _landed(double x, double y, double z) => mcClientFrame(McMat4.translation(x, y, z).multiply(McMat4.rotationZ(90)));

/// `SquidRenderer.setupRotations` out of water, where `Squid.aiStep` tips
/// the body over to -90 degrees about its middle.
final McMat4 _squid = mcClientFrame(McMat4.translation(0, 0.5, 0).multiply(McMat4.rotationX(-90)).multiply(McMat4.translation(0, -1.2, 0)));

/// The saddle layer and texture each saddled mob draws for a `saddle` in
/// its saddle slot.
const Map<String, (String, String)> mcMobSaddles = <String, (String, String)>{
  'minecraft:pig': ('pig#saddle', 'entity/equipment/pig_saddle/saddle'),
  'minecraft:strider': ('strider#saddle', 'entity/equipment/strider_saddle/saddle'),
  'minecraft:donkey': ('donkey#saddle', 'entity/equipment/donkey_saddle/saddle'),
  'minecraft:mule': ('mule#saddle', 'entity/equipment/mule_saddle/saddle'),
  'minecraft:skeleton_horse': ('skeleton_horse#saddle', 'entity/equipment/skeleton_horse_saddle/saddle'),
  'minecraft:zombie_horse': ('zombie_horse#saddle', 'entity/equipment/zombie_horse_saddle/saddle'),
  'minecraft:camel': ('camel#saddle', 'entity/equipment/camel_saddle/saddle'),
  'minecraft:camel_husk': ('camel_husk#saddle', 'entity/equipment/camel_husk_saddle/saddle'),
  'minecraft:nautilus': ('nautilus#saddle', 'entity/equipment/nautilus_saddle/saddle'),
  'minecraft:zombie_nautilus': ('nautilus#saddle', 'entity/equipment/nautilus_saddle/saddle'),
};

/// Every texture a mob body may bind, for the extraction tool.
final List<String> mcMobTextureIds = () {
  final Set<String> ids = <String>{};
  void addBody(String type, Map<String, Object?> settings) {
    for (final McMobLayer layer in mcMobBody(type, settings)?.layers ?? const <McMobLayer>[]) {
      ids.add(layer.texture);
    }
  }

  const Map<String, Map<String, List<Object>>> choices = <String, Map<String, List<Object>>>{
    'minecraft:axolotl': <String, List<Object>>{'variant': mcAxolotlVariants},
    'minecraft:cat': <String, List<Object>>{'variant': mcCatVariants, 'collar': <String>['red']},
    'minecraft:chicken': <String, List<Object>>{'variant': _climates},
    'minecraft:copper_golem': <String, List<Object>>{'weather': mcCopperWeather},
    'minecraft:cow': <String, List<Object>>{'variant': _climates},
    'minecraft:creeper': <String, List<Object>>{'powered': <bool>[true]},
    'minecraft:fox': <String, List<Object>>{'type': <String>['red', 'snow']},
    'minecraft:mooshroom': <String, List<Object>>{'variant': <String>['red', 'brown']},
    'minecraft:panda': <String, List<Object>>{'mainGene': mcPandaGenes},
    'minecraft:parrot': <String, List<Object>>{'variant': mcParrotVariants},
    'minecraft:pig': <String, List<Object>>{'variant': _climates},
    'minecraft:rabbit': <String, List<Object>>{'type': mcRabbitTypes},
    'minecraft:sheep': <String, List<Object>>{'color': <String>['red']},
    'minecraft:shulker': <String, List<Object>>{'color': mcDyes},
    'minecraft:tropical_fish': <String, List<Object>>{'pattern': mcTropicalPatterns},
    'minecraft:vex': <String, List<Object>>{'charging': <bool>[true]},
    'minecraft:villager': <String, List<Object>>{'type': mcVillagerTypes, 'profession': mcVillagerProfessions, 'level': mcVillagerLevels},
    'minecraft:zombie_villager': <String, List<Object>>{'type': mcVillagerTypes, 'profession': mcVillagerProfessions, 'level': mcVillagerLevels},
    'minecraft:wither': <String, List<Object>>{'invulnerableTicks': <int>[100], 'armored': <bool>[true]},
    'minecraft:wolf': <String, List<Object>>{'variant': mcWolfVariants, 'collar': <String>['red'], 'angry': <bool>[true]},
  };
  for (final String type in mcMobBodyTypes) {
    addBody(type, const <String, Object?>{});
    for (final MapEntry<String, List<Object>> setting in (choices[type] ?? const <String, List<Object>>{}).entries) {
      for (final Object value in setting.value) {
        addBody(type, <String, Object?>{setting.key: value});
      }
    }
  }
  // Every level badge and profession together with a tame wolf of each coat.
  for (final String level in mcVillagerLevels) {
    addBody('minecraft:villager', <String, Object?>{'profession': 'farmer', 'level': level});
    addBody('minecraft:zombie_villager', <String, Object?>{'profession': 'farmer', 'level': level});
  }
  for (final String variant in mcWolfVariants) {
    addBody('minecraft:wolf', <String, Object?>{'variant': variant, 'collar': 'red'});
    addBody('minecraft:wolf', <String, Object?>{'variant': variant, 'angry': true});
  }
  for (final String type in <String>['red', 'snow']) {
    addBody('minecraft:fox', <String, Object?>{'type': type, 'sleeping': true});
  }
  for (final String pattern in mcTropicalPatterns) {
    addBody('minecraft:tropical_fish', <String, Object?>{'pattern': pattern});
  }
  for (final bool angry in <bool>[false, true]) {
    for (final bool nectar in <bool>[false, true]) {
      addBody('minecraft:bee', <String, Object?>{'angry': angry, 'nectar': nectar});
    }
  }
  for (final (String, String) saddle in mcMobSaddles.values) {
    ids.add(saddle.$2);
  }
  return ids.toList()..sort();
}();

/// The mob bodies [mcMobBody] draws.
const List<String> mcMobBodyTypes = <String>[
  'minecraft:allay', 'minecraft:axolotl', 'minecraft:bat', 'minecraft:bee', 'minecraft:blaze', 'minecraft:bogged', 'minecraft:breeze', //
  'minecraft:camel', 'minecraft:camel_husk', 'minecraft:cat', 'minecraft:cave_spider', 'minecraft:chicken', 'minecraft:cod', //
  'minecraft:copper_golem', 'minecraft:cow', 'minecraft:creaking', 'minecraft:creeper', 'minecraft:dolphin', 'minecraft:donkey', //
  'minecraft:drowned', 'minecraft:elder_guardian', 'minecraft:enderman', 'minecraft:endermite', 'minecraft:evoker', 'minecraft:fox', //
  'minecraft:ghast', 'minecraft:giant', 'minecraft:glow_squid', 'minecraft:goat', 'minecraft:guardian', 'minecraft:happy_ghast', //
  'minecraft:hoglin', 'minecraft:husk', 'minecraft:illusioner', 'minecraft:iron_golem', 'minecraft:magma_cube', 'minecraft:mooshroom', //
  'minecraft:mule', 'minecraft:nautilus', 'minecraft:ocelot', 'minecraft:panda', 'minecraft:parched', 'minecraft:parrot', //
  'minecraft:phantom', 'minecraft:pig', 'minecraft:piglin', 'minecraft:piglin_brute', 'minecraft:pillager', 'minecraft:polar_bear', //
  'minecraft:pufferfish', 'minecraft:rabbit', 'minecraft:ravager', 'minecraft:salmon', 'minecraft:sheep', 'minecraft:shulker', //
  'minecraft:silverfish', 'minecraft:skeleton', 'minecraft:skeleton_horse', 'minecraft:slime', 'minecraft:sniffer', 'minecraft:snow_golem', //
  'minecraft:spider', 'minecraft:squid', 'minecraft:stray', 'minecraft:strider', 'minecraft:sulfur_cube', 'minecraft:tadpole', //
  'minecraft:trader_llama', 'minecraft:tropical_fish', 'minecraft:vex', 'minecraft:villager', 'minecraft:vindicator', //
  'minecraft:wandering_trader', 'minecraft:warden', 'minecraft:witch', 'minecraft:wither', 'minecraft:wither_skeleton', 'minecraft:wolf', //
  'minecraft:zoglin', 'minecraft:zombie', 'minecraft:zombie_horse', 'minecraft:zombie_nautilus', 'minecraft:zombie_villager', //
  'minecraft:zombified_piglin',
];

const List<String> _nautilusArmor = <String>['iron', 'golden', 'diamond', 'netherite', 'copper'];

/// What a mob wears in its body slot: the layer, texture and tint for
/// [itemKey], or null when that mob shows nothing for it. Undyed wolf armour
/// shows no overlay; a dyed one is drawn in its dye.
(String, String, List<double>)? mcMobBodyArmor(String type, String itemKey, int? dyedColor) {
  if (type == 'minecraft:happy_ghast' && itemKey.endsWith('_harness')) {
    final String color = itemKey.substring(0, itemKey.length - 8);
    if (mcDyes.contains(color)) return ('happy_ghast_harness#main', 'entity/equipment/happy_ghast_body/${color}_harness', _white);
  }
  if (type == 'minecraft:wolf' && itemKey == 'wolf_armor') {
    return dyedColor == null
        ? ('wolf_armor#main', 'entity/equipment/wolf_body/armadillo_scute', _white)
        : ('wolf_armor#main', 'entity/equipment/wolf_body/armadillo_scute_overlay', mcRgb(dyedColor));
  }
  if ((type == 'minecraft:nautilus' || type == 'minecraft:zombie_nautilus') && itemKey.endsWith('_nautilus_armor')) {
    final String material = itemKey.substring(0, itemKey.length - 15);
    if (_nautilusArmor.contains(material)) {
      return ('nautilus_armor#main', 'entity/equipment/nautilus_body/${material == 'golden' ? 'gold' : material}', _white);
    }
  }
  return null;
}

/// Every texture [mcMobBodyArmor] binds.
final List<String> mcMobEquipmentTextureIds = <String>[
  for (final String dye in mcDyes) 'entity/equipment/happy_ghast_body/${dye}_harness',
  'entity/equipment/wolf_body/armadillo_scute',
  'entity/equipment/wolf_body/armadillo_scute_overlay',
  for (final String material in _nautilusArmor) 'entity/equipment/nautilus_body/${material == 'golden' ? 'gold' : material}',
];
