/// Document kinds, matching `kind` in schema/editor-v1.schema.json and the
/// Java `DocumentKind` enum.
enum DocumentKind {
  npc('npc', 'NPCs', 'npcs'),
  route('route', 'Routes', 'routes'),
  skin('skin', 'Skins', 'skins'),
  group('group', 'Groups', 'groups'),
  behavior('behavior', 'Behaviors', 'behaviors'),
  pack('pack', 'Packs', 'packs'),
  shop('shop', 'Shops', 'shops'),
  inventory('inventory', 'Chest GUIs', 'gui/inventories'),
  menu('menu', 'Menus', 'gui/menus'),
  hologram('hologram', 'Holograms', 'gui/holograms'),
  textAnimation('text-animation', 'Text animations', 'gui/text-animations'),
  presets('presets', 'Presets', 'gui'),
  rig('rig', 'Rigs', 'rigs'),
  animation('animation', 'Animations', 'animations');

  const DocumentKind(this.wireName, this.label, this.folder);

  final String wireName;
  final String label;
  final String folder;

  static DocumentKind? parse(String wireName) {
    for (final DocumentKind kind in values) {
      if (kind.wireName == wireName) return kind;
    }
    return null;
  }
}
