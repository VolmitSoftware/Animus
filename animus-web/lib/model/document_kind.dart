/// Document kinds, matching `kind` in schema/editor-v1.schema.json and the
/// Java `DocumentKind` enum.
enum DocumentKind {
  npc('npc', 'NPCs', 'npcs'),
  route('route', 'Routes', 'routes'),
  recording('recording', 'Recordings', 'recordings'),
  skin('skin', 'Skins', 'skins'),
  group('group', 'Groups', 'groups'),
  behavior('behavior', 'Behaviors', 'behaviors'),
  pack('pack', 'Packs', 'packs'),
  shop('shop', 'Shops', 'shops'),
  quest('quest', 'Quests', 'quests'),
  inventory('inventory', 'Chest GUIs', 'gui/inventories'),
  menu('menu', 'Menus', 'gui/menus'),
  hologram('hologram', 'Holograms', 'gui/holograms'),
  textAnimation('text-animation', 'Text animations', 'gui/text-animations'),
  presets('presets', 'Presets', 'gui'),
  rig('rig', 'Rigs', 'rigs'),
  animation('animation', 'Animations', 'animations'),

  /// The single `server-behaviors.json` at the data folder root: the server
  /// behaviors that run. Its folder is empty.
  serverBehaviors('server-behaviors', 'Server behaviors', '');

  const DocumentKind(this.wireName, this.label, this.folder);

  final String wireName;
  final String label;
  final String folder;

  /// The id of the one server behaviors list.
  static const String serverBehaviorsId = 'server-behaviors';

  /// Where the server behaviors list lives.
  static const String serverBehaviorsPath = 'server-behaviors.json';

  static DocumentKind? parse(String wireName) {
    for (final DocumentKind kind in values) {
      if (kind.wireName == wireName) return kind;
    }
    return null;
  }
}
