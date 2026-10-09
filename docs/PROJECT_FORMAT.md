# MotionFX document format v1

`.mfx` is UTF-8 JSON; see `core/Project.kt` for the authoritative serializable schema.
`version: 1` is mandatory; unsupported versions are rejected. IDs are bounded safe
filenames. Width/height are even pixels, 16–3840. FPS is 24, 30 or 60. Duration is
milliseconds, up to one hour. Limit: 32 layers, 32 effects per layer, 10,000 keys
per property. These are defensive limits, not performance promises.

Layers are bottom-to-top. Visibility uses the half-open interval `[start,end)`.
`source` is a persisted Android Storage Access Framework URI, not an embedded
asset. Deleting/revoking a source makes rendering fail with a visible error.
Documents transferred between devices need their original URI permissions;
portable asset packaging/relinking is not implemented yet.

Video source time = `sourceIn + (compositionTime - start) * speed`.
Keyframe times are absolute composition milliseconds; splitting preserves
animation and source continuity. X/Y are fractions of composition size; scale
is relative to fit-to-composition; rotation is degrees. Opacity is 0–1.
The first key's interpolation applies until the next key. Cubic Bezier solves
its time axis by 24 deterministic bisection steps; control X values are 0–1.
Duplicate key timestamps are replaced by editor operations and rejected on load.

Effect parameters are animated Tracks. Implemented kinds: brightness, contrast,
saturation, tint, with parameter range -1–1. Unknown effects fail visibly.
Plugin instances are stored as native effect snapshots, so uninstalling a plugin
does not break a saved composition or run untrusted code.

Projects are written via Android AtomicFile, which retains a valid backup during
replacement. Autosave is debounced 350 ms and flushed on activity stop. A sudden
power loss within that debounce window can lose the last unsaved edit. Project
files are app-private; uninstall deletes them. Export `.mfx` for user backups.
