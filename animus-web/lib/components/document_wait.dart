/// The document frame while a server document opened by its address is not
/// here yet: a skeleton while it is read, an alert with Retry when the read
/// fails, and "not in this workspace" when neither the workspace nor the
/// server's list has it.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';

import '../model/document_kind.dart';
import '../services/server/error_copy.dart';
import '../state/document_loader.dart';
import '../state/library.dart';
import '../state/live_session.dart';
import '../ui/alert.dart';
import '../ui/skeleton.dart';
import 'list_page.dart' show listNoun;

class DocumentWait extends StatefulWidget {
  const DocumentWait({required this.kind, required this.id, required this.entry, required this.onHome, this.session, super.key});

  final DocumentKind kind;
  final String id;

  /// The catalog's entry for the document; null when it is not listed.
  final LibraryEntry? entry;

  /// The live session of a server workspace; null for a local one.
  final LiveSession? session;
  final void Function() onHome;

  @override
  State<DocumentWait> createState() => _DocumentWaitState();
}

class _DocumentWaitState extends State<DocumentWait> {
  bool _reading = false;
  LoadResult? _result;

  @override
  void initState() {
    super.initState();
    _read();
  }

  Future<void> _read() async {
    final LiveSession? session = component.session;
    if (session == null || component.entry == null) return;
    setState(() {
      _reading = true;
      _result = null;
    });
    final LoadResult result = await session.ensureLoaded(<DocumentRef>[DocumentRef(component.kind, component.id)]);
    if (!mounted) return;
    setState(() {
      _reading = false;
      _result = result;
    });
  }

  @override
  Widget build(BuildContext context) {
    final LibraryEntry? entry = component.entry;
    final String noun = listNoun(component.kind);
    if (entry == null || component.session == null) {
      return AppAlert(
        tone: AlertTone.error,
        title: '${noun[0].toUpperCase()}${noun.substring(1)} not in this workspace',
        message: 'It was renamed or deleted, or the link is from another workspace.',
        action: Button.outline(label: 'Home', size: ButtonSize.sm, onPressed: component.onHome),
      );
    }
    final LoadResult? result = _result;
    if (result != null && result.missing.isNotEmpty) {
      return AppAlert(
        tone: AlertTone.error,
        title: '${entry.name} is gone from the server',
        message: 'It was deleted or moved on the server.',
        action: Button.outline(label: 'Home', size: ButtonSize.sm, onPressed: component.onHome),
      );
    }
    if (result != null && result.failed.isNotEmpty) {
      final ErrorCopy? reason = result.reason;
      return AppAlert(
        tone: AlertTone.error,
        title: 'Could not load ${entry.name}',
        message: reason?.text ?? 'The server did not answer.',
        live: true,
        action: Button.outline(label: 'Retry', size: ButtonSize.sm, onPressed: _read),
      );
    }
    return Skeleton(rows: 8, label: _reading || entry.loading ? 'Loading ${entry.name}' : 'Waiting for ${entry.name}');
  }
}
