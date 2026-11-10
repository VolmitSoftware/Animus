import '../../model/document_kind.dart';

/// What documents of [kind] are for, in one sentence: the kind list's
/// heading tip and the second half of its empty state.
String kindListPurpose(DocumentKind kind) => switch (kind) {
      DocumentKind.npc => 'NPCs are the characters the server spawns, each with its look, place and behaviors.',
      DocumentKind.route => 'Routes are paths of points that NPCs walk or patrol.',
      DocumentKind.recording => 'Recordings are movement captured in game and played back on an NPC.',
      DocumentKind.skin => 'Skins are player textures that NPCs wear.',
      DocumentKind.group => 'Groups hold settings that many NPCs share.',
      DocumentKind.behavior => 'Behaviors are node graphs that tell NPCs what to do and when.',
      DocumentKind.pack => 'Packs bundle behaviors to install or share together.',
      DocumentKind.shop => 'Shops are trade offers an NPC opens for players.',
      DocumentKind.quest => 'Quests are stages of objectives an NPC offers, with rewards at the end.',
      DocumentKind.inventory => 'Chest GUIs are clickable chest menus for players.',
      DocumentKind.menu => 'Menus are clickable holographic menus placed in the world.',
      DocumentKind.hologram => 'Holograms are floating lines of text and items in the world.',
      DocumentKind.textAnimation => 'Text animations are animated lines for names, holograms and GUIs.',
      DocumentKind.presets => 'Presets are styles and items that GUIs reuse.',
      DocumentKind.rig => 'Rigs are custom models built from bones and display parts.',
      DocumentKind.animation => 'Animations are keyframed motion played on a rig.',
      DocumentKind.serverBehaviors => 'The server behaviors list names the behaviors that run server-wide.',
    };

/// How a document of [kind] gets made when the list has none: recordings
/// come from the game, everything else from New.
String? kindMadeElsewhere(DocumentKind kind) => switch (kind) {
      DocumentKind.recording => 'Run /animus record start <id> in game to make one.',
      _ => null,
    };
