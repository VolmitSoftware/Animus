/// The Talk and trade tab: the behaviors the NPC runs (conversations
/// included, with New conversation) and the shop a click opens. Advanced
/// adds the stored inventory behaviors read.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../config/docs_links.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/npc/npc_tabs.dart';
import '../../logic/npc/reference_options.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../../state/workspace_route.dart';
import '../../ui/advanced_in_use.dart';
import '../../ui/action_row.dart';
import '../../ui/field.dart';
import '../../ui/section.dart';
import '../../ui/toggle_choice.dart';
import '../gui/gui_fields.dart';
import '../select/picker_scope.dart';
import 'behavior_list.dart';
import 'inventory_slots.dart';
import 'npc_form.dart';
import 'npc_parts.dart';
import 'npc_layout.dart';
import '../select/animus_select.dart';

class NpcTalkTab extends StatelessWidget {
  const NpcTalkTab({required this.form, required this.extras, required this.onReveal, this.inUse = 0, super.key});

  final NpcForm form;
  final bool extras;
  final void Function() onReveal;

  /// The settings Simple counts on this tab's line.
  final int inUse;

  @override
  Widget build(BuildContext context) {
    final NpcForm form = this.form;
    final JsonMap doc = form.doc;
    final PickerScope? scope = PickerScope.of(context);
    return tabBody(<Widget>[
      NpcBehaviorList(
        listed: stringList(doc['behaviors']),
        documents: form.workspaceDocuments,
        serverBehaviors: form.serverBehaviors,
        diagnostics: form.diagnostics,
        onOpen: form.onOpen,
        onNewConversation: scope == null ? null : () => scope.onNavigate(NewDocumentRoute.conversation(scope.workspaceId)),
        onChanged: (List<String> next) => form.write(const <JsonStep>['behaviors'], next.isEmpty ? null : next),
      ),
      ShopBindingEditor(
        value: form.modules[shopModuleKey],
        shops: shopPickerOptions(form.workspaceDocuments),
        diagnostics: form.diagnostics,
        onOpen: form.onOpen,
        onChanged: (Object? value) => form.putModule(shopModuleKey, value),
      ),
      if (!extras) AdvancedInUse(count: inUse, onShow: onReveal),
      if (extras && !form.group)
        AppSection(
          title: 'Inventory',
          open: doc.containsKey('inventory'),
          tip: 'Items the NPC keeps for behaviors and modules to read. Nothing in game shows it.',
          docs: Docs.npcInventory,
          children: <Widget>[
            NpcInventorySlots(value: doc['inventory'], diagnostics: form.diagnostics, onChanged: (Object? value, {Object? from}) => form.emit(const <JsonStep>['inventory'], value, coalesce: 'npc:inventory', from: from)),
          ],
        ),
    ]);
  }
}

/// `animus:shop`: the shop a click on the NPC opens, and which click.
class ShopBindingEditor extends StatelessWidget {
  const ShopBindingEditor({required this.value, required this.shops, required this.onChanged, this.diagnostics = const <Diagnostic>[], this.onOpen, super.key});

  /// The module object, or null.
  final Object? value;

  /// Shop ids of the workspace.
  final List<String> shops;

  /// The whole new module, null to remove it.
  final void Function(Object? value) onChanged;
  final List<Diagnostic> diagnostics;
  final void Function(DocumentKind kind, String id)? onOpen;

  /// The shop select's placeholder, which also names it for New shop to
  /// come back to.
  static const String _none = 'No shop';

  @override
  Widget build(BuildContext context) {
    const String shopId = 'animus-npc-shop';
    final JsonMap module = asMap(value);
    final String path = childPath(r'$.modules', shopModuleKey);
    final String? shop = asString(module['shop']);
    final PickerScope? scope = PickerScope.of(context);
    final void Function(DocumentKind, String)? open = onOpen;
    return dom.div(classes: 'animus-npc-shop', attributes: const <String, String>{'data-npc': 'shop'}, <Widget>[
      Field(
        label: 'Shop',
        forId: shopId,
        tip: 'A trade window that opens when a player clicks the NPC.',
        docs: Docs.shops,
        problems: <Diagnostic>[
          ...diagnosticsAt(diagnostics, '$path.shop'),
          if (shop != null && shop.isNotEmpty && !shops.contains(shop)) Diagnostic(DiagnosticSeverity.warning, '$path.shop', 'Not a shop in this workspace; the server may have it.'),
        ],
        child: ActionRow(
          label: 'Shop',
          leading: <Widget>[
            NpcPick(child: AnimusSelect.values(
              references: DocumentKind.shop,
              id: shopId,
              value: shop == null || shop.isEmpty ? null : shop,
              values: <String>[...shops, if (shop != null && shop.isNotEmpty && !shops.contains(shop)) shop],
              allowNone: true,
              noneLabel: _none,
              onChanged: (String? id) => onChanged(id == null ? null : <String, Object?>{...module, 'shop': id}),
            )),
          ],
          // One icon button, so the select ends where the behaviors' do.
          secondary: <RowAction>[
            if (shop != null && shop.isNotEmpty)
              RowAction('Edit shop', shops.contains(shop) && open != null ? () => open(DocumentKind.shop, shop) : null, icon: ArcaneIcon.pencil(), iconOnly: true)
            else if (scope != null)
              RowAction('New shop', () => scope.run(newActionId, DocumentKind.shop, _none, ''), icon: ArcaneIcon.plus(), iconOnly: true),
          ],
        ),
      ),
      if (value != null)
        Field(
          label: 'Opens on',
          tip: 'Which click opens the shop. Sneaking admins select the NPC instead.',
          problems: diagnosticsAt(diagnostics, '$path.openOn'),
          // Three choices side by side; Right click is the default, written
          // as no key. Another value stays until a choice replaces it.
          child: ToggleChoice<String>(
            label: 'Opens on',
            options: const <ChoiceOption<String>>[
              ChoiceOption<String>('right', 'Right click'),
              ChoiceOption<String>('left', 'Left click'),
              ChoiceOption<String>('both', 'Either'),
            ],
            value: asString(module['openOn']) ?? 'right',
            onChanged: (String click) {
              final JsonMap next = <String, Object?>{...module};
              if (click == 'right') {
                next.remove('openOn');
              } else {
                next['openOn'] = click;
              }
              onChanged(next);
            },
          ),
        ),
    ]);
  }
}
