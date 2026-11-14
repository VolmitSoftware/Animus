import 'package:animus_editor/model/document_kind.dart';
import 'package:animus_editor/state/catalog_entry.dart';
import 'package:animus_editor/state/server_catalog.dart';
import 'package:test/test.dart';

void main() {
  test('should keep catalog events that arrive while the catalog pages are read', () {
    CatalogEntry entry(String id, int revision) => CatalogEntry(DocumentKind.npc, id, 'npcs/$id.json', revision, 'sha256:$id-$revision');
    final ServerCatalog catalog = ServerCatalog()..replace(<CatalogEntry>[entry('town/a', 1), entry('town/b', 1), entry('town/c', 1)]);
    catalog.beginLoad();
    catalog
      ..upsert(entry('town/a', 5))
      ..remove('npc:town/b')
      ..upsert(entry('town/c', 2))
      ..upsert(entry('town/d', 1));
    // Pages read before or after those events: town/c's page is newer than its event.
    catalog.replace(<CatalogEntry>[entry('town/a', 4), entry('town/b', 1), entry('town/c', 3)]);
    expect(catalog['npc:town/a']?.revision, 5, reason: 'an event newer than its page wins');
    expect(catalog['npc:town/b'], isNull, reason: 'a delete during the read stands');
    expect(catalog['npc:town/c']?.revision, 3, reason: 'a page newer than the event wins');
    expect(catalog['npc:town/d']?.revision, 1, reason: 'a document created during the read stays');
    catalog.replace(<CatalogEntry>[entry('town/a', 6)]);
    expect(catalog.keys, <String>['npc:town/a'], reason: 'only one read takes the events');
  });
}
