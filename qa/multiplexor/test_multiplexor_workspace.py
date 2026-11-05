from pathlib import Path
import unittest

import multiplexor_workspace as mw


class MultiplexorWorkspaceTest(unittest.TestCase):
    def test_default_workspace_is_the_shared_server_multiplexor_checkout(self):
        workspace: Path = mw.default_workspace()
        self.assertEqual(workspace.name, 'ServerMultiplexor')
        self.assertNotIn('.qa', workspace.parts)
        self.assertEqual(mw.default_binary(), workspace / 'multiplexor')

    def test_evidence_stays_in_ignored_plugin_consumer_state(self):
        self.assertEqual(mw.evidence_root(Path('/w')), Path('/w/consumers/plugin-consumers/state/animus-evidence'))

    def test_baseline_name_follows_project_purpose_platform_version(self):
        self.assertEqual(mw.baseline_name('folia', '26.1.2'), 'animus-baseline-folia-26-1-2')


if __name__ == '__main__':
    unittest.main()
