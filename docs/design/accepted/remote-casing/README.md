# Remote Casing — accepted opaque state

Status: **accepted and active**

- `remote_casing_opaque.png` is the approved 16×16 texture for the unpowered,
  opaque state.
- The same texture is copied to the runtime resource path at
  `assets/distantstock/textures/block/remote_casing.png`.
- `connected_wall_reference.png` records how the face should read when casings
  merge into a larger wall. It is a visual target, not a runtime atlas.
- `preview.png` is the approval render.

The powered transparent state remains a separate design and implementation
task. Its outer frame must remain visible while only the centre becomes
emissive and transparent.

The superseded V7.4 texture is retained under
`docs/design/archive/remote-casing/v7.4/` for provenance only. Do not reference
that archive from block models, generated resources, or runtime code.
