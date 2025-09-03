********************************
Scene and Controllers
********************************

Scene Model
===========

``core.scene.info.openrocket.swing.gui.figure3d.Scene`` holds:

- Objects (``SceneObject``) with geometry (``Mesh`` → ``RenderableMesh``), transforms,
  selection state, and ``Appearance3D`` materials.
- Camera (``Camera``) with projection/view, orbit/pan/dolly helpers.
- Lights (via ``LightController``), background, fog settings.
- Particle emitters.
- Selection list and listeners.

Read‑only View
--------------

- ``SceneView`` exposes getters only and is implemented by ``Scene``.
- All renderers/passes accept ``SceneView``.

Selection
---------

- ``Scene.updateSelection(Raycaster, boolean isMultiSelect)`` updates selection based
  on ray intersections; components are grouped by their ``RocketComponent``.
- ``SelectionListener`` can be registered to get notified when the selected list changes.

Controllers
===========

CameraControls
--------------

- ``CameraControls`` interface and default ``CameraController`` support
  ``initialize``, ``focusOnRocket``, ``handleOrbit/pan/scroll``, and ``resize``.

Scene Input Processing
----------------------

- ``SceneInputProcessor`` interface and ``DefaultSceneInputProcessor`` translate
  input state (mouse deltas, clicks, modifiers) to:
  - Orbit/pan/zoom
  - Selection and double‑click actions
  - Light manipulation (dragging primary light direction)

Lighting
--------

- ``LightController`` interface provides light add/get/set/remove and optional visualizers.
- ``LightManager`` implements ``LightController`` and can attach light visualizer objects
  into the scene.

Events
======

SelectionListener
-----------------

.. code-block:: java

   scene.addSelectionListener(selected -> {
       // update UI, enable tools, etc.
   });

ExportListener
--------------

.. code-block:: java

   orchestrator.addExportListener(transparent -> {
       // hook export actions or UI notifications
   });

