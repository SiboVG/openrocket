package info.openrocket.swing.gui.figure3d.ui;

import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.swing.gui.figure3d.DemoFactory;
import info.openrocket.swing.gui.figure3d.core.geometry.RocketMeshBuilder;
import info.openrocket.swing.gui.figure3d.export.ImageExporter;
import info.openrocket.swing.gui.figure3d.export.PngExporter;
import info.openrocket.swing.gui.figure3d.input.KeyboardHandler;
import info.openrocket.swing.gui.figure3d.materials.Texture;
import info.openrocket.swing.gui.figure3d.rendering.Shader;
import info.openrocket.swing.gui.figure3d.rendering.backgrounds.SolidColorBackground;
import info.openrocket.swing.gui.figure3d.scene.core.SceneView;
import info.openrocket.swing.gui.figure3d.scene.orchestration.Scene3DOrchestrator;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.awt.AWTGLCanvas;
import org.lwjgl.opengl.awt.GLData;
import org.lwjgl.system.MemoryUtil;

import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL11.GL_BLEND;
import static org.lwjgl.opengl.GL11.GL_CULL_FACE;
import static org.lwjgl.opengl.GL11.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL11.glBlendFunc;
import static org.lwjgl.opengl.GL11.glDisable;
import static org.lwjgl.opengl.GL11.glDrawArrays;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glTexSubImage2D;
import static org.lwjgl.opengl.GL11.glViewport;
import static org.lwjgl.opengl.GL12.GL_BGRA;
import static org.lwjgl.opengl.GL12.GL_UNSIGNED_INT_8_8_8_8_REV;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.GL_STATIC_DRAW;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL15.glBufferData;
import static org.lwjgl.opengl.GL15.glDeleteBuffers;
import static org.lwjgl.opengl.GL15.glGenBuffers;
import static org.lwjgl.opengl.GL20.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL20.glVertexAttribPointer;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_SRGB;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL30.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL30.glGenVertexArrays;

/**
 * AWTGLCanvas-backed scene panel for Swing integration.
 *
 * Note: There are two input paths in this codebase.
 * - AWT path (this class): registers Swing listeners and writes into the shared InputState via the orchestrator.
 * - GLFW path: {@code input.info.openrocket.swing.gui.figure3d.MouseInputHandler} with GLFW callbacks.
 * Both paths converge on the same SceneInputProcessor so the interaction model is consistent.
 */
public class GLScenePanel extends AWTGLCanvas {

	private Scene3DOrchestrator scene3DOrchestrator;
	private final KeyboardHandler keyboardHandler;

	private static final double CLICK_DRAG_THRESHOLD_SQ = 5 * 5;

	private final HUDPanel hudPanel;
	private BufferedImage hudImage;
	private Texture hudTexture;
	private Shader hudShader;
	private int hudVao;
	private int hudVbo; // Store VBO reference for cleanup
	private ByteBuffer hudImageBuffer;
	private IntBuffer hudIntBuffer; // Direct IntBuffer view for efficiency
	private Graphics2D hudGraphics; // Reusable Graphics2D context

	// Track dimensions to detect actual size changes
	private int lastFramebufferWidth = -1;
	private int lastFramebufferHeight = -1;
	private boolean hudNeedsUpdate = true;

	// Camera tracking for HUD updates
	private boolean cameraIsMoving = false;

	// Resize debouncing
	private volatile boolean resizePending = false;
	private javax.swing.Timer resizeTimer;

	private final Rocket rocket;

	public GLScenePanel(Rocket rocket, HUDPanel hudPanel) {
		super(createGLData());

		this.rocket = rocket;
		this.hudPanel = hudPanel;
		this.keyboardHandler = new KeyboardHandler();

		this.addComponentListener(new ComponentAdapter() {
			@Override
			public void componentResized(ComponentEvent e) {
				if (scene3DOrchestrator == null) return;

				runInContext(() -> {
					int fbWidth = getFramebufferWidth();
					int fbHeight = getFramebufferHeight();
					int width = getWidth();
					int height = getHeight();

					// Immediately update viewport and camera for proper rendering
					glViewport(0, 0, fbWidth, fbHeight);
					scene3DOrchestrator.resize(width, height, fbWidth, fbHeight);

					// Only defer HUD texture recreation
					if (fbWidth != lastFramebufferWidth || fbHeight != lastFramebufferHeight) {
						resizePending = true;

						// Cancel any existing timer
						if (resizeTimer != null) {
							resizeTimer.stop();
						}

						// Start timer for HUD texture update
						resizeTimer = new javax.swing.Timer(100, evt -> {
							if (resizePending) {
								runInContext(() -> {
									initHudTexture();
									lastFramebufferWidth = getFramebufferWidth();
									lastFramebufferHeight = getFramebufferHeight();
									hudNeedsUpdate = true;
									resizePending = false;
								});
							}
						});
						resizeTimer.setRepeats(false);
						resizeTimer.start();

						// Mark HUD for immediate update to show caret in new position
						hudNeedsUpdate = true;
					}
				});
			}
		});
	}

	private void addInputListeners() {
		if (scene3DOrchestrator == null) return;

		MouseAdapter mouseAdapter = new MouseAdapter() {
			private Point pressPoint;
			private Point lastPoint;
			private boolean isDragging;

			@Override
			public void mousePressed(MouseEvent e) {
				if (SwingUtilities.isLeftMouseButton(e)) {
					// Use Swing's built-in click count to detect double-clicks.
					if (e.getClickCount() == 2) {
						scene3DOrchestrator.getInputHandler().getInputState().doubleClickPoint.set(e.getPoint());
					}
					pressPoint = e.getPoint();
					lastPoint = e.getPoint();
					isDragging = false;
					cameraIsMoving = true; // Start tracking camera movement
				}
			}

			@Override
			public void mouseReleased(MouseEvent e) {
				if (SwingUtilities.isLeftMouseButton(e)) {
					// We check for !isDragging to differentiate a click from a drag-release.
					// A double-click will also fire this event for the second click.
					if (!isDragging && pressPoint != null) {
						scene3DOrchestrator.getInputHandler().getInputState().clickPoint.set(pressPoint);
					}
					pressPoint = null;
					isDragging = false;
					cameraIsMoving = false; // Stop tracking camera movement
				}
			}

			@Override
			public void mouseDragged(MouseEvent e) {
				if (SwingUtilities.isLeftMouseButton(e) && pressPoint != null) {
					if (!isDragging && pressPoint.distanceSq(e.getPoint()) > CLICK_DRAG_THRESHOLD_SQ) {
						isDragging = true;
					}

					if (isDragging) {
						// Check for modifier keys
						boolean isCtrlDown = (e.getModifiersEx() & MouseEvent.CTRL_DOWN_MASK) != 0;
						boolean isAltDown = (e.getModifiersEx() & MouseEvent.ALT_DOWN_MASK) != 0;

						// Update input state based on modifiers
						var inputState = scene3DOrchestrator.getInputHandler().getInputState();
						inputState.isPanning = isCtrlDown;
						inputState.isLightDragging = isAltDown;

						// Always update the drag delta
						float deltaX = e.getX() - lastPoint.x;
						float deltaY = e.getY() - lastPoint.y;
						inputState.dx += deltaX;
						inputState.dy += deltaY;
					}
					lastPoint = e.getPoint();
				}
			}

			@Override
			public void mouseWheelMoved(MouseWheelEvent e) {
				scene3DOrchestrator.getInputHandler().getInputState().scrollDelta += e.getWheelRotation() * -1.0f;
				hudNeedsUpdate = true; // Mark HUD for update on zoom
			}
		};
		addMouseListener(mouseAdapter);
		addMouseMotionListener(mouseAdapter);
		addMouseWheelListener(mouseAdapter);

		setFocusable(true);
		addKeyListener(new KeyAdapter() {
			@Override
			public void keyPressed(KeyEvent e) {
				keyboardHandler.handleKeyEvent(e.getKeyCode(), 1);
			}

			@Override
			public void keyReleased(KeyEvent e) {
				keyboardHandler.handleKeyEvent(e.getKeyCode(), 0);
			}
		});
	}

	private static GLData createGLData() {
		GLData data = new GLData();
		data.majorVersion = 3;
		data.minorVersion = 3;
		data.profile = GLData.Profile.CORE;
		data.samples = 4;
		data.sRGB = true;
		data.swapInterval = 1;
		return data;
	}

	@Override
	public void initGL() {
		try {
			GL.createCapabilities();
			glEnable(GL_DEPTH_TEST);
			glEnable(GL_CULL_FACE);
			glEnable(GL_FRAMEBUFFER_SRGB);

			scene3DOrchestrator = Scene3DOrchestrator.builder(rocket, getWidth(), getHeight(), getFramebufferWidth(), getFramebufferHeight())
					.build();
			SceneView scene = scene3DOrchestrator.getScene();

			// Create the scene mesh
			RocketMeshBuilder.buildRocketMesh(scene, rocket, scene3DOrchestrator.getRenderingConfiguration());
			//RocketMeshBuilder.createOriginAxes(scene, true, true);
			scene.setBackground(new SolidColorBackground(0.4f, 0.4f, 0.4f));

			// Focus on the rocket
			scene3DOrchestrator.focusOnRocket();

			if (this.hudPanel != null) {
				this.hudPanel.setSceneViewController(this.scene3DOrchestrator);
				this.hudPanel.setGLScenePanel(this);
			}

			// --- Initialize HUD rendering objects ---
			hudShader = new Shader("/shaders/ui/hud_vertex.glsl", "/shaders/ui/hud_fragment.glsl");

			// Set initial dimensions
			lastFramebufferWidth = getFramebufferWidth();
			lastFramebufferHeight = getFramebufferHeight();

			initHudTexture();
			initHudVao();

			addInputListeners();
			DemoFactory.setupDemoKeyboardHandling(this.keyboardHandler, scene3DOrchestrator.getScene(), scene3DOrchestrator);
		} catch (Exception e) {
			throw new RuntimeException("Failed to initialize renderer", e);
		}
	}

	/**
	 * Cleans up old resources and creates new ones with current framebuffer dimensions.
	 */
	private void initHudTexture() {
		int fbWidth = getFramebufferWidth();
		int fbHeight = getFramebufferHeight();

		if (fbWidth <= 0 || fbHeight <= 0) {
			cleanupHudResources();
			return;
		}

		// Check if we can reuse existing resources with padding
		if (hudImage != null && hudImageBuffer != null) {
			int currentCapacity = hudImageBuffer.capacity();
			int requiredCapacity = fbWidth * fbHeight * 4;

			// If the buffer is big enough, reuse it (allow up to 25% larger)
			if (currentCapacity >= requiredCapacity && currentCapacity <= requiredCapacity * 1.25) {
				// Just update the texture size without reallocating
				if (hudTexture != null) {
					Texture.TexturePool.release(hudTexture);
				}
				hudTexture = Texture.TexturePool.acquire(fbWidth, fbHeight);

				// Recreate BufferedImage with new size but keep buffer
				if (hudGraphics != null) {
					hudGraphics.dispose();
				}
				hudImage = new BufferedImage(fbWidth, fbHeight, BufferedImage.TYPE_INT_ARGB);
				hudGraphics = hudImage.createGraphics();
				hudGraphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				hudGraphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

				return;
			}
		}

		// Full cleanup and recreation needed
		cleanupHudResources();

		// Allocate with some padding to reduce future reallocations
		int paddedSize = (int)(fbWidth * fbHeight * 4 * 1.1); // 10% padding

		hudImage = new BufferedImage(fbWidth, fbHeight, BufferedImage.TYPE_INT_ARGB);
		hudImageBuffer = MemoryUtil.memAlloc(paddedSize);
		hudIntBuffer = hudImageBuffer.asIntBuffer();

		hudGraphics = hudImage.createGraphics();
		hudGraphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		hudGraphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

		hudTexture = Texture.TexturePool.acquire(fbWidth, fbHeight);
	}

	private void cleanupHudResources() {
		if (hudTexture != null) {
			Texture.TexturePool.release(hudTexture);
			hudTexture = null;
		}
		if (hudImageBuffer != null) {
			MemoryUtil.memFree(hudImageBuffer);
			hudImageBuffer = null;
			hudIntBuffer = null;
		}
		if (hudGraphics != null) {
			hudGraphics.dispose();
			hudGraphics = null;
		}
		hudImage = null;
	}

	private void initHudVao() {
		float[] quadVertices = {
				// positions   // texCoords (V coordinates are flipped)
				-1.0f,  1.0f,  0.0f, 0.0f,  // Top-left vertex maps to V=0.0
				-1.0f, -1.0f,  0.0f, 1.0f,  // Bottom-left vertex maps to V=1.0
				1.0f, -1.0f,  1.0f, 1.0f,  // Bottom-right vertex maps to V=1.0

				-1.0f,  1.0f,  0.0f, 0.0f,  // Top-left vertex maps to V=0.0
				1.0f, -1.0f,  1.0f, 1.0f,  // Bottom-right vertex maps to V=1.0
				1.0f,  1.0f,  1.0f, 0.0f   // Top-right vertex maps to V=0.0
		};
		hudVao = glGenVertexArrays();
		hudVbo = glGenBuffers();
		glBindVertexArray(hudVao);
		glBindBuffer(GL_ARRAY_BUFFER, hudVbo);
		glBufferData(GL_ARRAY_BUFFER, quadVertices, GL_STATIC_DRAW);
		glEnableVertexAttribArray(0);
		glVertexAttribPointer(0, 2, GL_FLOAT, false, 4 * Float.BYTES, 0);
		glEnableVertexAttribArray(1);
		glVertexAttribPointer(1, 2, GL_FLOAT, false, 4 * Float.BYTES, 2 * Float.BYTES);
		glBindVertexArray(0);
	}

	@Override
	public void paintGL() {
		if (scene3DOrchestrator == null) return;

		runInContext(() -> {
			// --- 3D Scene Rendering & Export Logic ---
			handleKeyboardEvents();
			glViewport(0, 0, getFramebufferWidth(), getFramebufferHeight());
			scene3DOrchestrator.update();

			if (cameraIsMoving) {
				hudNeedsUpdate = true;
			}

			boolean renderBackgroundForDisplay = true;

			// Check for an export request before the main render
			if (scene3DOrchestrator.isExportRequested()) {
				// Render the scene specifically for the export (with or without background)
				scene3DOrchestrator.getRenderer().render(scene3DOrchestrator.getScene(), null, !scene3DOrchestrator.isExportTransparent());

				// Swap buffers to make the rendered image available for reading
				swapBuffers();

				// Now export the front buffer
				try {
					String filePath = "export_" + System.currentTimeMillis() + ".png";
					ImageExporter exporter = new PngExporter();
					exporter.export(getFramebufferWidth(), getFramebufferHeight(), filePath);
				} catch (IOException e) {
					System.err.println("Failed to export screenshot: " + e.getMessage());
				}

				// Clean up the request
				scene3DOrchestrator.clearExportRequest();

				// Since we just swapped, the background for the live view is now what we exported.
				// We can skip the main display render for this frame to avoid a flicker.
				renderBackgroundForDisplay = false;
			}


			// --- Main Display Rendering (if not exporting) ---
				if (renderBackgroundForDisplay) {
				scene3DOrchestrator.getRenderer().render(scene3DOrchestrator.getScene(), null, true);

				// --- 2D HUD Rendering - only update texture if needed ---
				if (hudNeedsUpdate || hudPanel.needsRepaint()) {
					updateHudTexture();
					hudNeedsUpdate = false;
				}

				if (hudTexture != null) {
					// Set GL state for 2D rendering
					glDisable(GL_DEPTH_TEST);
					glEnable(GL_BLEND);
					glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

					hudShader.use();

					glActiveTexture(GL_TEXTURE0);
					glBindTexture(GL_TEXTURE_2D, hudTexture.getId());

					glBindVertexArray(hudVao);
					glDrawArrays(GL_TRIANGLES, 0, 6);
					glBindVertexArray(0);

					// Restore GL state
					glEnable(GL_DEPTH_TEST);
					glDisable(GL_BLEND);
				}

				// --- Final Swap for display ---
				swapBuffers();
			}
		});
	}

	private void updateHudTexture() {
		if (hudImage == null || hudPanel == null || hudGraphics == null) {
			return;
		}

		// The panel works in logical window coordinates
		int windowWidth = getWidth();
		int windowHeight = getHeight();
		double dpiScale = (double) getFramebufferHeight() / (double) windowHeight;

		// Save current transform
		hudGraphics.setTransform(hudGraphics.getDeviceConfiguration().getDefaultTransform());
		hudGraphics.scale(dpiScale, dpiScale);

		// Clear with transparent background
		hudGraphics.setBackground(new Color(0, 0, 0, 0));
		hudGraphics.clearRect(0, 0, windowWidth, windowHeight);

		// Paint HUD panel
		hudPanel.setBounds(0, 0, windowWidth, windowHeight);
		hudPanel.paint(hudGraphics);

		// Get pixel data directly without creating new arrays
		final int[] pixels = ((DataBufferInt) hudImage.getRaster().getDataBuffer()).getData();

		// Copy directly to IntBuffer view
		hudIntBuffer.clear();
		hudIntBuffer.put(pixels);
		hudImageBuffer.rewind();

		// Update texture
		glBindTexture(GL_TEXTURE_2D, hudTexture.getId());
		glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, hudImage.getWidth(), hudImage.getHeight(),
				GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, hudImageBuffer);
	}

	public void handleKeyboardEvents() {
		if (keyboardHandler != null) {
			keyboardHandler.handleQueuedEvents();
		}
	}

	public void markHudForUpdate() {
		hudNeedsUpdate = true;
	}

	public void cleanup() {
		if (resizeTimer != null) {
			resizeTimer.stop();
			resizeTimer = null;
		}

		runInContext(() -> {
			cleanupHudResources();
			if (hudVao != 0) {
				glDeleteVertexArrays(hudVao);
				hudVao = 0;
			}
			if (hudVbo != 0) {
				glDeleteBuffers(hudVbo);
				hudVbo = 0;
			}
			if (hudShader != null) {
				hudShader.cleanup();
				hudShader = null;
			}
		});
	}
}
