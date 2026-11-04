# Future deployment integration

The user-provided Google service-account JSON is stored as the GitHub Actions repository secret `FIREBASE_SERVICE_ACCOUNT_ANIMUS` in `VolmitSoftware/Animus`. Its presence was verified through GitHub's secret metadata on 2026-11-04. The plaintext credential is not part of this repository.

The later Firebase workflow can reference `${{ secrets.FIREBASE_SERVICE_ACCOUNT_ANIMUS }}`. No deployment workflow or hosting rollout has been enabled at this stage. Configure the Firebase project/site, arcane_jaspr web build, and workflow only at the editor milestone, after the server milestones and their acceptance gates.
