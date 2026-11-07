/// The shop editor: the offers board on one side, and on the other both
/// windows the shop can open in, the shop's own settings and the NPCs that
/// open it. Offers read as trades and open in place to edit; the previews
/// follow every edit and pick offers too. Problems show at the offer they
/// belong to: schema and server rules, the notes for what a window style
/// cannot show exactly, and offer checks such as limits that conflict.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/gui/gui_json.dart';
import '../../logic/gui/shop_board.dart';
import '../../logic/gui/shop_checks.dart';
import '../../logic/gui/shop_entries.dart';
import '../../logic/gui/shop_layout.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../conversation/conversation_attach.dart' show ConversationCommit;
import '../info_tip.dart';
import '../mc/item_picker.dart';
import '../menu_button.dart';
import 'gui_fields.dart';
import 'offer_board.dart';
import 'shop_links.dart';
import 'shop_offer_editor.dart';
import 'shop_previews.dart';
import 'shop_settings.dart';

/// The workspace's chest GUIs by id.
Map<String, JsonMap> workspaceLayouts(Iterable<AnimusDocument> documents) => <String, JsonMap>{
      for (final AnimusDocument document in documents)
        if (document.kind == DocumentKind.inventory && document.json is Map<String, Object?>) document.id: asMap(document.json),
    };

/// Every diagnostic the shop editor shows: the document's own, then the
/// offer checks and the notes for each style the shop opens in.
List<Diagnostic> shopEditorDiagnostics(AnimusDocument document, {List<AnimusDocument> workspace = const <AnimusDocument>[], bool Function(String id)? knownItem}) {
  final JsonMap shop = asMap(document.json);
  final Map<String, JsonMap> layouts = workspaceLayouts(workspace);
  final Set<String> styles = <String>{
    effectiveShopStyle(shop),
    for (final AnimusDocument user in shopUsers(workspace, document.id))
      if (asString(shopModuleOf(user)['style']) case final String style) style,
  };
  final List<Diagnostic> notes = <Diagnostic>[
    for (final String style in styles)
      ...shopStyleNotes(shop, style, hasList: (String id) => layouts.containsKey(id) ? layoutHasList(layouts[id]!) : null),
  ];
  final List<Diagnostic> own = document.diagnostics;
  return <Diagnostic>[...own, ...withoutDuplicatePaths(own, <Diagnostic>[...shopOfferChecks(shop, knownItem: knownItem), ...notes])];
}

class ShopEditor extends StatefulWidget {
  const ShopEditor({
    required this.document,
    required this.onJson,
    this.workspaceDocuments = const <AnimusDocument>[],
    this.onCommit,
    this.onOpen,
    super.key,
  });

  final AnimusDocument document;
  final JsonEdit onJson;

  /// The workspace, for layout GUIs and the NPCs that open the shop.
  final List<AnimusDocument> workspaceDocuments;

  /// Writes NPC documents as one undoable change; null hides attaching.
  final ConversationCommit? onCommit;
  final void Function(DocumentKind kind, String id)? onOpen;

  @override
  State<ShopEditor> createState() => _ShopEditorState();
}

enum _Side { shop, npcs }

class _ShopEditorState extends State<ShopEditor> {
  OfferRef? _selected;
  Set<String> _checked = <String>{};
  List<RemovedOffer>? _removed;
  _Side _side = _Side.shop;

  JsonMap get _shop => asMap(component.document.json);

  @override
  void initState() {
    super.initState();
    if (ItemRegistry.ids.isEmpty) {
      ItemRegistry.load().then((List<String> _) {
        if (mounted) setState(() {});
      });
    }
  }

  void _write(List<JsonStep> steps, Object? value, {String? coalesce}) => component.onJson(writeAt(_shop, steps, value), coalesce: coalesce ?? steps.join('.'));

  void _edit(ShopEdit edit) {
    if (edit.ref == null) return;
    component.onJson(writeAt(_shop, const <JsonStep>['pages'], edit.shop['pages']));
    setState(() {
      if (_selected != null) _selected = edit.ref;
      _checked = <String>{};
    });
  }

  void _add(String kind) {
    final ShopEdit edit = addOffer(_shop, kind, page: _selected?.page ?? 0);
    if (edit.ref == null) return;
    component.onJson(writeAt(_shop, const <JsonStep>['pages'], edit.shop['pages']));
    setState(() => _selected = edit.ref);
    focusOfferRow(edit.ref!);
  }

  void _delete(List<OfferRef> refs) {
    final ({JsonMap shop, List<RemovedOffer> removed}) result = removeOffers(_shop, refs);
    if (result.removed.isEmpty) return;
    component.onJson(writeAt(_shop, const <JsonStep>['pages'], result.shop['pages']));
    setState(() {
      _removed = result.removed;
      _checked = <String>{};
      if (_selected != null && refs.contains(_selected)) _selected = null;
    });
  }

  void _undo() {
    final List<RemovedOffer>? removed = _removed;
    if (removed == null) return;
    component.onJson(writeAt(_shop, const <JsonStep>['pages'], restoreOffers(_shop, removed)['pages']));
    setState(() {
      _removed = null;
      _selected = removed.length == 1 ? removed.single.ref : null;
    });
    if (removed.length == 1) focusOfferRow(removed.single.ref);
  }

  void _select(OfferRef? ref) => setState(() => _selected = ref);

  @override
  Widget build(BuildContext context) {
    final JsonMap shop = _shop;
    final List<Diagnostic> all = shopEditorDiagnostics(component.document, workspace: component.workspaceDocuments, knownItem: ItemRegistry.ids.isEmpty ? null : ItemRegistry.has);
    List<Diagnostic> problemsOf(OfferRef ref) {
      final String path = ref.path;
      return <Diagnostic>[for (final Diagnostic d in all) if (d.path == path || d.path.startsWith('$path.') || d.path.startsWith('$path[')) d];
    }

    final OfferRef? selected = _selected != null && boardOfferAt(shop, _selected!) != null ? _selected : null;
    final List<BoardOffer> offers = boardOffers(shop);
    final Set<String> live = <String>{for (final BoardOffer offer in offers) offer.ref.key};
    final Set<String> checked = _checked.where(live.contains).toSet();
    final bool container = isContainerShop(shop);
    final int errors = all.where((Diagnostic d) => d.severity == DiagnosticSeverity.error).length;
    final List<Diagnostic> loose = <Diagnostic>[
      for (final Diagnostic d in all)
        if (!d.path.startsWith(r'$.pages[') || !offers.any((BoardOffer o) => d.path.startsWith(o.ref.path)))
          if (!_settingsShow(d.path)) d,
    ];
    final Map<String, JsonMap> layouts = workspaceLayouts(component.workspaceDocuments);
    final int users = shopUsers(component.workspaceDocuments, component.document.id).length;
    return dom.div(classes: 'animus-shop-editor', <Widget>[
      dom.section(classes: 'animus-panel animus-shop-board', attributes: const <String, String>{'aria-label': 'Offers'}, <Widget>[
        dom.div(classes: 'animus-shop-board-head', <Widget>[
          dom.h2(classes: 'animus-shop-panel-title', <Widget>[
            withTip(Component.text(offers.isEmpty ? 'Offers' : 'Offers (${offers.length})'), 'In the order players see them. Drag a row or press Alt with the arrow keys to reorder within its group.'),
          ]),
          MenuButton(
            id: 'animus-shop-add',
            label: '+ Add offer',
            emphasis: true,
            entries: <MenuEntry>[
              for (final String kind in shopOfferKinds)
                if (!container || kind == 'trade' || kind == 'static') MenuEntry(offerKindLabels[kind]!, () => _add(kind)),
            ],
          ),
        ]),
        if (errors > 0)
          dom.p(classes: 'animus-gui-problem is-error', attributes: const <String, String>{'role': 'status'}, <Widget>[
            Component.text('$errors problem${errors == 1 ? '' : 's'} to fix before the server loads this shop.'),
          ]),
        for (final Diagnostic problem in loose)
          dom.p(classes: 'animus-gui-problem is-${problem.severity.name}', attributes: const <String, String>{'role': 'note'}, <Widget>[
            dom.code(<Widget>[Component.text(problem.path)]),
            Component.text(' ${problem.message}'),
          ]),
        if (_removed != null)
          dom.div(classes: 'animus-row animus-shop-undo', attributes: const <String, String>{'role': 'status'}, <Widget>[
            Component.text('Deleted ${_removed!.length} offer${_removed!.length == 1 ? '' : 's'}.'),
            Button.secondary(label: 'Undo', size: ButtonSize.small, onPressed: _undo),
            GuiIconButton(icon: ArcaneIcon.x(size: IconSize.sm), label: 'Dismiss', onPressed: () => setState(() => _removed = null)),
          ]),
        if (checked.isNotEmpty)
          dom.div(classes: 'animus-row animus-shop-bulk', <Widget>[
            Component.text('${checked.length} selected'),
            Button.secondary(
              label: 'Delete ${checked.length}',
              size: ButtonSize.small,
              onPressed: () => _delete(<OfferRef>[for (final String key in checked) ?OfferRef.parse(key)]),
            ),
            Button.ghost(label: 'Select all', size: ButtonSize.small, disabled: checked.length == offers.length, onPressed: () => setState(() => _checked = live)),
            Button.ghost(label: 'Clear', size: ButtonSize.small, onPressed: () => setState(() => _checked = <String>{})),
          ]),
        OfferBoard(
          shop: shop,
          selected: selected,
          checked: checked,
          problemsOf: problemsOf,
          onSelect: _select,
          onCheck: (Set<String> keys) => setState(() => _checked = keys),
          onEdit: _edit,
          onDelete: _delete,
          editor: (BoardOffer offer) => ShopOfferEditor(
            key: ValueKey<String>('offer-editor:${offer.ref.key}'),
            shop: shop,
            offer: offer,
            diagnostics: all,
            onWrite: _write,
            onMove: (ShopEdit edit) {
              if (edit.ref == null) return;
              component.onJson(writeAt(shop, const <JsonStep>['pages'], edit.shop['pages']));
              setState(() => _selected = edit.ref);
            },
          ),
        ),
      ]),
      dom.div(classes: 'animus-shop-side', <Widget>[
        dom.section(classes: 'animus-panel animus-shop-preview', attributes: const <String, String>{'aria-label': 'Shop windows'}, <Widget>[
          ShopPreviews(document: component.document, layouts: layouts, selected: selected, onSelect: (OfferRef ref) => setState(() => _selected = ref)),
        ]),
        dom.section(classes: 'animus-panel animus-shop-settings', attributes: const <String, String>{'aria-label': 'Shop settings'}, <Widget>[
          GuiTabs(
            label: 'Shop parts',
            tabs: <String>['Shop', users == 0 ? 'NPCs' : 'NPCs ($users)'],
            selected: _side.index,
            onSelect: (int index) => setState(() => _side = _Side.values[index]),
          ),
          if (_side == _Side.shop)
            ShopSettings(shop: shop, diagnostics: all, layouts: layouts.keys.toList()..sort(), onWrite: _write)
          else if (component.onCommit != null)
            ShopLinks(shop: component.document.id, documents: component.workspaceDocuments, onCommit: component.onCommit!, onOpen: component.onOpen)
          else
            const dom.p(classes: 'animus-muted', <Widget>[Component.text('Open the workspace to attach NPCs.')]),
        ]),
      ]),
    ]);
  }

  /// Paths the settings tab shows at their field.
  static bool _settingsShow(String path) =>
      RegExp(r'^\$\.(title|style|layout|permission|modes|defaultMode|match|owner|containers|pages\[\d+\]\.(name|rows|permission))').hasMatch(path);
}
