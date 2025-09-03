********************************
Input and Windowing
********************************

Window Backends
===============

``WindowManager`` abstracts the window and GL context. Optional capability
interfaces let consumers opt‑in to platform specifics:

- ``FramebufferAware``: ``getFramebufferSize()``, resize callback
- ``CursorQuery``: cursor position, mouse button state
- ``KeyboardEventSource``: stream key events to a ``KeyboardListener``

Default: ``GLFWWindowManager`` implements all of the above for LWJGL/GLFW.

.. note::

   Use ``getWindowSize()`` for window dimensions. ``getWidth()/getHeight()`` are
   still available but deprecated to prefer a single source of truth.

Keyboard
========

Low‑level events
----------------

- ``KeyboardListener`` receives key press/release (GLFW codes), typically wired by
  the window backend.

Action mapping
--------------

- ``KeyBindings`` and ``KeyboardHandler``: map actions to keys (single‑press and
  press‑and‑hold). Call ``handleQueuedEvents()`` each frame to execute actions.

Mouse
=====

Two input paths feed the same ``InputState`` and converge through
``SceneInputProcessor``:

- GLFW path: ``input.info.openrocket.swing.gui.figure3d.MouseInputHandler`` configured via GLFW callbacks.
- AWT path: ``ui.info.openrocket.swing.gui.figure3d.GLScenePanel`` registers Swing listeners.

InputState
----------

The shared ``InputState`` stores processed mouse deltas, click/double‑click points,
scroll deltas, and mode flags (panning/light dragging). The scene input processor
consumes this state during ``update()``.

