# MTX Nexus development branch

The validation workflow exercises the routing policy and builds a test APK on
GitHub-hosted infrastructure. The validation APK uses the existing signing
identity and versionCode 18. The emulator upgrades from the actual published
versionCode 17 APK, checks data retention,
and tests rejection of an incompatible signer and a downgrade. Production builds continue through the existing signed workflow.

## Routing contract

Use the controller from a hosted job in the private source repository. The
GitHub token needs Actions write and Contents read. A separate credential with
Administration read lists the repository's registered runners. Keep that
credential in a server-side GitHub secret and scope it to the pilot repository.
The trusted branch must be protected from unauthorized workflow changes.

A runner qualifies only if it reports idle and online, is Windows RICCI, and
advertises the exact labels selected by the worker, including the adapter's
capability label. Add capability labels only after testing the actual toolchain
on the existing installation. The worker must also recheck its tools before
building because a label can outlive the installed toolchain.

The PC worker is a separate workflow with an immutable source SHA input. It must
exist on the default branch for dispatch. Bind it to the private repository and
trusted branch, verify the SHA belongs to that branch, and deny all release and
signing access. The hosted controller waits up to 90 seconds in the queue and
25 minutes once the worker starts, then requests cancellation of the exact run.
A disconnected worker can take longer to receive cancellation. Never use an
artifact from an unconfirmed or discarded attempt.

Run the hosted build for the same source SHA when the controller outputs
`target=cloud`. When it outputs `target=pc`, use only the artifacts of the
returned worker run ID. A separate gated hosted job should own any eventual
signing or publishing. Neither the controller nor this branch's validation
workflow publishes updates.

## Inventory

Run `python3 -m nexus.registry --owner OWNER --output inventory.json` with a
GitHub App installation token in `NEXUS_INSTALLATION_TOKEN`. The App needs
Metadata read and Contents read and must be installed for the intended scope.
The inventory is a complete snapshot of visible projects keyed by GitHub's
repository IDs. A renamed project retains its ID. A disappeared project might
have been deleted or its installation access removed. The output can contain
private metadata. Keep it within the authorized service or user session.
Branch details include the build type and workflow files for each branch.
Unknown and truncated trees are reported as such. Repositories that disappear
during the scan are omitted from the next complete snapshot.

Run the routing and registry tests with
`python3 -m unittest discover -s nexus/tests -v`.

## Android OTA validation module

`:android-ota` is an Android library that checks SHA-256, package identity,
version monotonicity, declared version, and exact current signers. Build
Control uses it for its public stable GitHub releases. Existing apps can
reuse the validation with their current manifest and installer. Metadata
transport, channel policy, and app-specific UI belong to the caller.

### Scheduled private inventory

Copy `nexus/templates/private-registry.yml` to
`.github/workflows/nexus-registry.yml` on the default branch of an owner-only
private control repository. The workflow runs twice an hour and on demand.
Keep the inventory repository restricted to users who may see metadata for
all inventoried projects. A public repository is ineligible.

Install a GitHub App with Metadata read and Contents read. Choose all
repositories at installation time if newly created repositories should be
included without per-repository selection. Set the App client ID in
`NEXUS_DISCOVERY_APP_CLIENT_ID` and the private key in the Actions secret
`NEXUS_DISCOVERY_APP_PRIVATE_KEY`. The scheduled workflow requests a short-lived
installation token and keeps successful inventory snapshots for seven days.
The workflow's output is an inventory, not permission to run the listed code.
