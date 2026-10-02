# Wheels LuCLI patch queue

This fork supplies the LuCLI runtime tested and shipped by Wheels. It carries
upstream bug fixes, not fork-only product features. Do not recreate `bpamiri/LuCLI`:
its GitHub redirect is still used by older installed formulas.

## Branches and versions

- `main` mirrors `cybersonic/LuCLI` main. Never commit fork changes there.
- `wheels` starts at the latest shipped fork release tag and carries the reviewed patch queue.
- Work and upstream PR branches use `peter/…`.
- Fork releases use `<base>.<n>`: `0.6.2.1`, `0.6.2.2`, etc., where the base is
  the latest shipped fork release. A future upstream rebase must preserve shipped
  behavior and sort after installed versions. Reset the counter for a new base. These are releases, not prereleases.

Every behavioral patch must have an upstream PR. Drop a patch when the next
upstream base includes it. A fix merged on upstream main but absent from the
chosen release tag remains a documented backport until the base contains it.
Fork version metadata, this runbook, and fork-only release routing are operational
changes; do not propose those as upstream product features.

## 0.6.2.1 queue

Base: `wheels-dev/LuCLI` tag `v0.6.2` (`6067a6a03abaad4e17ece3f94e0271ddcaca3c99`).
This supersedes the unpublished 0.6.1.1 plan: Homebrew already ships the fork's
0.6.2, which includes upstream #114 dependency lifecycle hooks and #119 version
flags/build metadata. Building from upstream v0.6.1 would drop that behavior and
sort below the installed runtime. The maintainer selected this base on 2026-09-27.

The v0.6.2 release notes claim #123, but its tagged source still has the old
`!file.exists()` module guard and has no matching #123 patch ID. Retain #123 in
this source queue; do not infer tagged source content from release notes.

| Order | Upstream change | Purpose |
|---|---|---|
| 0 | [811284e](https://github.com/cybersonic/LuCLI/commit/811284e171d3f6bd8f9e4b17b3ee66431b52e9f8), dependency pin only | Use released Lucee 7.0.4.34 |
| 1 | [#123](https://github.com/cybersonic/LuCLI/pull/123), `74ee90c` | Aliased binary routes to its module despite a same-named cwd entry |
| 2 | [#124](https://github.com/cybersonic/LuCLI/pull/124), `ef658f1` | Reserved root command tokens remain module positional arguments |
| 3 | [#128](https://github.com/cybersonic/LuCLI/pull/128), `caf43f0` | Preserve captured MCP output when a tool throws (#125) |
| 4 | [#129](https://github.com/cybersonic/LuCLI/pull/129), `0b4f0c7` | Honor false-valued help controls (#126) |
| 5 | [#130](https://github.com/cybersonic/LuCLI/pull/130), `4c124ba` + `944f230` | Forward module timeout arguments (#127) |

Build dependency: upstream backport [811284e](https://github.com/cybersonic/LuCLI/commit/811284e171d3f6bd8f9e4b17b3ee66431b52e9f8)
(`lucee.version` pin only) selects released Lucee `7.0.4.34`; the v0.6.2 tagged
`7.0.4.18-SNAPSHOT` dependency is no longer resolvable. This is already merged
upstream and drops on the next upstream rebase that contains it. The upstream
commit's unrelated project-version change and formatting are not backported.

Conflict resolutions preserve the new helpers/tests from both sides. The #123
changelog resolution preserves the base's existing 0.7.0 section and adds the fix
under Unreleased.

## 0.6.2.2 queue

Base: the shipped fork release `v0.6.2.1` (`43b56f6`). Everything in the 0.6.2.1 queue
above carries forward unchanged.

| Order | Change | Purpose |
|---|---|---|
| 6 | wheels-dev/LuCLI [#14](https://github.com/wheels-dev/LuCLI/pull/14), squash `5255307` (issue [#13](https://github.com/wheels-dev/LuCLI/issues/13)) | Dev servers listen on `127.0.0.1` by default; `bindAddress` / `--host` opt-in |
| 7 | release branch `78e2f40` + the dry-run validation commit after it (review follow-ups on #15) | JMX monitoring honours `bindAddress` (registry, RMI connector, RMI hostname); unauthenticated JMX is refused on a non-loopback bind unless authentication is configured in `jvm.additionalArgs`, including in `--dry-run`; schema default for `monitoring.enabled` corrected to `false`; the Docker runtime prints its listen address |

Upstream PR to `cybersonic/LuCLI` (rows 6 and 7): **pending maintainer OK** (drafted;
it is opened only after that OK). Until upstream merges it, it is a documented fork patch.

Behaviour change for users of the fork: a dev server is no longer reachable from
other machines unless `bindAddress` (or `--host`) opts in. With `monitoring.enabled`,
JMX now listens on the same address, and a start (or `--dry-run`) that combines
monitoring with a non-loopback bind fails with a clear error unless JMX
authentication is configured. Clients that resolve
`localhost` only to `::1` need `127.0.0.1` (documented). A second loopback connector
on `::1` is a possible follow-up, not part of this release.

## Next queue (unreleased)

Base: the shipped fork release `v0.6.2.2` (`b3c01ce`). Everything above carries forward.
No release is tagged for this queue yet; tagging needs the Wheels release owner's go.

| Order | Change | Purpose |
|---|---|---|
| 8 | wheels-dev/LuCLI [#19](https://github.com/wheels-dev/LuCLI/pull/19) (issue [#17](https://github.com/wheels-dev/LuCLI/issues/17)) | A module function run by an MCP `tools/call` receives the runtime-owned `__lucliMcpCall=true` argument; any caller-supplied argument of that name is dropped, and a terminal call never carries it |

Upstream PR to `cybersonic/LuCLI` (row 8): maintainer approved (2026-10-02); opened as a
`cherry-pick -x` onto upstream `main` once #19 is reviewed and green. Until upstream merges
it, it is a documented fork patch. Module authors whose argument validation rejects unknown
keys must accept or remove `__lucliMcpCall` (Wheels: wheels-dev/wheels#3980).

## Approved patch mapping

Stable patch IDs (original → assembled); a differing ID below is only diff
context: the ordered added/deleted lines are byte-identical.

| PR | Original → assembled commit | Original patch ID | Assembled patch ID |
|---|---|---|---|
| #123 | 74ee90c → ba87514 | 0b54fb96867c62b13d4e1560192c58aa720c8877 | same |
| #124 | ef658f1 → 7348c4a | 2d7f98dbf49415e2eff4b0fd26808cb340acc1c9 | 014835faed6b8ee8528eb85794c6dc8bbf50a31d |
| #128 | caf43f0 → e9a5ad6 | a0cd68187bf61f4c1009ab8cb6c526bb91fe12e2 | same |
| #129 | 0b4f0c7 → 240249a | ef228b5abaa0694c924b64582a52826837ef13d4 | same |
| #130 | 4c124ba → 0a5ef0d | 8372a5a675d808813bbf5802d5deeb2860e0fb0b | f9dcdb6e08e47c8ee7fdd99015a2a994e09e3c3a |
| #130 cluster fix | 944f230 → 64eceac | f182b55ce1cbf22d62dbde72deb9f56eb18faee4 | same |

## Rebase onto an upstream release

1. Fetch upstream and origin; confirm clean status. Record the current `wheels`
   SHA and create a local backup branch before rewriting anything.
2. Start `peter/rebase-<version>` at the new upstream tag. Inspect every queue PR
   and drop changes already present in that tag (merged on main alone is not
   sufficient). Cherry-pick the remaining patches in the documented order with
   `git cherry-pick -x`. Keep original provenance when resolving conflicts.
3. Reapply fork operational changes, update this table, and set the Maven version
   to `<new-upstream>.1`. Windows file/product version metadata uses that same
   four-part version.
4. Run `mvn clean test`, the BATS integration suite, real MCP/CLI probes, and
   Wheels CLI/distribution tests using the built candidate. Inspect test totals,
   not just process exits; record skipped and unchecked paths. Resolve failures.
5. Review the accumulated diff and `git range-diff` against the old queue. Get
   independent review of changed behavior and release routing. Update `wheels`
   with an explicitly checked `--force-with-lease` only when a rebase requires it.
   Never rewrite published tags or replace release assets.

## Release

The fork's **Release** workflow is manual and publishes only GitHub assets in
`wheels-dev/LuCLI`, from `wheels`. Its default is a build-only dry run with retained artifacts. It uses that repository's `GITHUB_TOKEN`; it
must not invoke upstream JReleaser full-release, Docker Hub, Homebrew, Scoop,
Pages, or another registry. Scheduled publishing workflows remain disabled.

1. Merge the reviewed candidate into `wheels`; confirm its exact SHA and Maven
   version. Confirm the tag does not exist and all required checks pass.
2. First dispatch `gh workflow run release.yml --repo wheels-dev/LuCLI --ref <candidate-branch> -f publish=false`.
   This runs CI and retains candidate artifacts without creating a tag/release.
   Run `install-validation.yml` with that dry-run ID and version to verify the retained assets on Linux, macOS, and Windows (including the EXE and ZIP payload). Test those assets with strict Wheels CLI tests and real stdio MCP probes;
   get both independent reviewers to clear the full assembled diff, runbook,
   and release workflow. Then dispatch
   `gh workflow run release.yml --repo wheels-dev/LuCLI --ref wheels -f publish=true`.
   The workflow runs CI, builds the JAR/Unix launchers/Windows executable, checks
   their version, generates SHA256 checksums, and creates `v<version>` as GitHub Latest at the
   checked-out SHA. A pre-existing tag/release is a hard failure.
3. Inspect the workflow result, tag SHA, asset names, and SHA256 checksums.
   Run `install-validation.yml -f source=published -f version=<version>` against
   the actual published assets on all three OSes, then run the real CLI/MCP
   regression probes. The release rebuilds; dry-run hashes do not certify the
   published bytes. No Wheels channel may move until these checks pass.
4. Update Wheels' single LuCLI repo/version source and the generated active
   package-manager definitions. Verify `distribution-install-smoke` for the
   active brew/Scoop/apt/yum channels after propagation. Chocolatey is retired;
   do not revive it as part of a runtime update.

Known MCP limits retained from upstream: Java `Error` subclasses are outside
`catch(Exception)`, output is not size-capped, and background-thread output
written after stream restoration is not captured.
