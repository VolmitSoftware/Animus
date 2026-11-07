import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/gui/gui_layout.dart';
import 'package:animus_editor/logic/gui/shop_board.dart';
import 'package:animus_editor/logic/gui/shop_layout.dart';
import 'package:test/test.dart';

JsonMap _offer(String id, {String type = 'trade'}) => <String, Object?>{
      'id': id,
      'type': type,
      'item': <String, Object?>{'id': 'minecraft:bread'},
      if (type == 'trade') 'needs': <Object?>[<String, Object?>{'item': <String, Object?>{'id': 'minecraft:emerald'}, 'amount': 1}],
      if (type == 'trade') 'gives': <Object?>[<String, Object?>{'item': <String, Object?>{'id': 'minecraft:bread'}, 'amount': 1}],
    };

void main() {
  final JsonMap gui = <String, Object?>{
    'title': '{{ args.title }} {{ page }}/{{ pages }}',
    'resolution': '9x1',
    'mask': <Object?>['#...#####'],
    'keys': <String, Object?>{'#': <String, Object?>{'type': 'decoration', 'icon': <String, Object?>{'type': 'item', 'item': 'minecraft:gray_stained_glass_pane'}}},
    'list': <String, Object?>{'area': '.', 'var': 'offer', 'source': '[]', 'template': <String, Object?>{'type': 'decoration', 'icon': <String, Object?>{'type': 'item', 'item': 'minecraft:stone'}}},
  };
  final JsonMap shop = <String, Object?>{
    'id': 'town:market',
    'title': '&6Market',
    'pages': <Object?>[
      <String, Object?>{
        'trade': <String, Object?>{'3': _offer('c'), '1': _offer('b'), '0': _offer('a'), '7': _offer('d'), '5': _offer('pane', type: 'static')},
      },
    ],
  };

  test('should fill the list area in trading screen order, without decorations', () {
    final ({ChestLayout layout, int pages}) first = layoutShopInGui(gui, shop);
    expect(first.pages, 2);
    expect(first.layout.title, '&6Market 1/2');
    expect(<OfferRef?>[for (final int slot in <int>[1, 2, 3]) offerAtCell(first.layout.cell(slot))], const <OfferRef>[OfferRef(0, 'trade', 0), OfferRef(0, 'trade', 1), OfferRef(0, 'trade', 3)]);
    expect(offerAtCell(first.layout.cell(0)), isNull, reason: 'the GUI keeps its own slots');
  });

  test('should show the next list page and clamp past the end', () {
    final ({ChestLayout layout, int pages}) second = layoutShopInGui(gui, shop, page: 9);
    expect(second.layout.title, '&6Market 2/2');
    expect(offerAtCell(second.layout.cell(1)), const OfferRef(0, 'trade', 7));
    expect(second.layout.cell(2).source, ChestCellSource.empty);
    expect(layoutHasList(gui), isTrue);
    expect(layoutHasList(<String, Object?>{'mask': <Object?>[]}), isFalse);
  });
}
