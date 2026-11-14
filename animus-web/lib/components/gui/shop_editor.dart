/// The shop page: the form on the left in one tab strip (Offers, Settings)
/// and the window players get on the right. Offers read as trades, open in
/// place to edit, reorder by drag or their "..." menu; the window follows
/// every edit and picks offers too. Problems show at the offer they belong
/// to: schema and server rules, the notes for what a window cannot show,
/// and offer checks such as limits that conflict.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';

import '../../logic/diagnostic_text.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/gui/shop_board.dart';
import '../../logic/gui/shop_checks.dart';
import '../../logic/gui/shop_entries.dart';
import '../../logic/gui/shop_layout.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../../ui/action_row.dart';
import '../../ui/alert.dart';
import '../../ui/confirm_dialog.dart';
import '../../ui/mode.dart';
import '../../ui/tabs.dart';
import '../conversation/conversation_attach.dart' show ConversationCommit;
import '../mc/item_picker.dart';
import '../../ui/menu.dart';
import 'gui_fields.dart' show JsonEdit;
import 'gui_split.dart';
import 'offer_board.dart';
import 'shop_links.dart';
import 'shop_offer_editor.dart';
import 'shop_previews.dart';
import 'shop_settings.dart';
import '../../ui/stack.dart';

/// The workspace's chest GUIs by id.
Map<String, JsonMap> workspaceLayouts(Iterable<AnimusDocument> documents) => <String, JsonMap>{
      for (final AnimusDocument document in documents)
        if (document.kind == DocumentKind.inventory && document.json is Map<String, Object?>) document.id: asMap(document.json),
    };

/// Every diagnostic the shop page shows: the document's own, then the
/// offer checks and the notes for each window the shop opens in.
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

/// The kind "Add offer" makes: the shop's first mode, a trade in a shop
/// with containers.
String defaultOfferKind(JsonMap shop) {
  if (isContainerShop(shop)) return 'trade';
  return switch (enabledShopModes(shop).first) { 'buy' => 'buy', 'sell' => 'sell', _ => 'trade' };
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

class _ShopEditorState extends State<ShopEditor> {
  int _tab = 0;
  OfferRef? _selected;
  Set<String> _checked = <String>{};

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

  void _save(ShopEdit edit, {bool select = false}) {
    if (edit.ref == null) return;
    component.onJson(writeAt(_shop, const <JsonStep>['pages'], edit.shop['pages']));
    setState(() {
      if (select || _selected != null) _selected = edit.ref;
      _checked = <String>{};
    });
  }

  void _add(String kind) {
    _save(addOffer(_shop, kind, page: _selected?.page ?? 0), select: true);
    setState(() => _tab = 0);
  }

  void _delete(List<OfferRef> refs) {
    final ({JsonMap shop, List<RemovedOffer> removed}) result = removeOffers(_shop, refs);
    if (result.removed.isEmpty) return;
    component.onJson(writeAt(_shop, const <JsonStep>['pages'], result.shop['pages']));
    setState(() {
      _checked = <String>{};
      if (_selected != null && refs.contains(_selected)) _selected = null;
    });
  }

  Future<void> _deleteChecked(List<OfferRef> refs) async {
    final String count = refs.length == 1 ? 'offer' : '${refs.length} offers';
    if (await askToConfirm(title: 'Delete $count?', message: 'Undo brings them back.', confirmLabel: 'Delete', destructive: true)) _delete(refs);
  }

  @override
  Widget build(BuildContext context) {
    final JsonMap shop = _shop;
    final bool advanced = ModeScope.advanced(context);
    final List<Diagnostic> all = shopEditorDiagnostics(component.document, workspace: component.workspaceDocuments, knownItem: ItemRegistry.ids.isEmpty ? null : ItemRegistry.has);
    final OfferRef? selected = _selected != null && boardOfferAt(shop, _selected!) != null ? _selected : null;
    final Map<String, JsonMap> layouts = workspaceLayouts(component.workspaceDocuments);
    final int settingsErrors = all.where((Diagnostic d) => d.severity == DiagnosticSeverity.error && ShopSettings.shows(d.path)).length;
    return GuiSplit(
      label: 'Shop',
      form: <Widget>[
        AppTabs(
          label: 'Shop',
          tabs: <String>['Offers', settingsErrors == 0 ? 'Settings' : 'Settings ($settingsErrors)'],
          selected: _tab,
          onSelect: (int index) => setState(() => _tab = index),
        ),
        if (_tab == 0)
          _offers(shop, all, selected, advanced)
        else
          ShopSettings(
            shop: shop,
            shopId: component.document.id,
            diagnostics: all,
            layouts: layouts.keys.toList()..sort(),
            documents: component.workspaceDocuments,
            onWrite: _write,
            onCommit: component.onCommit,
            onOpen: component.onOpen,
          ),
      ],
      preview: (bool band) => ShopPreviews(
        document: component.document,
        layouts: layouts,
        selected: selected,
        band: band,
        onSelect: (OfferRef ref) => setState(() {
          _selected = ref;
          _tab = 0;
        }),
      ),
    );
  }

  Widget _offers(JsonMap shop, List<Diagnostic> all, OfferRef? selected, bool advanced) {
    final List<BoardOffer> offers = boardOffers(shop);
    final Set<String> live = <String>{for (final BoardOffer offer in offers) offer.ref.key};
    final Set<String> checked = advanced ? _checked.where(live.contains).toSet() : const <String>{};
    final bool container = isContainerShop(shop);
    final String first = defaultOfferKind(shop);
    final List<Diagnostic> loose = <Diagnostic>[
      for (final Diagnostic d in all)
        if (!offers.any((BoardOffer o) => d.path == o.ref.path || d.path.startsWith('${o.ref.path}.') || d.path.startsWith('${o.ref.path}[')))
          if (!ShopSettings.shows(d.path)) d,
    ];
    return stack(spacing: 16, <Widget>[
      ActionRow(
        dense: true,
        label: 'Offers',
        moreId: 'animus-shop-add-more',
        leading: <Widget>[
          mutedText(offers.length == 1 ? '1 offer' : '${offers.length} offers'),
        ],
        more: <AppMenuEntry>[
          for (final String kind in shopOfferKinds)
            if (kind != first && (!container || kind == 'trade' || kind == 'static')) AppMenuEntry('Add ${offerKindNouns[kind]}', () => _add(kind)),
        ],
        primary: RowAction('Add offer', () => _add(first), icon: ArcaneIcon.plus(), id: 'animus-shop-add'),
      ),
      for (final Diagnostic problem in loose)
        AppAlert(tone: problem.severity == DiagnosticSeverity.error ? AlertTone.error : AlertTone.warning, message: diagnosticSentenceIn(problem, advanced: ModeScope.advanced(context))),
      if (checked.isNotEmpty)
        ActionRow(
          dense: true,
          label: 'Selected offers',
          leading: <Widget>[mutedText('${checked.length} selected')],
          secondary: <RowAction>[
            RowAction('Select all', checked.length == offers.length ? null : () => setState(() => _checked = live)),
            RowAction('Clear', () => setState(() => _checked = <String>{})),
          ],
          primary: RowAction('Delete', () => _deleteChecked(<OfferRef>[for (final String key in checked) ?OfferRef.parse(key)]), destructive: true),
        ),
      OfferBoard(
        shop: shop,
        selected: selected,
        checked: advanced ? checked : null,
        problemsOf: (OfferRef ref) => diagnosticsUnder(all, ref.path),
        onSelect: (OfferRef? ref) => setState(() => _selected = ref),
        onCheck: (Set<String> keys) => setState(() => _checked = keys),
        onEdit: _save,
        onDelete: (OfferRef ref) => _delete(<OfferRef>[ref]),
        editor: (BoardOffer offer) => ShopOfferEditor(
          key: ValueKey<String>('offer-editor:${offer.ref.key}'),
          shop: shop,
          offer: offer,
          diagnostics: all,
          onWrite: _write,
          onMove: (ShopEdit edit) => _save(edit, select: true),
        ),
      ),
    ]);
  }
}

/// Diagnostics at [path] or inside it.
List<Diagnostic> diagnosticsUnder(List<Diagnostic> all, String path) =>
    <Diagnostic>[for (final Diagnostic d in all) if (d.path == path || d.path.startsWith('$path.') || d.path.startsWith('$path[')) d];
