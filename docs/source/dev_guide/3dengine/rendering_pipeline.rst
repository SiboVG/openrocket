********************************
Rendering Pipeline
********************************

Renderer and Passes
===================

- ``Renderer`` interface with ``render(SceneView, WindowManager, boolean)``.
- ``RealisticRenderer`` composes a set of ``RenderPass``es:
  - Background (gradient/skybox/HDRI/solid)
  - Geometry pass (objects)
  - Carets pass (CG/CP markers)
  - Post‑processing (FXAA, outlines, motion blur)

Shaders and Textures
====================

Shaders
-------

- ``ShaderProgram`` provides uniform setters and program lifecycle; ``Shader`` is
  the default OpenGL implementation.
- ``RealisticRenderer.ShaderUniforms`` caches uniform locations for performance.

Textures
--------

- ``TextureBinder`` abstracts texture state. ``TextureStateManager`` minimizes
  redundant binds and parameter calls across units.

Materials
=========

- ``Appearance3D`` describes per‑object material (color/specular/roughness/texture/decals).
- ``MaterialBinder`` binds per‑object uniforms and textures. ``DefaultMaterialBinder``
  implements the standard shading.

Particle Systems
================

- ``ParticleSystemRenderer`` renders emitters from ``SceneView`` and the active camera.
- Default implementations:
  - ``ParticleRenderer`` (line streaks; fallback)
  - ``FlameRenderer`` (billboarded quads, flicker, flame texture)
  - ``VolumetricSmokeRenderer`` (billboarded quads with simple dynamic lighting)

Backgrounds
===========

- Background pass supports:
  - Solid color (with checkerboard for transparency)
  - Gradient
  - Skybox (cubemap or atlas)
  - HDRI equirectangular maps

Selection and Outlines
======================

- ``OutlinePass`` renders silhouettes of selected objects, applies edge detection,
  and composites a colored outline over the scene.

Resizing and Framebuffer
========================

- ``RealisticRenderer.resize(width, height)`` rebuilds the main FBO and resizes
  render passes that own textures.
- ``FramebufferAware`` enables platform backends to trigger resize callbacks and
  provide framebuffer sizes distinct from window sizes (HiDPI).

