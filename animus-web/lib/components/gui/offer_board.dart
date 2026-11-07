/// The shop's offers as rows that read like trades: the kind, item icons
/// with counts on both sides of the arrow, the same as one sentence, and
/// the slot. A row opens its editor in place. Rows reorder within their
/// page and grid by drag or Alt+Up and Alt+Down; Up and Down move between
/// rows and Delete removes one, or every ticked row.
library;

import 'dart:js_interop';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../../config/docs_links.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/gui/shop_board.dart';
import '../../logic/gui/shop_offers.dart';
import '../../model/animus_document.dart';
import '../info_tip.dart';
import '../mc/mc_item_icons.dart';
import 'gui_fields.dart';
import 'shop_offer_editor.dart' show offerKindTags;

/// The element id of an offer row's main button.
String offerRowId(OfferRef ref) => 'animus-shop-offer-${ref.page}-${ref.grid}-${ref.slot}';

/// Focuses the row of [ref] once it is drawn: an edit is saved before the
/// board shows it, so this waits up to [frames] frames for the row.
void focusOfferRow(OfferRef ref, {int frames = 60}) => web.window.requestAnimationFrame(((JSNumber _) {
      final web.Element? element = web.document.getElementById(offerRowId(ref));
      if (element != null) {
        (element as web.HTMLElement).focus();
      } else if (frames > 0) {
        focusOfferRow(ref, frames: frames - 1);
      }
    }).toJS);

/// An item icon with its count, or the money token with its price.
class GoodsIcon extends StatelessWidget {
  const GoodsIcon(this.goods, {super.key});

  final ShopGoods goods;

  @override
  Widget build(BuildContext context) {
    final String? key = McItemIcons.keyOf(goods.item);
    final String? url = key == null ? null : McItemIcons.url(key);
    final String? label = goods.label;
    return dom.span(classes: 'animus-shop-goods-icon', <Widget>[
      dom.span(classes: 'animus-shop-icon', <Widget>[
        if (url != null)
          dom.img(src: url, alt: '', attributes: const <String, String>{'draggable': 'false'})
        else
          dom.span(classes: 'animus-shop-icon-missing', <Widget>[Component.text(goods.item.isEmpty ? '?' : itemWords(goods.item))]),
        if (label == null && goods.count > 1) dom.span(classes: 'animus-shop-icon-count', <Widget>[Component.text('${goods.count}')]),
      ]),
      if (label != null) dom.span(classes: 'animus-shop-icon-price', <Widget>[Component.text(label)]),
    ]);
  }
}

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

  /// Ticked rows, by [OfferRef.key].
  final Set<String> checked;
  final List<Diagnostic> Function(OfferRef ref) problemsOf;
  final void Function(OfferRef? ref) onSelect;
  final void Function(Set<String> keys) onCheck;

  /// A board edit to save.
  final void Function(ShopEdit edit) onEdit;
  final void Function(List<OfferRef> refs) onDelete;

  /// The open row's editor.
  final Widget Function(BoardOffer offer) editor;

  @override
  State<OfferBoard> createState() => _OfferBoardState();
}

class _OfferBoardState extends State<OfferBoard> {
  OfferRef? _dragging;
  String? _over;

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

  void _nudge(OfferRef ref, int delta) {
    final ShopEdit edit = nudgeOffer(component.shop, ref, delta);
    if (edit.ref == null || edit.ref == ref) return;
    component.onEdit(edit);
    focusOfferRow(edit.ref!);
  }

  void _key(web.Event raw, BoardOffer offer, List<BoardOffer> all) {
    final web.KeyboardEvent event = raw as web.KeyboardEvent;
    final int index = all.indexWhere((BoardOffer o) => o.ref == offer.ref);
    switch (event.key) {
      case 'ArrowUp' || 'ArrowDown':
        event.preventDefault();
        final int delta = event.key == 'ArrowUp' ? -1 : 1;
        if (event.altKey) {
          _nudge(offer.ref, delta);
        } else if (index + delta >= 0 && index + delta < all.length) {
          focusOfferRow(all[index + delta].ref);
        }
      case 'Home' || 'End':
        event.preventDefault();
        if (all.isNotEmpty) focusOfferRow((event.key == 'Home' ? all.first : all.last).ref);
      case 'Delete':
        event.preventDefault();
        final bool many = component.checked.contains(offer.ref.key) && component.checked.length > 1;
        component.onDelete(many ? <OfferRef>[for (final String key in component.checked) ?OfferRef.parse(key)] : <OfferRef>[offer.ref]);
        final BoardOffer? next = index + 1 < all.length ? all[index + 1] : (index > 0 ? all[index - 1] : null);
        if (next != null && !many) focusOfferRow(next.ref);
    }
  }

  @override
  Widget build(BuildContext context) {
    final List<BoardOffer> offers = boardOffers(component.shop);
    if (offers.isEmpty) {
      return const dom.p(classes: 'animus-muted animus-shop-empty', <Widget>[Component.text('No offers yet. Add one to start.')]);
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
      final String page = asString(asMap(head.page < pages.length ? pages[head.page] : null)['name']) ?? 'Page ${head.page + 1}';
      final String title = '${offerGridTitles[head.grid]} · $page';
      groups.add(dom.section(classes: 'animus-shop-group', attributes: <String, String>{'aria-label': title, 'data-group': '${head.page}:${head.grid}'}, <Widget>[
        dom.h3(classes: 'animus-shop-group-title', <Widget>[
          withTip(Component.text(title), 'Offers in one mode on one page. Players switch between Trades, Buying and Selling in the window.', docs: Docs.shopEntries),
          if (!offers[start].modeOn) const dom.span(classes: 'animus-gui-problem is-warning', <Widget>[Component.text(' Mode off: players never see these.')]),
        ]),
        dom.ol(classes: 'animus-shop-offers', <Widget>[
          for (int index = start; index < end; index++) _row(offers[index], offers, index - start, end - start),
        ]),
      ]));
      start = end;
    }
    return dom.div(classes: 'animus-shop-board-groups', groups);
  }

  Widget _row(BoardOffer offer, List<BoardOffer> all, int position, int count) {
    final OfferRef ref = offer.ref;
    final bool open = component.selected == ref;
    final bool ticked = component.checked.contains(ref.key);
    final List<Diagnostic> problems = component.problemsOf(ref);
    final int errors = problems.where((Diagnostic d) => d.severity == DiagnosticSeverity.error).length;
    final int warnings = problems.length - errors;
    final String sentence = offerSentence(offer.entry);
    final OfferSides sides = offerSides(offer.entry);
    final String name = '${offerKindTags[offer.type] ?? offer.type}: $sentence';
    final List<String> classes = <String>[
      'animus-shop-offer',
      if (open) 'is-open',
      if (ticked) 'is-ticked',
      if (_over == ref.key) 'is-drop',
      if (_dragging == ref) 'is-dragging',
    ];
    return dom.li(
      key: ValueKey<String>('offer:${ref.key}'),
      classes: classes.join(' '),
      attributes: <String, String>{'data-offer': ref.key, 'data-id': offer.id},
      events: <String, void Function(web.Event)>{
        'dragover': (web.Event event) {
          final OfferRef? from = _dragging;
          if (from == null || !from.sameGroup(ref) || from == ref) return;
          event.preventDefault();
          if (_over != ref.key) setState(() => _over = ref.key);
        },
        'dragleave': (web.Event _) {
          if (_over == ref.key) setState(() => _over = null);
        },
        'drop': (web.Event event) {
          final OfferRef? from = _dragging;
          event.preventDefault();
          setState(() {
            _dragging = null;
            _over = null;
          });
          if (from == null || !from.sameGroup(ref) || from == ref) return;
          final ShopEdit edit = reorderOffer(component.shop, from, offerPosition(component.shop, ref).$1);
          if (edit.ref != null) component.onEdit(edit);
        },
      },
      <Widget>[
        dom.div(
          classes: 'animus-shop-offer-row',
          attributes: const <String, String>{'draggable': 'true'},
          events: <String, void Function(web.Event)>{
            'dragstart': (web.Event event) {
              (event as web.DragEvent).dataTransfer?.setData('text/plain', ref.key);
              event.dataTransfer?.effectAllowed = 'move';
              setState(() => _dragging = ref);
            },
            'dragend': (web.Event _) => setState(() {
                  _dragging = null;
                  _over = null;
                }),
          },
          <Widget>[
          dom.span(classes: 'animus-shop-grip', attributes: const <String, String>{'title': 'Drag to reorder', 'aria-hidden': 'true'}, <Widget>[
            ArcaneIcon.gripVertical(size: IconSize.sm),
          ]),
          dom.label(classes: 'animus-shop-check', <Widget>[
            dom.input(
              type: dom.InputType.checkbox,
              checked: ticked,
              attributes: <String, String>{'aria-label': 'Select $name'},
              events: <String, void Function(web.Event)>{
                'change': (web.Event event) {
                  final bool on = (event.target! as web.HTMLInputElement).checked;
                  component.onCheck(on ? <String>{...component.checked, ref.key} : (<String>{...component.checked}..remove(ref.key)));
                },
              },
            ),
          ]),
          dom.button(
            id: offerRowId(ref),
            classes: 'animus-shop-offer-main',
            attributes: <String, String>{
              'type': 'button',
              'aria-expanded': '$open',
              'aria-label': '$name. ${_where(ref)}.${errors > 0 ? ' $errors problem${errors == 1 ? '' : 's'}.' : ''} Alt and arrow keys reorder.',
            },
            events: <String, void Function(web.Event)>{
              'click': (web.Event _) => component.onSelect(open ? null : ref),
              'keydown': (web.Event event) => _key(event, offer, all),
            },
            <Widget>[
              dom.span(classes: 'animus-shop-trade', attributes: const <String, String>{'aria-hidden': 'true'}, <Widget>[
                for (int i = 0; i < sides.takes.length; i++) ...<Widget>[
                  if (i > 0) const dom.span(classes: 'animus-shop-plus', <Widget>[Component.text('+')]),
                  GoodsIcon(sides.takes[i]),
                ],
                if (offer.type != 'static') const dom.span(classes: 'animus-shop-arrow', <Widget>[Component.text('→')]),
                for (int i = 0; i < sides.gives.length; i++) ...<Widget>[
                  if (i > 0) const dom.span(classes: 'animus-shop-plus', <Widget>[Component.text('+')]),
                  GoodsIcon(sides.gives[i]),
                ],
              ]),
              dom.span(classes: 'animus-shop-sentence', attributes: const <String, String>{'aria-hidden': 'true'}, <Widget>[Component.text(sentence)]),
              dom.span(classes: 'animus-shop-where', attributes: const <String, String>{'aria-hidden': 'true'}, <Widget>[
                dom.span(classes: 'animus-shop-kind is-${offer.type}', <Widget>[Component.text(offerKindTags[offer.type] ?? offer.type)]),
                Component.text(' · Slot ${ref.slot}'),
                if (errors > 0) dom.span(classes: 'animus-shop-flag is-error', <Widget>[Component.text(' · $errors problem${errors == 1 ? '' : 's'}')]),
                if (errors == 0 && warnings > 0) dom.span(classes: 'animus-shop-flag is-warning', <Widget>[Component.text(' · $warnings note${warnings == 1 ? '' : 's'}')]),
              ]),
            ],
          ),
          dom.div(classes: 'animus-shop-offer-actions', <Widget>[
            GuiIconButton(icon: ArcaneIcon.arrowUp(size: IconSize.sm), label: 'Move earlier: $sentence', disabled: position == 0, onPressed: () => _nudge(ref, -1)),
            GuiIconButton(icon: ArcaneIcon.arrowDown(size: IconSize.sm), label: 'Move later: $sentence', disabled: position >= count - 1, onPressed: () => _nudge(ref, 1)),
            GuiIconButton(
              icon: ArcaneIcon.copy(size: IconSize.sm),
              label: 'Duplicate: $sentence',
              onPressed: () {
                final ShopEdit edit = duplicateOffer(component.shop, ref);
                if (edit.ref == null) return;
                component.onEdit(edit);
                component.onSelect(edit.ref);
              },
            ),
            GuiIconButton(icon: ArcaneIcon.trash(size: IconSize.sm), label: 'Delete: $sentence', onPressed: () => component.onDelete(<OfferRef>[ref])),
          ]),
        ]),
        if (open) ...<Widget>[
          for (final Diagnostic problem in problems)
            dom.p(classes: 'animus-gui-problem is-${problem.severity.name}', attributes: const <String, String>{'role': 'note'}, <Widget>[
              dom.code(<Widget>[Component.text(problem.path.substring(ref.path.length).isEmpty ? 'offer' : problem.path.substring(ref.path.length))]),
              Component.text(' ${problem.message}'),
            ]),
          component.editor(offer),
        ],
      ],
    );
  }

  String _where(OfferRef ref) => 'Slot ${ref.slot}';
}

/// Grid names as group headings.
const Map<String, String> offerGridTitles = <String, String>{'trade': 'Trades', 'buy': 'Buying', 'sell': 'Selling'};
