"""Coverage rules for the isolated Folia stationary-preview acceptance gate.

This module does not verify artifacts or transcripts. A runtime auditor supplies
only check IDs whose live receipts and provenance it has already verified, and
an independently fixed required-check map. Unit races can inform the review but
cannot satisfy a required live gate. General Folia runtime and graphical claims
are outside this scope even when every stationary gate passes.
"""
SCOPE = 'viewer-private stationary preview'
GATES = ('ownership', 'virtual-presentation', 'authoritative-interaction', 'viewer-lifecycle',
         'unavailable-terrain', 'queued-cancellation', 'bounds', 'retirement-shutdown', 'general-disabled')
OUTSIDE_SCOPE = ('persisted multi-viewer Folia runtime', 'autonomous movement', 'remote control',
                 'mounts and riding', 'combat', 'graphical appearance', 'comparative performance')


def assess_gates(gates, verified_live_checks, required_checks):
    """Return passed/partial/failed coverage; malformed or unsupported evidence raises ValueError.

    Gate entries use id/status/mode/evidence. Evidence is a list of check IDs.
    Every required check must be both cited by the gate and independently verified.
    Missing gate declarations or incomplete required-check specifications stay
    unverified; neither means that a test passed. An explicit failure dominates.
    """
    if not isinstance(gates, list):
        raise ValueError('Gate declarations must be a list')
    if set(required_checks) - set(GATES):
        raise ValueError('Unknown required gate policy')
    verified = set(verified_live_checks)
    indexed = {}
    for gate in gates:
        if not isinstance(gate, dict) or gate.get('id') not in GATES:
            raise ValueError('Unknown or malformed stationary-proof gate')
        key = gate['id']
        if key in indexed:
            raise ValueError('Duplicate stationary-proof gate: ' + key)
        if gate.get('status') not in ('passed', 'failed', 'unverified'):
            raise ValueError('Invalid gate status: ' + key)
        if gate.get('mode') not in ('live', 'unit', 'unverified'):
            raise ValueError('Invalid gate evidence mode: ' + key)
        evidence = gate.get('evidence', [])
        if not isinstance(evidence, list) or any(not isinstance(item, str) or not item.strip() for item in evidence) or len(set(evidence)) != len(evidence):
            raise ValueError('Malformed gate evidence references: ' + key)
        if gate['status'] == 'passed' and not evidence:
            raise ValueError('Passing gate lacks evidence: ' + key)
        indexed[key] = gate
    evaluated = []
    for key in GATES:
        declared = indexed.get(key)
        required = required_checks.get(key, [])
        if not isinstance(required, (list, tuple)) or any(not isinstance(item, str) or not item for item in required) or len(set(required)) != len(required):
            raise ValueError('Malformed required live checks: ' + key)
        effective, reason = 'unverified', 'No gate declaration'
        if declared:
            if declared['status'] == 'failed':
                effective, reason = 'failed', 'Explicit gate failure'
            elif declared['status'] != 'passed':
                reason = 'Gate explicitly unverified'
            elif declared['mode'] != 'live':
                reason = 'Unit or unavailable evidence cannot establish live retirement/ownership behavior'
            elif not required:
                reason = 'Required live-check policy has not been completed'
            else:
                refs = set(declared['evidence'])
                if refs - verified:
                    raise ValueError('Unverified live evidence reference for ' + key + ': ' + ', '.join(sorted(refs - verified)))
                if set(required) - refs:
                    reason = 'Required live checks missing: ' + ', '.join(sorted(set(required) - refs))
                else:
                    effective, reason = 'passed', 'All required live checks verified'
        evaluated.append({'id': key, 'status': effective, 'declared': declared,
                          'requiredChecks': list(required), 'reason': reason})
    status = 'failed' if any(g['status'] == 'failed' for g in evaluated) else 'passed' if all(g['status'] == 'passed' for g in evaluated) else 'partial'
    return {'scope': SCOPE, 'status': status, 'gates': evaluated,
            'outstandingGates': [g['id'] for g in evaluated if g['status'] != 'passed'],
            'outsideScope': list(OUTSIDE_SCOPE)}
