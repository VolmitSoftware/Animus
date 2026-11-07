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

/// The Citizens screen's one main next step, the button shown as primary.
enum ConverterStep { applyImport, convertCitizens, convertShops, convertDenizen, openWorkspace, none }

/// What the screen should push next: a staged server import waits for
/// Apply first, then any chosen input not yet converted (Citizens, then
/// Shopkeepers, then Denizen), then opening the output as a workspace.
ConverterStep converterNextStep({
  required bool citizensChosen,
  required bool citizensConverted,
  required bool shopsChosen,
  required bool shopsConverted,
  required bool denizenChosen,
  required bool denizenConverted,
  String? importState,
}) {
  if (importState == 'staged' || importState == 'rolled-back') return ConverterStep.applyImport;
  if (citizensChosen && !citizensConverted) return ConverterStep.convertCitizens;
  if (shopsChosen && !shopsConverted) return ConverterStep.convertShops;
  if (denizenChosen && !denizenConverted) return ConverterStep.convertDenizen;
  if (citizensConverted || shopsConverted || denizenConverted) return ConverterStep.openWorkspace;
  return ConverterStep.none;
}
