/// The NPC form, in tabs: Basics, Look, Movement, Talk and trade and Combat;
/// Advanced adds Attributes, Modules and Integrations, and more fields
/// inside the first tabs. A trait group gets the same tabs over its
/// `values`, with Members in place of Basics. Only the open tab is built,
/// so typing in one field does not rebuild the rest of the form. Edits
/// write JSON paths, so fields a tab does not show (and unknown keys)
/// survive untouched.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../logic/gui/gui_json.dart';
import '../../logic/inspect/inspect_schemas.dart';
import '../../logic/npc/combat_settings.dart';
import '../../logic/npc/npc_attributes.dart';
import '../../logic/npc/npc_catalog.dart';
import '../../logic/npc/npc_tabs.dart';
import '../../logic/npc/skin_edits.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../../ui/mode.dart';
import '../../ui/tabs.dart';
import '../conversation/conversation_attach.dart' show ConversationCommit;
import '../gui/gui_fields.dart' show JsonEdit, stringList;
import '../npc/attributes_editor.dart';
import '../npc/combat_tab.dart';
import '../npc/npc_basics.dart';
import '../npc/npc_form.dart';
import '../npc/npc_layout.dart';
import '../npc/npc_look.dart';
import '../npc/npc_members.dart';
import '../npc/npc_modules_tab.dart';
import '../npc/npc_movement.dart';
import '../npc/npc_talk.dart';

class NpcInspector extends StatefulWidget {
  const NpcInspector({
    required this.document,
    required this.onJson,
    required this.schemas,
    this.diagnostics = const <Diagnostic>[],
    this.workspaceDocuments = const <AnimusDocument>[],
    this.onSkinLoaded,
    this.onOpen,
    this.onCommit,
    this.serverBehaviors = const <String>[],
    this.serverPlugins = const <String>{},
    this.skinEditing,
    this.tab,
    super.key,
  }) : forGroup = false;

  /// The form over a trait group's `values`.
  const NpcInspector.forGroup({
    required this.document,
    required this.onJson,
    required this.schemas,
    this.diagnostics = const <Diagnostic>[],
    this.workspaceDocuments = const <AnimusDocument>[],
    this.onSkinLoaded,
    this.onOpen,
    this.onCommit,
    this.serverBehaviors = const <String>[],
    this.serverPlugins = const <String>{},
    this.skinEditing,
    super.key,
  })  : forGroup = true,
        tab = null;

  final AnimusDocument document;

  /// Whether [document] is a trait group and the tabs edit its `values`.
  final bool forGroup;
  final JsonEdit onJson;
  final InspectSchemas schemas;

  /// The browser's and the server's diagnostics together.
  final List<Diagnostic> diagnostics;

  /// Every document of the workspace: behaviors, skins, routes, shops, rigs
  /// and the other NPCs are offered from here.
  final List<AnimusDocument> workspaceDocuments;

  /// A skin PNG was loaded or a built-in skin worn; the preview redraws.
  final void Function()? onSkinLoaded;

  /// Opens another document of the workspace.
  final void Function(DocumentKind kind, String id)? onOpen;

  /// Writes several documents as one undoable change; null hides those
  /// actions.
  final ConversationCommit? onCommit;

  /// Behavior ids in the connected server's catalog.
  final List<String> serverBehaviors;

  /// The plugin integrations the connected server reports running.
  final Set<String> serverPlugins;

  /// Skin edits that write several documents; null leaves those out.
  final SkinEditing? skinEditing;

  /// The tab to open on, from the page's address; null keeps the last one.
  final NpcTab? tab;

  @override
  State<NpcInspector> createState() => _NpcInspectorState();
}

class _NpcInspectorState extends State<NpcInspector> {
  /// The tab last opened, kept for the next NPC (or group) opened.
  static NpcTab _lastNpc = NpcTab.basics;
  static NpcTab _lastGroup = NpcTab.members;

  late NpcTab _tab = component.tab ?? (component.forGroup ? _lastGroup : _lastNpc);

  /// Tabs whose Advanced settings Simple was asked to show here.
  final Set<NpcTab> _revealed = <NpcTab>{};

  void _select(NpcTab tab) {
    if (component.forGroup) {
      _lastGroup = tab;
    } else {
      _lastNpc = tab;
    }
    setState(() => _tab = tab);
  }

  @override
  void didUpdateWidget(NpcInspector oldComponent) {
    super.didUpdateWidget(oldComponent);
    final NpcTab? asked = component.tab;
    if (asked != null && asked != oldComponent.tab) _tab = asked;
  }

  void _reveal(Iterable<NpcTab> tabs) => setState(() => _revealed.addAll(tabs));

  NpcForm _form() => NpcForm(
        document: component.document,
        group: component.forGroup,
        schemas: component.schemas,
        diagnostics: component.diagnostics,
        workspaceDocuments: component.workspaceDocuments,
        onJson: component.onJson,
        onOpen: component.onOpen,
        onCommit: component.onCommit,
        serverBehaviors: component.serverBehaviors,
        serverPlugins: component.serverPlugins,
        skinEditing: component.skinEditing,
        onSkinLoaded: component.onSkinLoaded,
      );

  @override
  Widget build(BuildContext context) {
    final bool advanced = ModeScope.advanced(context);
    final bool group = component.forGroup;
    final NpcForm form = _form();
    final List<NpcTab> tabs = npcTabs(group: group, advanced: advanced, revealed: _revealed);
    final NpcTab tab = tabs.contains(_tab) ? _tab : tabs.first;
    // Simple counts the settings of the Advanced-only tabs on its first tab,
    // whose Show opens them; every other tab counts its own.
    final List<NpcTab> hidden = advanced
        ? const <NpcTab>[]
        : <NpcTab>[
            for (final NpcTab each in hiddenTabsInUse(form.doc, group: group, laidOut: npcLaidOut))
              if (!tabs.contains(each)) each,
          ];
    final bool extras = advanced || _revealed.contains(tab);
    final bool first = tab == tabs.first;
    final int inUse = extras ? 0 : simpleInUse(tab, form.doc, group: group, shown: tabs);
    return dom.section(
      classes: 'animus-npc-form',
      styles: stackStyles(gap: Gap.md),
      attributes: <String, String>{'aria-label': group ? 'Trait group' : 'NPC', 'data-npc': group ? 'group-form' : 'form'},
      <Widget>[
        dom.div(classes: 'animus-npc-tabs', <Widget>[
          AppTabs(
            id: 'animus-npc-tab-row',
            label: group ? 'Trait group sections' : 'NPC sections',
            tabs: <String>[for (final NpcTab each in tabs) each.label],
            selected: tabs.indexOf(tab),
            onSelect: (int index) => _select(tabs[index]),
          ),
        ]),
        dom.div(
          key: ValueKey<String>('npc-tab:${tab.slug}'),
          classes: 'animus-npc-panel',
          attributes: <String, String>{'role': 'tabpanel', 'aria-label': tab.label, 'data-npc-tab': tab.slug},
          <Widget>[_panel(tab, form, extras, inUse, first ? hidden : const <NpcTab>[])],
        ),
      ],
    );
  }

  /// Tab [tab]'s panel; [inUse] is the count on its line, whose Show opens
  /// its own Advanced settings and the tabs in [alsoReveal].
  Widget _panel(NpcTab tab, NpcForm form, bool extras, int inUse, List<NpcTab> alsoReveal) {
    void reveal() => _reveal(<NpcTab>[tab, ...alsoReveal]);
    return switch (tab) {
      NpcTab.basics => NpcBasicsTab(form: form, extras: extras, onReveal: reveal, inUse: inUse),
      NpcTab.members => NpcMembersTab(form: form, extras: extras, onReveal: reveal, inUse: inUse),
      NpcTab.look => NpcLookTab(form: form, extras: extras, onReveal: reveal, inUse: inUse),
      NpcTab.movement => NpcMovementTab(form: form, extras: extras, onReveal: reveal, inUse: inUse),
      NpcTab.talk => NpcTalkTab(form: form, extras: extras, onReveal: reveal, inUse: inUse),
      NpcTab.combat => _combat(form),
      NpcTab.attributes => tabBody(<Widget>[
          NpcAttributesNote(group: form.group),
          AttributesEditor(
            value: form.doc[attributesField],
            specs: attributeSpecs(form.schema),
            bodyType: form.group ? null : form.type,
            modules: form.modules,
            diagnostics: form.diagnostics,
            onChanged: (JsonMap? attributes) => form.write(const <JsonStep>[attributesField], attributes),
          ),
        ]),
      NpcTab.modules => NpcModulesTab(form: form, integrations: false),
      NpcTab.integrations => NpcModulesTab(form: form, integrations: true),
    };
  }

  Widget _combat(NpcForm form) {
    final List<NpcEntry> npcs = npcEntries(form.workspaceDocuments);
    final Set<String> squads = <String>{
      for (final NpcEntry entry in npcs)
        if (asString(asMap(asMap(entry.modules[combatModule])['squad'])['name']) case final String name) name,
      for (final NpcEntry entry in npcs) ...stringList(asMap(asMap(entry.modules[combatModule])['targets'])['squads']),
    };
    return CombatTab(
      modules: form.modules,
      onModule: (String key, Object? value) => form.putModule(key, value),
      schema: form.schema,
      diagnostics: form.diagnostics,
      npcIds: <String>[
        for (final NpcEntry entry in npcs)
          if (entry.key != form.document.id) entry.localId,
      ],
      squads: squads.toList()..sort(),
      origin: form.origin,
      group: form.group,
      attributes: form.doc[attributesField],
    );
  }
}
