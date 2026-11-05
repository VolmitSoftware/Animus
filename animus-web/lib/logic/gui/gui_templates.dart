/// Starting documents for the New buttons: each is valid against its schema
/// and the GUI checks, and shows the editor's main features.
library;

import '../../model/document_kind.dart';
import 'gui_json.dart';

/// The id a new document of [kind] gets before the user renames it.
String newDocumentId(DocumentKind kind, Set<String> taken) {
  final String base = switch (kind) {
    DocumentKind.inventory => 'example:menu',
    DocumentKind.shop => 'example:shop',
    DocumentKind.menu => 'example:menu',
    DocumentKind.hologram => 'example:hologram',
    _ => 'example',
  };
  if (!taken.contains(base)) return base;
  for (int index = 2;; index++) {
    final String candidate = '$base-$index';
    if (!taken.contains(candidate)) return candidate;
  }
}

JsonMap newDocument(DocumentKind kind, String id) => switch (kind) {
      DocumentKind.inventory => <String, Object?>{
          'schemaVersion': 1,
          'revision': 1,
          'title': '&8Menu',
          'resolution': '9x3',
          'mask': <String>['#########', '#   C   #', '#########'],
          'keys': <String, Object?>{
            '#': <String, Object?>{
              'type': 'decoration',
              'icon': <String, Object?>{'type': 'item', 'item': 'minecraft:gray_stained_glass_pane', 'name': ' '},
            },
            'C': <String, Object?>{
              'type': 'button',
              'icon': <String, Object?>{'type': 'item', 'item': 'minecraft:barrier', 'name': '&cClose'},
              'actions': <Object?>[<String, Object?>{'type': 'close'}],
            },
          },
        },
      DocumentKind.shop => <String, Object?>{
          'schemaVersion': 1,
          'revision': 1,
          'id': id,
          'title': '&6Shop',
          'modes': <String>['buy', 'sell'],
          'pages': <Object?>[
            <String, Object?>{
              'name': 'Goods',
              'rows': 3,
              'buy': <String, Object?>{
                '11': <String, Object?>{'id': 'bread', 'type': 'buy', 'item': <String, Object?>{'id': 'minecraft:bread', 'count': 4}, 'price': 12},
              },
              'sell': <String, Object?>{
                '13': <String, Object?>{'id': 'wheat', 'type': 'sell', 'item': <String, Object?>{'id': 'minecraft:wheat', 'count': 16}, 'price': 4},
              },
            },
          ],
        },
      DocumentKind.menu => <String, Object?>{
          'offset': <double>[0, 1.5, 2],
          'components': <Object?>[
            <String, Object?>{
              'id': 'title',
              'offset': <double>[0, 0.6, 0],
              'data': <String, Object?>{'type': 'decoration', 'icon': <String, Object?>{'type': 'text', 'text': '&6&lMenu'}},
            },
            <String, Object?>{
              'id': 'close',
              'offset': <double>[0, 0, 0],
              'data': <String, Object?>{
                'type': 'button',
                'icon': <String, Object?>{'type': 'item', 'item': 'minecraft:barrier', 'name': '&cClose'},
                'actions': <Object?>[<String, Object?>{'type': 'close'}],
              },
            },
          ],
        },
      DocumentKind.hologram => <String, Object?>{
          'schemaVersion': 3,
          'revision': 1,
          'anchor': <String, Object?>{'world': 'world', 'position': <double>[0, 65, 0]},
          'lines': <Object?>[
            '&6&lWelcome',
            '&7Hello, {{ viewer.name }}',
            <String, Object?>{'item': <String, Object?>{'type': 'item', 'item': 'minecraft:emerald'}},
          ],
        },
      _ => <String, Object?>{},
    };
