/// The shop's offers as rows that read like trades: the item icons with
/// their counts, then the same as one sentence ("1 saddle for 6 emerald"),
/// under the icons on a phone. A row opens its
/// form in place. Rows reorder within their page and mode by drag or by
/// their "..." menu (Move up, Move down), which also duplicates and deletes.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../../logic/gui/gui_json.dart';
import '../../logic/gui/shop_board.dart';
import '../../logic/gui/shop_offers.dart';
import '../../model/animus_document.dart';
import '../../ui/badge.dart';
import '../../ui/tone.dart';
import '../mc/mc_item_icons.dart';
import 'gui_rows.dart';
import 'shop_offer_editor.dart' show offerKindTags;
import 'gui_stack.dart';
import '../../ui/stack.dart';

/// An item icon with its count, or the money token with its price.
class GoodsIcon extends StatelessWidget {
  const GoodsIcon(this.goods, {super.key});

  final ShopGoods goods;

  @override
  Widget build(BuildContext context) {
    final String? key = McItemIcons.keyOf(goods.item);
    final String? url = key == null ? null : McItemIcons.url(key);
    final String? label = goods.label;
    return Row(mainAxisSize: MainAxisSize.min, gapSize: Gap.xs, children: <Widget>[
      dom.span(classes: 'animus-shop-icon', <Widget>[
        dom.span(classes: 'animus-chest-face', <Widget>[
          if (url != null)
            dom.img(src: url, alt: '', attributes: const <String, String>{'draggable': 'false', 'width': '24', 'height': '24'})
          else
            dom.span(classes: 'animus-chest-missing', <Widget>[Component.text(goods.item.isEmpty ? '?' : itemWords(goods.item))]),
          if (label == null && goods.count > 1) dom.span(classes: 'animus-chest-count', <Widget>[Component.text('${goods.count}')]),
        ]),
      ]),
      if (label != null) dom.span(classes: 'animus-shop-price', <Widget>[Component.text(label)]),
    ]);
  }
}

/// Grid names as group headings.
const Map<String, String> offerGridTitles = <String, String>{'trade': 'Trades', 'buy': 'Buying', 'sell': 'Selling'};

class OfferBoard extends StatefulWidget {
  const OfferBoard({
    required this.shop,
    required this.selected,
    required this.checked,
    required this.problemsOf,
    required this.onSelect,
    required this.onCheck,
    required this.onEdit,
    required this.onDelete,
    required this.editor,
    super.key,
  });

  final JsonMap shop;
  final OfferRef? selected;

  /// Ticked rows by [OfferRef.key]; null hides the boxes (Simple).
  final Set<String>? checked;
  final List<Diagnostic> Function(OfferRef ref) problemsOf;
  final void Function(OfferRef? ref) onSelect;
  final void Function(Set<String> keys) onCheck;

  /// A board edit to save.
  final void Function(ShopEdit edit) onEdit;
  final void Function(OfferRef ref) onDelete;

  /// The open row's form.
  final Widget Function(BoardOffer offer) editor;

  @override
  State<OfferBoard> createState() => _OfferBoardState();
}

class _OfferBoardState extends State<OfferBoard> {
  @override
  void initState() {
    super.initState();
    McItemIcons.addListener(_refresh);
  }

  @override
  void dispose() {
    McItemIcons.removeListener(_refresh);
    super.dispose();
  }

  void _refresh() {
    if (mounted) setState(() {});
  }

  @override
  Widget build(BuildContext context) {
    final List<BoardOffer> offers = boardOffers(component.shop);
    if (offers.isEmpty) {
      return mutedText('No offers yet.');
    }
    final JsonList pages = asList(component.shop['pages']);
    final List<Widget> groups = <Widget>[];
    int start = 0;
    while (start < offers.length) {
      final OfferRef head = offers[start].ref;
      int end = start;
      while (end < offers.length && offers[end].ref.sameGroup(head)) {
        end++;
      }
      final List<BoardOffer> group = offers.sublist(start, end);
      final String page = asString(asMap(head.page < pages.length ? pages[head.page] : null)['name']) ?? 'Page ${head.page + 1}';
      final String title = pages.length > 1 ? '${offerGridTitles[head.grid]}, $page' : offerGridTitles[head.grid]!;
      groups.add(dom.section(attributes: <String, String>{'aria-label': title, 'data-group': '${head.page}:${head.grid}'}, <Widget>[
        stack(<Widget>[
          groupHeading(title, after: <Widget>[if (!group.first.modeOn) const StatusBadge('Mode off', tone: BadgeTone.warning)]),
          OrderedRows(
            label: title,
            deleteNoun: 'offer',
            rows: <OrderedRow>[for (int index = 0; index < group.length; index++) _row(group[index], start + index)],
            onMove: (int from, int to) {
              final ShopEdit edit = reorderOffer(component.shop, group[from].ref, to);
              if (edit.ref != null) component.onEdit(edit);
            },
            onDuplicate: (int index) {
              final ShopEdit edit = duplicateOffer(component.shop, group[index].ref);
              if (edit.ref == null) return;
              component.onEdit(edit);
              component.onSelect(edit.ref);
            },
            onDelete: (int index) => component.onDelete(group[index].ref),
          ),
        ]),
      ]));
      start = end;
    }
    return stack(spacing: 16, groups);
  }

  OrderedRow _row(BoardOffer offer, int number) {
    final OfferRef ref = offer.ref;
    final bool open = component.selected == ref;
    final Set<String>? checked = component.checked;
    final List<Diagnostic> problems = component.problemsOf(ref);
    final int errors = problems.where((Diagnostic d) => d.severity == DiagnosticSeverity.error).length;
    final int warnings = problems.length - errors;
    final String sentence = offerSentence(offer.entry);
    final OfferSides sides = offerSides(offer.entry);
    final String kind = offerKindTags[offer.type] ?? offer.type;
    return OrderedRow(
      key: ref.key,
      name: 'offer ${number + 1}',
      open: open,
      attributes: <String, String>{'data-offer': ref.key, 'data-id': offer.id},
      line: RowOpener(
        open: open,
        label: '$kind: $sentence.${errors > 0 ? ' $errors ${errors == 1 ? 'problem' : 'problems'}.' : ''}',
        onToggle: () => component.onSelect(open ? null : ref),
        body: open ? component.editor(offer) : null,
        leading: <Widget>[
          if (checked != null)
            dom.input(
              type: dom.InputType.checkbox,
              checked: checked.contains(ref.key),
              attributes: <String, String>{'aria-label': 'Select $sentence'},
              events: <String, void Function(web.Event)>{
                'change': (web.Event event) {
                  final bool on = (event.target! as web.HTMLInputElement).checked;
                  component.onCheck(on ? <String>{...checked, ref.key} : (<String>{...checked}..remove(ref.key)));
                },
              },
            ),
        ],
        children: <Widget>[
          dom.span(classes: 'animus-shop-trade', attributes: const <String, String>{'aria-hidden': 'true'}, <Widget>[
            Row(mainAxisSize: MainAxisSize.min, gapSize: Gap.xs, children: <Widget>[
              for (final ShopGoods goods in sides.gives) GoodsIcon(goods),
              for (final ShopGoods goods in sides.takes) GoodsIcon(goods),
            ]),
          ]),
          // Two lines before it is cut, so the price stays readable on a phone.
          Expanded(
            child: dom.span(classes: 'animus-shop-sentence', attributes: const <String, String>{'aria-hidden': 'true'}, <Widget>[
              Text(sentence, size: FontSize.sm, maxLines: 2, overflow: TextOverflow.ellipsis),
            ]),
          ),
          if (errors > 0)
            StatusBadge('$errors ${errors == 1 ? 'problem' : 'problems'}', tone: BadgeTone.error)
          else if (warnings > 0)
            StatusBadge('$warnings ${warnings == 1 ? 'note' : 'notes'}', tone: BadgeTone.warning),
        ],
      ),
    );
  }
}
