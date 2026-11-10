/// Wiki pages the editor's help tips link to. Paths and anchors follow
/// the wiki at docs.volmitsoftware.com/animus (Wiki.js heading slugs).
library;

import '../model/document_kind.dart';

/// One wiki page, optionally at a heading.
class DocLink {
  const DocLink(this.page, [this.anchor]);

  /// The page path under `/animus/`, such as `04-npc-definitions`.
  final String page;

  /// The heading slug, without `#`.
  final String? anchor;

  String get url => 'https://docs.volmitsoftware.com/animus/$page${anchor == null ? '' : '#$anchor'}';
}

/// The wiki headings the editor points at.
abstract final class Docs {
  static const DocLink overview = DocLink('00-overview', 'core-concepts');
  static const DocLink fileLayout = DocLink('01-installation-compatibility', 'file-layout');
  static const DocLink commands = DocLink('02-commands-permissions');
  static const DocLink permissions = DocLink('02-commands-permissions', 'permissions');
  static const DocLink editorCommands = DocLink('02-commands-permissions', 'web-editor');
  static const DocLink editorConfig = DocLink('03-configuration', 'editor');

  static const DocLink npc = DocLink('04-npc-definitions', 'definition-file');
  static const DocLink npcActivation = DocLink('04-npc-definitions', 'activation-and-editing');
  static const DocLink npcAppearance = DocLink('04-npc-definitions', 'appearance-text-and-inventory');
  static const DocLink npcText = DocLink('04-npc-definitions', 'names-and-text-lines');
  static const DocLink npcFlags = DocLink('04-npc-definitions', 'other-presentation-flags');
  static const DocLink npcPoses = DocLink('04-npc-definitions', 'poses');
  static const DocLink npcInventory = DocLink('04-npc-definitions', 'inventory-and-owner');
  static const DocLink npcInteractions = DocLink('04-npc-definitions', 'interactions');
  static const DocLink npcLimits = DocLink('04-npc-definitions', 'work-and-visibility-limits');

  static const DocLink movement = DocLink('05-movement-mounts-riding', 'movement-settings');
  static const DocLink routes = DocLink('05-movement-mounts-riding', 'routes');
  static const DocLink lookClose = DocLink('05-movement-mounts-riding', 'looking-at-nearby-players');
  static const DocLink mounts = DocLink('05-movement-mounts-riding', 'mounted-npcs');
  static const DocLink riding = DocLink('05-movement-mounts-riding', 'riding-an-npc');

  static const DocLink rigs = DocLink('06-rigs-animations', 'rig-documents');
  static const DocLink animations = DocLink('06-rigs-animations', 'animation-documents');
  static const DocLink animationEvents = DocLink('06-rigs-animations', 'animation-events');
  static const DocLink blockbench = DocLink('06-rigs-animations', 'blockbench-models-on-the-server');

  static const DocLink behaviors = DocLink('07-behaviors', 'documents');
  static const DocLink behaviorNodes = DocLink('07-behaviors', 'nodes');
  static const DocLink behaviorVariables = DocLink('07-behaviors', 'variables');
  static const DocLink behaviorOperands = DocLink('07-behaviors', 'operands');
  static const DocLink behaviorExpressions = DocLink('07-behaviors', 'expressions');
  static const DocLink behaviorTriggers = DocLink('07-behaviors', 'automatic-triggers');
  static const DocLink behaviorCapabilities = DocLink('07-behaviors', 'built-in-capabilities');
  static const DocLink serverBehaviors = DocLink('07-behaviors', 'server-behaviors');
  static const DocLink packs = DocLink('07-behaviors', 'packs');
  static const DocLink dryRun = DocLink('07-behaviors', 'checking-behaviors-offline');

  static const DocLink combat = DocLink('08-combat', 'settings');
  static const DocLink combatTargets = DocLink('08-combat', 'target-rules');

  static const DocLink shops = DocLink('10-shops', 'shop-fields');
  static const DocLink quests = DocLink('38-quests', 'quest-fields');
  static const DocLink shopEntries = DocLink('10-shops', 'entry-fields');
  static const DocLink containerShops = DocLink('10-shops', 'container-shops');
  static const DocLink dtlTraders = DocLink('10-shops', 'importing-from-dtltraders');

  static const DocLink chestGuis = DocLink('11-guis-holograms-menus', 'chest-gui-fields');
  static const DocLink guiActions = DocLink('11-guis-holograms-menus', 'actions');
  static const DocLink guiExpressions = DocLink('11-guis-holograms-menus', 'expressions');
  static const DocLink holograms = DocLink('11-guis-holograms-menus', 'holograms');
  static const DocLink menus = DocLink('11-guis-holograms-menus', 'holographic-menus');
  static const DocLink textAnimations = DocLink('11-guis-holograms-menus', 'text-animations');
  static const DocLink gloss = DocLink('11-guis-holograms-menus', 'with-and-without-gloss');

  static const DocLink storage = DocLink('12-storage-recovery', 'definitions');
  static const DocLink rollback = DocLink('12-storage-recovery', 'rollback');
  static const DocLink schemaVersions = DocLink('12-storage-recovery', 'schema-versions');

  static const DocLink citizens = DocLink('13-migrating-from-citizens', 'converting-in-the-browser');
  static const DocLink citizensOutput = DocLink('13-migrating-from-citizens', 'where-the-files-go');
  static const DocLink shopkeepers = DocLink('13-migrating-from-citizens', 'shopkeepers');
  static const DocLink denizen = DocLink('13-migrating-from-citizens', 'denizen');

  static const DocLink editor = DocLink('14-web-editor', 'working-without-a-server');
  static const DocLink editorDocuments = DocLink('14-web-editor', 'npcs-trait-groups-routes-recordings-skins-and-packs');
  static const DocLink editorRigs = DocLink('14-web-editor', 'rig-and-animation-editor');
  static const DocLink editorGuis = DocLink('14-web-editor', 'gui-editors');
  static const DocLink editorKeyboard = DocLink('14-web-editor', 'keyboard-and-commands');
  static const DocLink editorTabs = DocLink('14-web-editor', 'more-than-one-tab');
  static const DocLink sending = DocLink('14-web-editor', 'sending-a-workspace-to-a-server');
  static const DocLink pairing = DocLink('14-web-editor', 'pairing');
  static const DocLink tokens = DocLink('14-web-editor', 'managing-tokens');
  static const DocLink connecting = DocLink('14-web-editor', 'connecting-the-server');
  static const DocLink live = DocLink('14-web-editor', 'editing-a-live-server');
  static const DocLink liveApply = DocLink('14-web-editor', 'applying-changes');
  static const DocLink livePreviews = DocLink('14-web-editor', 'previews');
  static const DocLink liveImports = DocLink('14-web-editor', 'importing');

  static const DocLink groups = DocLink('18-trait-groups', 'group-file');
  static const DocLink groupList = DocLink('18-trait-groups', 'listing-groups-on-an-npc');
  static const DocLink groupMerge = DocLink('18-trait-groups', 'merge-rules');

  static const DocLink recordings = DocLink('19-performance-recordings', 'the-recording-file');
  static const DocLink recordingPlayback = DocLink('19-performance-recordings', 'playing-a-recording-on-an-npc');

  /// The page that covers a document kind's fields.
  static DocLink forKind(DocumentKind kind) => switch (kind) {
        DocumentKind.npc => npc,
        DocumentKind.route => routes,
        DocumentKind.recording => recordings,
        DocumentKind.skin => npcAppearance,
        DocumentKind.group => groups,
        DocumentKind.behavior => behaviors,
        DocumentKind.pack => packs,
        DocumentKind.shop => shops,
        DocumentKind.quest => quests,
        DocumentKind.inventory => chestGuis,
        DocumentKind.menu => menus,
        DocumentKind.hologram => holograms,
        DocumentKind.textAnimation || DocumentKind.presets => textAnimations,
        DocumentKind.rig => rigs,
        DocumentKind.animation => animations,
        DocumentKind.serverBehaviors => serverBehaviors,
      };
}
