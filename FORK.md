# Wheels LuCLI patch queue

This fork supplies the LuCLI runtime tested and shipped by Wheels. It carries
upstream bug fixes, not fork-only product features. Do not recreate `bpamiri/LuCLI`:
its GitHub redirect is still used by older installed formulas.

## Branches and versions

- `main` mirrors `cybersonic/LuCLI` main. Never commit fork changes there.
- `wheels` starts at an upstream release tag and carries the reviewed patch queue.
- Work and upstream PR branches use `peter/…`.
- Fork releases use `<upstream>.<n>`: `0.6.1.1`, `0.6.1.2`, etc. Reset the final
  counter for each upstream release. These are releases, not prereleases.

Every behavioral patch must have an upstream PR. Drop a patch when the next
upstream base includes it. A fix merged on upstream main but absent from the
chosen release tag remains a documented backport until the base contains it.
Fork version metadata, this runbook, and fork-only release routing are operational
changes; do not propose those as upstream product features.

## 0.6.1.1 queue

Base: `cybersonic/LuCLI` tag `v0.6.1`.

| Order | Upstream change | Purpose |
|---|---|---|
| 0 | [811284e](https://github.com/cybersonic/LuCLI/commit/811284e171d3f6bd8f9e4b17b3ee66431b52e9f8), dependency pin only | Use released Lucee 7.0.4.34 |
| 1 | [#123](https://github.com/cybersonic/LuCLI/pull/123), `74ee90c` | Aliased binary routes to its module despite a same-named cwd entry |
| 2 | [#124](https://github.com/cybersonic/LuCLI/pull/124), `ef658f1` | Reserved root command tokens remain module positional arguments |
| 3 | [#128](https://github.com/cybersonic/LuCLI/pull/128), `caf43f0` | Preserve captured MCP output when a tool throws (#125) |
| 4 | [#129](https://github.com/cybersonic/LuCLI/pull/129), `0b4f0c7` | Honor false-valued help controls (#126) |
| 5 | [#130](https://github.com/cybersonic/LuCLI/pull/130), `4c124ba` + `944f230` | Forward module timeout arguments (#127) |

Build dependency: upstream backport [811284e](https://github.com/cybersonic/LuCLI/commit/811284e171d3f6bd8f9e4b17b3ee66431b52e9f8)
(`lucee.version` pin only) selects released Lucee `7.0.4.34`; the v0.6.1
`7.0.4.18-SNAPSHOT` dependency is no longer resolvable. This is already merged
upstream and drops on the next upstream rebase that contains it. The upstream
commit's unrelated project-version change and formatting are not backported.

Conflict resolutions preserve the new helpers/tests from both sides. The #123
changelog backport excludes unrelated 0.7.0 release entries.

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
   Test those assets with strict Wheels CLI tests and real stdio MCP probes;
   get both independent reviewers to clear the full assembled diff, runbook,
   and release workflow. Then dispatch
   `gh workflow run release.yml --repo wheels-dev/LuCLI --ref wheels -f publish=true`.
   The workflow runs CI, builds the JAR/Unix launchers/Windows executable, checks
   their version, generates SHA256 checksums, and creates `v<version>` at the
   checked-out SHA. A pre-existing tag/release is a hard failure.
3. Inspect the workflow result, tag SHA, asset names, and SHA256 checksums.
   Download the published assets and run the real CLI/MCP regression probes.
4. Update Wheels' single LuCLI repo/version source and the generated active
   package-manager definitions. Verify `distribution-install-smoke` for the
   active brew/Scoop/apt/yum channels after propagation. Chocolatey is retired;
   do not revive it as part of a runtime update.

Known MCP limits retained from upstream: Java `Error` subclasses are outside
`catch(Exception)`, output is not size-capped, and background-thread output
written after stream restoration is not captured.
