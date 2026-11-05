/// Port of core's `CitizensImporter.Note`: a mapper's decision for one report field and everything under it.
library;

const String statusConverted = 'CONVERTED';
const String requiresReview = 'REQUIRES_REVIEW';
const String unsupportedPreserved = 'UNSUPPORTED_PRESERVED';
const String requiresIntegration = 'REQUIRES_INTEGRATION';

class FieldNote {
  const FieldNote(this.status, this.reason);

  final String status;
  final String reason;
}
