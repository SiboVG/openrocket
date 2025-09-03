********************************
Engine Architecture (Java/LWJGL)
********************************

Overview
========

The engine is organized around clear, testable contracts. Rendering depends on a
read‑only scene contract (``SceneView``) and small interfaces for shaders,
textures, input, and window backends. The ``Scene3DOrchestrator`` composes
these parts and drives updates.

Core Modules
============

Scene and Orchestrator
----------------------

- ``core.scene.info.openrocket.swing.gui.figure3d.Scene``: Mutable scene model (objects, camera, lights,
  background, fog, selection). Implements ``SceneView`` for read‑only consumers.
- ``core.scene.info.openrocket.swing.gui.figure3d.SceneView``: Read‑only view of the scene (used by
  renderers and UI).
- ``orchestration.scene.info.openrocket.swing.gui.figure3d.Scene3DOrchestrator``: Wires up scene,
  controllers, and renderer, runs per‑frame ``update()``, exposes ``getRenderer()``.

Controllers
-----------

- ``CameraControls`` (interface) and ``CameraController``: Camera behavior and
  view management.
- ``SceneInputProcessor`` (interface) and ``DefaultSceneInputProcessor``: Translates
  input state to scene operations (selection, orbit/pan/zoom, light manipulation).
- ``LightController`` (interface) and ``LightManager``: Store, query, and visualize
  lights in the scene.

Rendering
---------

- ``Renderer`` (interface) and ``RealisticRenderer``: Frame rendering built from
  modular ``RenderPass`` components.
- ``RenderPass`` (interface): Background, geometry, and post‑processing passes.
- ``ShaderProgram`` (interface) and ``Shader``: GPU program abstraction.
- ``TextureBinder`` (interface) and ``TextureStateManager``: Optimized texture
  binding and parameter caching.
- ``MaterialBinder`` (interface) and ``DefaultMaterialBinder``: Apply per‑object
  uniforms and bind textures before draw.

Input and Windowing
-------------------

- ``WindowManager`` (interface) with capability interfaces:
  ``FramebufferAware``, ``CursorQuery``, ``KeyboardEventSource``; ``GLFWWindowManager``
  implements them for LWJGL/GLFW.
- ``KeyboardListener`` (interface): Low‑level key events from the window backend.
- ``KeyBindings`` (interface) and ``KeyboardHandler``: Action mapping and queued
  processing each frame.
- Mouse paths (dual):
  - GLFW path: ``input.info.openrocket.swing.gui.figure3d.MouseInputHandler`` via GLFW callbacks.
  - AWT path: ``ui.info.openrocket.swing.gui.figure3d.GLScenePanel`` via Swing listeners.
  Both write the shared ``InputState`` and converge on ``SceneInputProcessor``.

Data Flow
=========

1) Window backend creates a GL context and sets callbacks (GLFW) or Swing listeners (AWT).
2) Orchestrator builds the scene, controllers, and renderer and sets initial camera.
3) Each frame ``Scene3DOrchestrator.update()``:
   - Processes input (selection, orbit/pan/zoom, light manipulation)
   - Updates camera matrices and particle systems
   - Advances playback when bound to a ``PoseProvider``
4) ``Renderer.render(SceneView, WindowManager, renderBackground)`` draws passes
   and post‑processing, then composites to the screen.

