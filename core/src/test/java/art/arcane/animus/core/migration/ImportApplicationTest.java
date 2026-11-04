package art.arcane.animus.core.migration;

import org.junit.jupiter.api.Test;

/** End-to-end storage cases exercise conflicts, rollback/restart, copied references and resource bounds. */
class ImportApplicationTest {
    @Test void identityProjectionRetainsAndRemapsOnlyReviewedReferences() throws Exception { ProjectionChecks.main(new String[0]); }
    @Test void applicationsRemainAtomicAcrossEditsRestartAndRollback() throws Exception { ApplicationChecks.main(new String[0]); }
    @Test void compactTransactionsRecoverWithBoundedValidatedPayloads() throws Exception { CompactTransactionChecks.main(new String[0]); }
    @Test void applicationPlansFitTheirLoadAndTransactionBudgets() throws Exception { ApplicationLimitsChecks.main(new String[0]); }
}
