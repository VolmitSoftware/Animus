/// The keys each behavior form lays out itself; anything else goes to its
/// "Other fields" key/value editor and is kept as written.
library;

import 'completion_catalog.dart';
import 'schema_fields.dart';

/// Top-level keys of a behavior document with their own controls.
const Set<String> behaviorDocumentKeys = <String>{'schemaVersion', 'revision', 'id', 'server', 'entry', 'variables', 'routines', 'triggers'};

/// The fields of a trigger whose event is [spec], plus `event`.
Set<String> triggerKnownKeys(TriggerSpec? spec) => <String>{
      'event',
      for (final FieldSpec field in spec?.fields ?? const <FieldSpec>[]) field.key,
    };

/// The arguments [spec] declares; none for a capability outside the catalog.
Set<String> argumentKnownKeys(CapabilitySpec? spec) => <String>{
      for (final FieldSpec field in spec?.arguments ?? const <FieldSpec>[]) field.key,
    };
