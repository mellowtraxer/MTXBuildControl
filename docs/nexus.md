# MTX Nexus development branch

The validation workflow exercises the routing policy and builds a test APK on
GitHub-hosted infrastructure. The validation APK uses the existing signing
identity and versionCode 1, so it is intended for a clean test device or
emulator. Production builds continue through the existing signed workflow.

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
Unknown and truncated trees are reported as such.

Run the routing and registry tests with
`python3 -m unittest discover -s nexus/tests -v`.
