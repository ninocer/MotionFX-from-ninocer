# MotionFX Plugin API 1

Status: implemented **declarative effect presets**, not general executable plugins.
No compatibility with Adobe `.aex` or `.plugin` binaries.

`.mfxplugin` is a ZIP containing exactly one entry named `manifest.json`.
Maximum uncompressed manifest size: 64 KiB. Extra files, path traversal entries,
code, native libraries, shader files and duplicate entries are rejected. Nothing
is extracted into the filesystem. Arbitrary GLSL is deliberately unsupported in
v1; future shader support requires resource budgets, GPU watchdog testing, and a
separate API version. Transitions and standalone animation presets are future API
capabilities and are not advertised as supported by the manager.

Required fields:

| Field | Contract |
| --- | --- |
| api | Exactly 1 |
| id | 3–64 lowercase ASCII letters, digits, dot/hyphen; starts with letter |
| name | 1–80 characters |
| version | Three numeric dot-separated components |
| description | Up to 2048 characters |
| minAppVersion | Application version code; must be at most 1 |
| dependencies | Must be empty in API 1; nonempty dependencies fail installation |
| effects | 1–8 objects with `kind` and `amount` |

Supported effects: `brightness`, `contrast`, `saturation`, `tint`. Amount is a
finite number in [-1,1]. Effects execute in declared order through the same
color-matrix compositor used by preview, PNG and video export.

Install through **Plugins → Install .mfxplugin**. Enable/disable controls whether
the package can be applied to a layer. Applying materializes editable effect
instances in the document. Existing instances remain independent of the package;
disable an individual instance in the Effects tab to stop its rendering. Remove
uninstalls the package without damaging documents. Inactive packages allocate no
render resources; only small metadata is loaded by the manager.

Example: `examples/violet-film.mfxplugin`, source `examples/manifest.json`.
The parser's unit tests cover schema, API compatibility, instantiation, oversized
ZIP entries, path traversal and executable payload rejection.
