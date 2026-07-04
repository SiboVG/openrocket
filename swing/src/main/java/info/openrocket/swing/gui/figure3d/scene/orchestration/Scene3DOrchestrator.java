package info.openrocket.swing.gui.figure3d.scene.orchestration;

import info.openrocket.core.rocketcomponent.AxialStage;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.util.CoordinateIF;
import info.openrocket.core.startup.Application;
import info.openrocket.swing.gui.figure3d.animation.PlaybackClock;
import info.openrocket.swing.gui.figure3d.animation.PoseProvider;
import info.openrocket.swing.gui.figure3d.math.DefaultRaycaster;
import info.openrocket.swing.gui.figure3d.math.Raycaster;
import info.openrocket.swing.gui.figure3d.input.InputState;
import info.openrocket.swing.gui.figure3d.materials.AppearanceFactory;
import info.openrocket.swing.gui.figure3d.rendering.RealisticRenderer;
import info.openrocket.swing.gui.figure3d.rendering.GLRenderer;
import info.openrocket.swing.gui.figure3d.scene.controllers.CameraController;
import info.openrocket.swing.gui.figure3d.scene.controllers.CameraControls;
import info.openrocket.swing.gui.figure3d.scene.controllers.DefaultSceneInputProcessor;
import info.openrocket.swing.gui.figure3d.scene.controllers.LightController;
import info.openrocket.swing.gui.figure3d.scene.controllers.SceneInputProcessor;
import info.openrocket.swing.gui.figure3d.scene.graph.Camera;
import info.openrocket.swing.gui.figure3d.scene.graph.Light;
import info.openrocket.swing.gui.figure3d.scene.graph.Scene;
import info.openrocket.swing.gui.figure3d.scene.properties.Figure3DPreferences;
import info.openrocket.swing.gui.figure3d.scene.properties.RenderingConfiguration;
import info.openrocket.swing.gui.figure3d.scene.properties.ViewportDimensions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Coordinates scene state, input, synchronization, and rendering for a figure3d view.
 */
public class Scene3DOrchestrator {
	private final Scene scene;
	private final GLRenderer renderer;
	private final RenderingConfiguration renderingConfiguration;
	private final ViewportDimensions viewport;
	private final CameraControls cameraController;
	private final SceneInputProcessor inputHandler;
	private final ConcurrentLinkedQueue<Runnable> glTaskQueue = new ConcurrentLinkedQueue<>();
	private final AtomicBoolean shutdown = new AtomicBoolean(false);
	private final RocketSceneSynchronizer rocketSynchronizer;
	private final AppearanceFactory.DecalTextureCache decalTextureCache = AppearanceFactory.createDecalTextureCache();
	private volatile Runnable glTaskQueuedCallback;

	private long lastFrameTime;
	private volatile PlaybackClock playbackClock = null;
	private volatile PoseProvider flightPrimaryPoseProvider = null;
	private volatile boolean followFlightCamera = false;
	private volatile Map<AxialStage, List<double[]>> flightBurnIntervalsByStage = null;

	/**
	 * Updates the orchestrator's knowledge of the window and framebuffer dimensions.
	 *
	 * <p>This method should be called whenever the viewport is resized to ensure all
	 * components are properly updated with the new dimensions. It handles the distinction
	 * between logical window coordinates and physical framebuffer pixels, which is
	 * important for high-DPI displays.</p>
	 *
	 * @param newWindowWidth The new logical window width in pixels
	 * @param newWindowHeight The new logical window height in pixels
	 * @param newFramebufferWidth The new framebuffer width in pixels
	 * @param newFramebufferHeight The new framebuffer height in pixels
	 */
	public void resize(int newWindowWidth, int newWindowHeight,
			int newFramebufferWidth, int newFramebufferHeight) {
		boolean wasZoomFitting = cameraController.isZoomFitting();

		// Update viewport dimensions
		viewport.update(newWindowWidth, newWindowHeight, newFramebufferWidth, newFramebufferHeight);

		// Propagate resize event to relevant components
		cameraController.resize(viewport.getAspectRatio());
		if (wasZoomFitting) {
			cameraController.focusOnRocket();
		}
		inputHandler.updateDimensions(viewport);
		renderer.resize(viewport.getFramebufferWidth(), viewport.getFramebufferHeight());
		renderer.setDisplayScale(getDisplayScale());
	}

	/**
	 * Automatically positions and orients the camera to frame the entire rocket.
	 *
	 * <p>This method calculates the rocket's bounding box and adjusts the camera
	 * distance and center of interest to provide an optimal view of the complete
	 * rocket geometry.</p>
	 */
	public void focusOnRocket() {
		cameraController.focusOnRocket();
	}

	public void refitOnRocketBoundsChange() {
		cameraController.refitOnRocketBoundsChange();
	}

	/**
	 * Restores the default side view, clears any drag-applied rocket rotation, and fits the rocket.
	 */
	public void resetViewAndFocusOnRocket() {
		scene.resetRocketRotation();
		cameraController.resetView();
		cameraController.focusOnRocket();
	}

	/**
	 * Framebuffer pixels per logical pixel, as reported by the current viewport.
	 *
	 * @return the display scale, 1.0 when the viewport has no usable size yet
	 */
	public float getDisplayScale() {
		int windowHeight = viewport.getWindowHeight();
		if (windowHeight <= 0) {
			return 1.0f;
		}
		return (float) viewport.getFramebufferHeight() / windowHeight;
	}

	/**
	 * Sets where the centre of gravity and centre of pressure markers are drawn.
	 *
	 * <p>The renderer does not work these out itself: the centre of pressure depends on the
	 * flight conditions the user has chosen, which only the owning view knows.</p>
	 *
	 * @param cg centre of gravity, or {@code null}/NaN when there is nothing to show
	 * @param cp centre of pressure, or {@code null}/NaN when there is nothing to show
	 */
	public void setCaretPositions(CoordinateIF cg, CoordinateIF cp) {
		renderer.setCaretPositions(cg, cp);
	}

	/**
	 * Reapplies the persisted rocket drag rotation after the rocket mesh is rebuilt.
	 */
	public void applyRocketRotationToScene() {
		scene.applyRocketRotationToRocketObjects();
	}

	/**
	 * Rebuilds the rocket scene geometry using the current rendering configuration.
	 */
	public void rebuildRocketScene() {
		rebuildRocketScene(true);
	}

	/**
	 * Rebuilds the rocket scene geometry and optionally refocuses the camera.
	 */
	public void rebuildRocketScene(boolean refocusCamera) {
		rocketSynchronizer.rebuildRocketScene(refocusCamera);
	}

	/**
	 * Runs one frame of non-render updates before the caller renders the scene.
	 */
	public void update() {
		runPendingGlTasks();
		long currentFrameTime = System.nanoTime();
		float deltaTime = (currentFrameTime - lastFrameTime) / 1e9f;
		lastFrameTime = currentFrameTime;
		float particleDeltaTime = deltaTime;

		// Process all input events
		inputHandler.processInput();

		// Update camera and scene
		cameraController.update();

		// --- Simulation playback (if bound) ---
		if (playbackClock != null) {
			double previousPlaybackTime = playbackClock.getTime();
			playbackClock.update(deltaTime);
			double t = playbackClock.getTime();
			particleDeltaTime = (float) Math.max(0.0, t - previousPlaybackTime);
			for (var obj : scene.getObjects()) {
				if (obj.hasPoseProvider()) {
					obj.applyPoseAtTime(t);
				}
			}
			for (var emitter : scene.getParticleEmitters()) {
				if (emitter.hasPoseProvider()) {
					emitter.applyPoseAtTime(t);
				}
			}
			PoseProvider primaryProvider = flightPrimaryPoseProvider;
			if (followFlightCamera && primaryProvider != null) {
				cameraController.getCamera().setCenterOfInterest(primaryProvider.getPosition(t));
			}
			updateFlightParticleEmission(t);
		}

		if (particleDeltaTime > 0.0f) {
			scene.updateParticles(particleDeltaTime);
		}
	}

	/**
	 * Gets the managed 3D scene containing all rocket geometry and effects.
	 *
	 * @return The scene instance managed by this orchestrator
	 */
	public Scene getScene() {
		return scene;
	}

	/**
	 * Gets the unified rendering configuration containing all rendering settings.
	 *
	 * @return The rendering configuration instance
	 */
	public RenderingConfiguration getRenderingConfiguration() {
		return renderingConfiguration;
	}

	/**
	 * Exposes the renderer via its interface to reduce coupling.
	 */
	public GLRenderer getRenderer() {
		return renderer;
	}

	public AppearanceFactory.DecalTextureCache getDecalTextureCache() {
		return decalTextureCache;
	}

	/**
	 * Enqueues work that must run on the GL/render thread.
	 */
	public void enqueueGlTask(Runnable task) {
		if (task == null || shutdown.get()) {
			return;
		}
		glTaskQueue.add(task);
		Runnable callback = glTaskQueuedCallback;
		if (callback != null) {
			callback.run();
		}
	}

	/**
	 * Registers a callback that wakes a demand-driven host after GL work is queued.
	 */
	public void setGlTaskQueuedCallback(Runnable callback) {
		this.glTaskQueuedCallback = callback;
	}

	private void runPendingGlTasks() {
		Runnable task;
		while ((task = glTaskQueue.poll()) != null) {
			task.run();
		}
	}

	/**
	 * Detaches listeners and stops accepting new tasks.
	 */
	public void shutdown() {
		if (!shutdown.compareAndSet(false, true)) {
			return;
		}
		glTaskQueuedCallback = null;
		glTaskQueue.clear();
		if (rocketSynchronizer != null) {
			rocketSynchronizer.dispose();
		}
	}

	/**
	 * Gets the camera controller for external access to camera operations.
	 *
	 * @return The camera controller instance
	 */
	public CameraControls getCameraController() {
		return cameraController;
	}

	/**
	 * Gets the input handler for external access to input state and processing.
	 *
	 * @return The input handler instance
	 */
	public SceneInputProcessor getInputHandler() {
		return inputHandler;
	}

	/**
	 * Gets the viewport dimensions for external access to coordinate conversion.
	 *
	 * @return The viewport dimensions instance
	 */
	public ViewportDimensions getViewport() {
		return viewport;
	}

	/**
	 * Creates an orchestrator using the figure 3D defaults and saved preferences.
	 *
	 * @param rocket the rocket model to visualize
	 * @param windowWidth the logical window width in pixels
	 * @param windowHeight the logical window height in pixels
	 * @param framebufferWidth the framebuffer width in pixels
	 * @param framebufferHeight the framebuffer height in pixels
	 * @return a configured orchestrator
	 * @throws Exception if the renderer cannot be initialized
	 */
	public static Scene3DOrchestrator create(Rocket rocket, int windowWidth, int windowHeight,
			int framebufferWidth, int framebufferHeight) throws Exception {
		ViewportDimensions viewport = new ViewportDimensions(
				windowWidth, windowHeight, framebufferWidth, framebufferHeight);
		Camera camera = Camera.builder()
				.withFieldOfView(Math.toRadians(10))
				.withAspectRatio(viewport.getAspectRatio())
				.withClippingPlanes(0.1f, 100f)
				.withFixedCenterOfInterest(false)
				.build();

		RenderingConfiguration config = new RenderingConfiguration();
		config.getVisualEffects().setParticleEffectsEnabled(false);
		Figure3DPreferences.applyDefaults(config, rocket.getDocument().getDocumentPreferences(),
				Application.getPreferences());

		Scene scene = new Scene(rocket, camera, config);
		return new Scene3DOrchestrator(rocket, viewport, camera, scene, config);
	}

	/**
	 * Connects the configured scene resources and creates the renderer and controllers.
	 *
	 * @param rocket the OpenRocket model to visualize
	 * @param viewport the viewport dimensions for rendering
	 * @param camera the pre-configured camera instance
	 * @param scene the pre-configured scene instance
	 * @param config the rendering configuration settings
	 * @throws Exception if the renderer cannot be initialized
	 */
	private Scene3DOrchestrator(Rocket rocket, ViewportDimensions viewport, Camera camera, Scene scene,
			RenderingConfiguration config) throws Exception {
		// 1. Initialize core components
		this.viewport = viewport;
		this.scene = scene;
		this.renderingConfiguration = config;
		Raycaster raycaster = new DefaultRaycaster();
		InputState inputState = new InputState();

		// 2. Initialize renderer
		this.renderer = new RealisticRenderer(renderingConfiguration, rocket,
				viewport.getFramebufferWidth(), viewport.getFramebufferHeight());
		// A view that opens at its final size never resizes, so this cannot wait for resize().
		this.renderer.setDisplayScale(getDisplayScale());

		// 3. Initialize controllers
		this.cameraController = new CameraController(rocket, camera, scene, renderingConfiguration);
		this.cameraController.initialize(rocket, viewport.getAspectRatio());
		this.cameraController.addCameraChangeListener(ignored -> {
			LightController lightController = this.scene.getLightController();
			if (!lightController.areVisualizersVisible()) {
				return;
			}
			for (Light light : lightController.getLights()) {
				lightController.refreshVisualizer(light);
			}
		});

		this.inputHandler = new DefaultSceneInputProcessor(inputState, raycaster, scene, cameraController,
				renderingConfiguration);
		this.inputHandler.updateDimensions(viewport);

		this.rocketSynchronizer = new RocketSceneSynchronizer(this, this.scene, rocket);
		this.lastFrameTime = System.nanoTime();
	}

	// ---------- Simulation control helpers ----------

	/**
	 * Binds replay providers by stage so separated stages can follow their own
	 * simulation branches.
	 */
	public void bindFlightPosesToRocket(Map<AxialStage, PoseProvider> providersByStage, PoseProvider primaryProvider,
			double startTime, double endTime) {
		if (providersByStage == null || providersByStage.isEmpty()) {
			throw new IllegalArgumentException("providersByStage is empty");
		}
		if (primaryProvider == null) {
			throw new IllegalArgumentException("primaryProvider is null");
		}

		enqueueGlTask(() -> {
			for (var obj : scene.getObjects()) {
				RocketComponent component = obj.getRocketComponent();
				if (component == null) {
					continue;
				}
				// Fall back to the primary (sustainer) trajectory when a component's stage has
				// no dedicated branch, so every rocket object flies with the rocket instead of
				// being stranded at the launch pad while the follow camera chases the rocket up.
				obj.setPoseProvider(providerOrPrimary(component, providersByStage, primaryProvider));
			}
			int emitterCount = 0;
			for (var emitter : scene.getParticleEmitters()) {
				RocketComponent component = emitter.getRocketComponent();
				PoseProvider provider = component != null
						? providerOrPrimary(component, providersByStage, primaryProvider)
						: primaryProvider;
				emitter.setPoseProvider(provider);
				emitter.applyPoseAtTime(startTime);
				emitterCount++;
			}
			log.info("Flight replay bound poses: {} rocket object(s), {} particle emitter(s) attached to a trajectory",
					scene.getObjects().size(), emitterCount);
		});
		this.flightPrimaryPoseProvider = primaryProvider;
		this.playbackClock = new PlaybackClock(startTime, endTime);
	}

	private static PoseProvider providerOrPrimary(RocketComponent component,
			Map<AxialStage, PoseProvider> providersByStage, PoseProvider primaryProvider) {
		PoseProvider provider = providerForComponent(component, providersByStage);
		return provider != null ? provider : primaryProvider;
	}

	private static PoseProvider providerForComponent(RocketComponent component,
			Map<AxialStage, PoseProvider> providersByStage) {
		AxialStage stage = stageForComponent(component);
		return stage != null ? providersByStage.get(stage) : null;
	}

	private static AxialStage stageForComponent(RocketComponent component) {
		if (component == null) {
			return null;
		}
		try {
			return component instanceof AxialStage stage ? stage : component.getStage();
		} catch (IllegalStateException e) {
			return null;
		}
	}

	/**
	 * Sets the motor burn windows per stage so exhaust emitters flame only while their
	 * own stage's motor is burning (a sustainer must not flame during a booster-only burn).
	 */
	public void setFlightBurnIntervals(Map<AxialStage, List<double[]>> burnIntervalsByStage) {
		if (burnIntervalsByStage == null) {
			this.flightBurnIntervalsByStage = null;
			return;
		}
		Map<AxialStage, List<double[]>> copy = new HashMap<>();
		for (Map.Entry<AxialStage, List<double[]>> entry : burnIntervalsByStage.entrySet()) {
			List<double[]> intervals = new ArrayList<>();
			for (double[] interval : entry.getValue()) {
				if (interval != null && interval.length >= 2) {
					intervals.add(new double[] { interval[0], interval[1] });
				}
			}
			copy.put(entry.getKey(), List.copyOf(intervals));
		}
		this.flightBurnIntervalsByStage = Map.copyOf(copy);
	}

	private void updateFlightParticleEmission(double time) {
		Map<AxialStage, List<double[]>> byStage = flightBurnIntervalsByStage;
		if (byStage == null) {
			return;
		}
		for (var emitter : scene.getParticleEmitters()) {
			AxialStage stage = stageForComponent(emitter.getRocketComponent());
			List<double[]> intervals = stage != null ? byStage.get(stage) : null;
			if (intervals == null) {
				// Burn window unknown for this emitter's stage: keep it emitting rather than
				// forcing it dark, so a mapping gap never leaves the rocket with no exhaust.
				emitter.setEmissionEnabled(true);
				continue;
			}
			emitter.setEmissionEnabled(isWithinAnyInterval(intervals, time));
		}
	}

	private static boolean isWithinAnyInterval(List<double[]> intervals, double time) {
		if (intervals == null) {
			return false;
		}
		for (double[] interval : intervals) {
			if (time >= interval[0] && time <= interval[1]) {
				return true;
			}
		}
		return false;
	}

	public PlaybackClock getPlaybackClock() {
		return playbackClock;
	}

	public void setFollowFlightCamera(boolean followFlightCamera) {
		this.followFlightCamera = followFlightCamera;
	}
}
