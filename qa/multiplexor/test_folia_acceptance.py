"""Folia preview evidence must not overstate missing, unit-only, or partial live work."""
import unittest
from folia_acceptance import GATES, OUTSIDE_SCOPE, SCOPE, assess_gates


class FoliaAcceptanceTest(unittest.TestCase):
    def setUp(self):
        self.policy = {gate: [gate + ':required-live-check'] for gate in GATES}
        self.verified = {check for checks in self.policy.values() for check in checks}
        self.gates = [{'id': gate, 'status': 'passed', 'mode': 'live', 'evidence': checks.copy()} for gate, checks in self.policy.items()]

    def test_complete_live_evidence_has_only_stationary_scope(self):
        result = assess_gates(self.gates, self.verified, self.policy)
        self.assertEqual('passed', result['status'])
        self.assertEqual(SCOPE, result['scope'])
        self.assertEqual(list(OUTSIDE_SCOPE), result['outsideScope'])
        self.assertIn('persisted multi-viewer Folia runtime', result['outsideScope'])
        self.assertIn('graphical appearance', result['outsideScope'])

    def test_unit_races_cannot_substitute_for_live_retirement(self):
        self.gates[7]['mode'] = 'unit'
        result = assess_gates(self.gates, self.verified, self.policy)
        self.assertEqual('partial', result['status'])
        self.assertEqual(['retirement-shutdown'], result['outstandingGates'])

    def test_missing_or_explicitly_unverified_gate_is_partial(self):
        for gates in [self.gates[:-1], self.gates[:-1] + [{'id': GATES[-1], 'status': 'unverified', 'mode': 'unverified', 'evidence': []}]]:
            with self.subTest(gates=gates):
                result = assess_gates(gates, self.verified, self.policy)
                self.assertEqual('partial', result['status'])
                self.assertEqual(['general-disabled'], result['outstandingGates'])

    def test_known_failure_dominates_missing_other_gates(self):
        result = assess_gates([{'id': 'ownership', 'status': 'failed', 'mode': 'live', 'evidence': []}], set(), self.policy)
        self.assertEqual('failed', result['status'])

    def test_passing_label_cannot_hide_missing_required_bounds_assertion(self):
        self.policy['bounds'].append('bounds:64-admissions')
        result = assess_gates(self.gates, self.verified, self.policy)
        self.assertEqual('partial', result['status'])
        self.assertEqual(['bounds'], result['outstandingGates'])

    def test_fabricated_reference_cannot_count_as_live_evidence(self):
        self.gates[0]['evidence'].append('not-in-verified-transcript')
        with self.assertRaisesRegex(ValueError, 'Unverified live evidence'):
            assess_gates(self.gates, self.verified, self.policy)

    def test_empty_policy_cannot_issue_acceptance(self):
        result = assess_gates(self.gates, self.verified, {})
        self.assertEqual('partial', result['status'])
        self.assertEqual(list(GATES), result['outstandingGates'])

    def test_duplicate_unknown_or_empty_passing_evidence_rejected(self):
        for gates in [self.gates + [self.gates[0]], [{'id': 'full-folia-runtime', 'status': 'passed', 'mode': 'live', 'evidence': ['fake']}],
                      [{'id': 'ownership', 'status': 'passed', 'mode': 'live', 'evidence': []}]]:
            with self.subTest(gates=gates):
                with self.assertRaises(ValueError):
                    assess_gates(gates, self.verified, self.policy)


if __name__ == '__main__':
    unittest.main()
