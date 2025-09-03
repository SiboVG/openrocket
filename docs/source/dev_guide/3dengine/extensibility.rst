********************************
Extensibility and Integration
********************************

Replaceable Components
======================

Renderers
---------

- Implement ``Renderer`` to provide an alternative rendering strategy (headless,
  debug, etc.). The orchestrator exposes the renderer via ``getRenderer()``.

Raycasting
----------

- Implement ``Raycaster`` to swap picking (e.g., BVH‑accelerated). Inject into
  ``DefaultSceneInputProcessor``.

Materials
---------

- Implement ``MaterialBinder`` to change how object uniforms and textures are
  bound (wireframe‑only, PBR, multi‑layer decals).

Textures
--------

- Implement ``TextureBinder`` for custom texture handling (bindless, atlases).

Controllers
-----------

- Implement ``CameraControls`` (FPS, trackball) and ``SceneInputProcessor``
  (alternate interaction models).

Window Backends
---------------

- Implement ``WindowManager`` and the capability interfaces to target other
  platforms or to simulate input in tests.

Usage Patterns
==============

Access patterns
---------------

- Prefer ``SceneView`` when reading scene state in rendering/UI.
- Prefer interface getters: ``getLightController()``, ``getRenderer()``, and
  ``getWindowSize()``.

Events
------

- Register ``SelectionListener`` with ``Scene``.
- Register ``ExportListener`` with ``Scene3DOrchestrator``.

Testing Tips
============

- Mock or stub ``ShaderProgram`` and ``TextureBinder``.
- Use a headless ``Renderer`` that records calls for CI without GL context.
- Prefer ``SceneView`` snapshots when verifying pass logic.

