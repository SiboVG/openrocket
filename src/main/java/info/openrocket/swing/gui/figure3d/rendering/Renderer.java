package info.openrocket.swing.gui.figure3d.rendering;


import info.openrocket.swing.gui.figure3d.scene.core.SceneView;
import info.openrocket.swing.gui.figure3d.window.WindowManager;

/**
 * Core interface for the OpenRocket 3D rendering system.
 * 
 * Defines the contract for rendering engines that transform 3D scene data into
 * 2D output. Implementations handle the complete rendering pipeline including
 * geometry rendering, lighting, materials, post-processing, and particle systems.
 */
public interface Renderer {
	/**
	 * Renders a single frame of the given scene to the active framebuffer.
	 * 
	 * This method performs the complete rendering pipeline including geometry
	 * rendering, lighting calculations, material application, particle systems,
	 * and post-processing effects.
	 * 
	 * @param scene The scene containing geometry, lights, camera, and particle systems to render
	 * @param windowManager The window manager for viewport and context information
	 * @param renderBackground Whether to render the scene background or leave it transparent
	 */
    void render(SceneView scene, WindowManager windowManager, boolean renderBackground);

	/**
	 * Cleans up all rendering resources and releases GPU memory.
	 * 
	 * This method should be called when the renderer is no longer needed.
	 * It releases all OpenGL resources including shaders, buffers, textures,
	 * and framebuffer objects.
	 */
	void cleanup();
}
