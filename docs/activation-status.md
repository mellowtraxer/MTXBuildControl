# MTX NEXUS activation status

This document distinguishes validated pilot behavior from production readiness.

## Verified pilot
- MTX Studio pilot supports a hosted controller, PC worker and hosted fallback.
- Build Control contains the Android OTA validation library.
- No production release permission is granted to the pilot.

## Blocked by account-level authorization
1. Install a narrowly scoped GitHub App with **Administration: read** on the MTX Studio pilot repository to query self-hosted runners. Store its client ID as `NEXUS_RUNNER_APP_CLIENT_ID` and its private key as `NEXUS_RUNNER_APP_PRIVATE_KEY`, or use the documented read-only credential alternative. Never commit the private key.
2. For account-wide discovery, install a GitHub App with **Metadata: read** and **Contents: read** for all intended repositories, including future ones. Configure `NEXUS_DISCOVERY_APP_CLIENT_ID` and `NEXUS_DISCOVERY_APP_PRIVATE_KEY` in an owner-only private controller repository.
3. Install the vetted `nexus/templates/private-registry.yml` workflow on that private repository's default branch. Verify inventory visibility and access controls.

## Next engineering gates
- Prove PC-first automatically, not only with manual dispatch.
- Test real worker disconnect and hosted recovery, not only simulated timeout.
- Preserve each application's signing identity and monotonically increasing Android versionCode.
- Reconcile LiquidNoir preview tag naming and artifact quota before enabling OTA.
- Migrate SleepSync, Mario Chess and LiquidNoir individually with tests.
- Validate OTA update and retained user data on a real Android device.
- Do not publish stable updates until artifact, signature, version and release-manifest checks pass.

## Safety
- Do not copy private inventory into the public MTXBuildControl repository.
- Do not place privileged GitHub App tokens in Android clients or untrusted worker jobs.
- A new repository is discoverable without implying its code is safe to build or deploy.
- Android updates require matching package ID and signing identity; Android will reject normal downgrades.
