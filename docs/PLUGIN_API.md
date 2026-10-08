# MotionFX Plugin API 1.0.0

This is a **data-only API**, not a native binary SDK. MotionFX 0.1.0 targets Android 13+.
`.aex`, Adobe `.plugin`, JavaScript, DEX, JAR, native shared objects and raw GLSL are not supported.
No compatibility with After Effects plugins is implemented or claimed.

## Package

`.mfxplugin` is a ZIP containing exactly two root entries: `manifest.json` and `content.json`.
No folders, additional files, executable payloads, network resources or external asset references.
The installer parses in memory; it never extracts paths from the archive.

Limits: compressed input 256 KiB; expanded manifest 32 KiB; expanded content 128 KiB;
JSON nesting 32; 16 contributions/package; 8 operations/contribution; 16 parameters;
128 keyframes; 16 dependencies. IDs match `[a-z][a-z0-9_-]*(\.[a-z][a-z0-9_-]*)*`, max 96 chars.
Unknown JSON properties, enum values, nonfinite numbers and out-of-range parameters are rejected.

Manifest example (the packaging tool computes `contentSha256`):

```json
{
  "formatVersion": 1,
  "id": "studio.example.amber",
  "name": "Amber",
  "version": "1.0.0",
  "description": "A warm tint",
  "author": "Example Studio",
  "api": {"min": "1.0.0", "maxExclusive": "2.0.0"},
  "application": {"min": "0.1.0", "maxExclusive": "1.0.0"},
  "dependencies": [],
  "contentSha256": "64 lowercase hexadecimal characters"
}
```

`dependencies` entries: `{"id":"studio.other.plugin","versions":{"min":"1.0.0","maxExclusive":"2.0.0"}}`.
Dependencies constrain lifecycle/compatibility; they do not give access to another package's private state or code.
Versions use stable numeric `major.minor.patch` only. Ranges include `min` and exclude `maxExclusive`.
Pre-release and build metadata are deliberately unsupported in v1. Breaking semantics require an API major bump.

## Contributions

`content.json` contains `{"contributions":[...]}`. A contribution has `id`, `name`, `kind`,
and optional `parameters`, `operations`, `keyframes` arrays (empty by default).

| Kind | Definition | Runtime behavior |
| --- | --- | --- |
| `EFFECT` | 1–8 operations | Fused per-pixel CPU evaluation |
| `GPU_SHADER` | Same restricted operations | Host-generated GLES3 shader, CPU fallback on unavailable/lost GPU |
| `TRANSITION` | No operations, parameters or keys | Alpha-correct crossfade of two equally sized frames; explicit progress 0–1 |
| `PRESET` | 2–128 opacity keyframes | Multiplies alpha by the sampled opacity at explicit composition time |

Example custom effect:

```json
{
  "contributions": [{
    "id": "amber",
    "name": "Amber tint",
    "kind": "EFFECT",
    "parameters": [{"id":"strength","name":"Strength","min":0.0,"max":1.0,"default":0.65}],
    "operations": [{"type":"TINT","amount":{"parameter":"strength"},"color":[1.0,0.75,0.4]}]
  }]
}
```

Each operation has `type` and `amount`. Amount is either `{"value":0.5}` or
`{"parameter":"strength"}`. A parameter reference must exist, and its entire range must fit
the operation's range. Overrides are finite and clamped to the declared parameter range;
unknown override names produce a visible bypass diagnostic. The optional `color` is three values in 0–1,
default `[1,1,1]`; only TINT uses it.

| Operation | Amount | Formula for encoded sRGB RGB |
| --- | --- | --- |
| `TINT` | 0–1 | `rgb *= mix(1, color, amount)` |
| `EXPOSURE` | -4–4 | `rgb *= 2^amount` |
| `CONTRAST` | 0–2 | `rgb = (rgb - 0.5) * amount + 0.5` |
| `INVERT` | 0–1 | `rgb = mix(rgb, 1-rgb, amount)` |
| `VIGNETTE` | 0–1 | `rgb *= 1 - amount * clamp(2*dot(uv-.5,uv-.5),0,1)` |

RGB is clamped after each operation; effects preserve alpha. Frames use straight-alpha ARGB8,
encoded sRGB, row-major top-left origin. Vignette coordinates use pixel centers.
GPU output may differ from CPU by one 8-bit channel unit due to floating-point rounding.
Crossfade interpolates premultiplied color and alpha, then converts back to straight alpha.

Preset keyframes contain `timeSeconds`, `value`, `interpolation` (default `LINEAR`).
Times start at zero, strictly increase, and end by 60 seconds; values are opacity in 0–1.
`LINEAR`, `HOLD`, `EASE_IN_OUT` (smoothstep) apply to the segment starting at the key.
Sampling before/after the key range returns the endpoint. No wall-clock time, randomness, expressions or loops.

## Host API and lifecycle

The platform-neutral `plugin-api` Gradle module exposes:

- `PackageReader.read(InputStream)` / `decode(ByteArray)` — bounded parsing, integrity and schema validation.
- `PluginManager(directory, appVersion)` — `install`, `uninstall`, `enable`, `disable`, `list`.
- `resolve(PluginReference)` / `contributions(id)` — lazy graph access, only for enabled compatible packages.
- `FrameEngine.render(...)` — one pipeline for PREVIEW and EXPORT, explicit time, optional second frame/progress.
- `GpuBackend` — trusted host implementation boundary, never provided by an untrusted package.
- `ShaderCompiler` — finite host templates; raw GLSL injection is not an API.
- `ProjectStore` — atomic `.mfx` snapshots with last-good backup and recovery.

New installs start disabled. Enable verifies the stored package hash and requires enabled dependencies
with compatible versions. Cycles are rejected even when packages are disabled. Disable is blocked while an enabled
dependent needs the package; uninstall is blocked while any installed dependent references it.
An installed ID cannot be overwritten: remove it explicitly before installing another version.
Projects pin exact versions so a replacement cannot silently change visuals.

Inactive packages retain only small manifest metadata; they have no loaded effect graphs, shaders, textures,
threads, timers, services or class loaders. Enabled graphs load on demand. Disable/uninstall evicts graphs.
The Android host creates GPU resources only for an actual GPU render and closes them after the preview/export operation.
The registry is limited to 128 packages; a composition to 16 contributions; a frame to 4,194,304 pixels.
The sample editor uses 640×360 to keep the complete workflow bounded; this is not a claim of 4K/60 performance.

## Failure and storage semantics

Installation and registry changes use same-directory temporary files, fsync, and atomic rename.
A prior registry generation and its package files remain recoverable; uninstall removes the active registration immediately,
but a package referenced by that one backup generation is retained until a later uninstall cleanup.
Project snapshots preserve every plugin reference and parameter, including absent/disabled/incompatible plugins.
The renderer does not write project storage. Missing or failed contributions bypass with `PluginIssue`, retaining
the preceding frame; other contributions still render. Corrupted installed packages are quarantined for the session
and evicted from the graph cache. Re-enable rechecks integrity. GPU context/compile failures use CPU fallback with a notice.
No broad suppression of fatal VM errors is attempted. Recovery relies on persisted project snapshots.

If primary project/registry JSON is corrupt, the valid backup is restored without replacing it with the corrupt file.
If both copies are invalid, loading fails visibly and preserves the files. Private imported images use unique immutable
filenames, so a previous project backup still refers to the correct media. No deletion of project refs occurs on uninstall.

## Trust model and future library

SHA-256 verifies integrity, **not publisher identity**. V1 has no package signature or store certification mechanism.
Every package, including locally selected files, is treated as untrusted data. Validation is mandatory on installation
and lazy load. No reflection, dynamic class loading, script engine, subprocess, native-code loading, network access,
filesystem capability or Android permission can be requested by a package. This respects Android's constraints on
dynamically downloaded executable code. GLES code is emitted by the installed application from a bounded graph.

A future catalog can provide publisher metadata, package URLs, hashes, review and signed attestations outside
the installed package format. It should download to a bounded stream and call the same installer, verify publisher
attestations before presenting trust badges, and use a new versioned contract for new capabilities. Never bypass
PackageReader or turn a catalog download into a class loader. Registry storage is local and offline-capable;
no account, advertising SDK, network permission or catalog service is needed for the current workflow.

## Author and test a package

Edit `examples/starter/manifest.json` and `content.json`, then in cloud:

```sh
python3 tools/package_plugin.py examples/starter examples/starter.mfxplugin
python3 tools/package_plugin.py examples/starter app/src/main/assets/starter.mfxplugin
./gradlew :plugin-api:test
```

The packer writes deterministic ZIPs and computes content integrity; Android/JVM validation is authoritative.
Install the file through the app's system document picker, enable it, and add a contribution.
JVM tests cover package abuse, lifecycle, dependency constraints, versioning, rendering, fallback and persistence.
Android instrumentation tests execute generated GLES shaders and encode/decode a real MP4.
