/// The Citizens screen's converted output as the files of a new workspace:
/// each staged tree laid at the data-folder root, so its npcs/, shops/,
/// guis/ and behaviors/ documents open in the editor. Records, reports and
/// source copies are not documents and are dropped by the folder reader.
library;

import 'dart:typed_data';

import '../denizen/denizen_converter.dart';
import '../shopkeepers/shopkeepers_converter.dart';
import 'citizens_converter.dart';
import 'java_json.dart';

/// Every converted file by its data-folder path. A Denizen file wins over a
/// Shopkeepers file of the same path, and that over a Citizens one, as when
/// the bundles unzip in that order.
Map<String, Uint8List> convertedWorkspaceFiles({CitizensPreview? citizens, ShopkeepersPreview? shops, DenizenPreview? denizen}) => <String, Uint8List>{
      for (final Map<String, String> files in <Map<String, String>>[
        if (citizens != null) citizens.files,
        if (shops != null) shops.files,
        if (denizen != null) denizen.files,
      ])
        for (final MapEntry<String, String> file in files.entries) file.key: javaUtf8(file.value),
    };

/// The new workspace's name: what was converted.
String convertedWorkspaceName({required bool citizens, required bool shops, required bool denizen}) {
  final List<String> parts = <String>[if (citizens) 'Citizens', if (shops) 'Shopkeepers', if (denizen) 'Denizen'];
  return parts.isEmpty ? 'Converted' : '${parts.join(', ')} import';
}
