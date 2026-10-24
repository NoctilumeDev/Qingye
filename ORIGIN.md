# Repository origin

Qingye was originally developed under [`NoctilumeDev/dome`](https://github.com/NoctilumeDev/dome) at `qingye/`.

The standalone repository was extracted from:

- source repository: `NoctilumeDev/dome`
- source coordinate: `dbb32160a811fd06c826a8d9eafbc660a0548219`
- source path: `qingye/`
- last source commit affecting that path before extraction: `b94a32e3d8e1ea995026caf8bb68e10a7084576f`
- authority cutover date: `2026-10-23`

The imported Git history preserves the Qingye-related sequence and authorship, but subtree extraction rewrites commit identities because `qingye/` becomes the new repository root. Exact historical evidence, GitHub Actions runs, and the original browser-showroom route remain owned by `dome` at their original coordinates.

From the authority cutover onward, this repository is the canonical development location for Qingye. The copy retained in `dome` is historical and must not receive new product changes.

The existing browser demonstration remains a compatibility projection at <https://noctilumedev.github.io/dome/>. It is not evidence that the Spring Boot backend, MySQL state, native WeChat build, real device behavior, or model-backed assistant ran successfully.

## Four-gate branch integration (2026-10-24)

The original extraction above preserved dome's then-main history, but did not include
its unmerged Qingye development branch. The eight Qingye-only commits after
`dbb32160a811fd06c826a8d9eafbc660a0548219` through
`45e7d4ffb4f5e1525a7b18d8b4d6e9cbc05f2f48` are now ported onto standalone
Qingye's `188786148e966066eb0935c91415c27272ab4ece` integration base.
The `qingye/` prefix is removed; authorship and commit order are retained.
Standalone governance, dependency upgrades and their evidence are preserved.
See [integration reconciliation](docs/four-gate-integration.md) for the mapping,
adaptations and validation. Source-branch model experiments remain historical
observations and do not qualify the integrated build automatically.
The dome repository and its branches are not modified by this integration.
