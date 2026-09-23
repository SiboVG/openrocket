package info.openrocket.swing.gui.figure3d.flight;

import info.openrocket.core.arch.SystemInfo;
import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.core.document.Simulation;
import info.openrocket.core.l10n.Translator;
import info.openrocket.core.rocketcomponent.AxialStage;
import info.openrocket.core.rocketcomponent.FlightConfigurationId;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.simulation.FlightData;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.simulation.FlightEvent;
import info.openrocket.core.startup.Application;
import info.openrocket.swing.gui.figure3d.SharedCanvasRenderScheduler;
import info.openrocket.swing.gui.figure3d.animation.PlaybackClock;
import info.openrocket.swing.gui.figure3d.animation.PoseProvider;
import info.openrocket.swing.gui.figure3d.constants.GeometryConstants;
import info.openrocket.swing.gui.figure3d.constants.RenderingConstants;
import info.openrocket.swing.gui.figure3d.geometry.IntList;
import info.openrocket.swing.gui.figure3d.geometry.Mesh;
import info.openrocket.swing.gui.figure3d.geometry.basic.AxesGenerator;
import info.openrocket.swing.gui.figure3d.geometry.basic.PlaneGenerator;
import info.openrocket.swing.gui.figure3d.geometry.basic.SphereGenerator;
import info.openrocket.swing.gui.figure3d.geometry.basic.TrajectoryTrailGenerator;
import info.openrocket.swing.gui.figure3d.particles.Particle;
import info.openrocket.swing.gui.figure3d.particles.smoke.SmokeEmitter;
import info.openrocket.swing.gui.figure3d.particles.smoke.SmokeSettings;
import info.openrocket.swing.gui.figure3d.materials.Appearance3D;
import info.openrocket.swing.gui.figure3d.rendering.FrameOverlay;
import info.openrocket.swing.gui.figure3d.rendering.backgrounds.GradientBackground;
import info.openrocket.swing.gui.figure3d.scene.controllers.CameraControls;
import info.openrocket.swing.gui.figure3d.scene.graph.Camera;
import info.openrocket.swing.gui.figure3d.scene.graph.SceneObject;
import info.openrocket.swing.gui.figure3d.scene.graph.SceneView;
import info.openrocket.swing.gui.figure3d.scene.orchestration.Scene3DOrchestrator;
import info.openrocket.swing.gui.figure3d.scene.orchestration.Scene3DOrchestrator.MotorExhaustMount;
import info.openrocket.swing.gui.figure3d.scene.properties.DisplaySettings;
import info.openrocket.swing.gui.figure3d.scene.properties.RenderingConfiguration;
import info.openrocket.swing.gui.figure3d.ui.GLScenePanel;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.GridBagLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

@SuppressWarnings("serial")
class Flight3DPanel extends JPanel implements SharedCanvasRenderScheduler.Client {
	private static final Logger log = LoggerFactory.getLogger(Flight3DPanel.class);
	private static final Translator trans = Application.getTranslator();
	private static final boolean DEBUG = Boolean.getBoolean("openrocket.figure3d.debug");
	private static final boolean IS_MACOS = SystemInfo.getPlatform() == SystemInfo.Platform.MAC_OS;
	private static final SharedCanvasRenderScheduler RENDER_SCHEDULER = SharedCanvasRenderScheduler.getInstance();
	private static final long RENDER_SHUTDOWN_TIMEOUT_MS = 2_000;
	private static final int STARTUP_RENDER_DELAY_MS = 120;
	private static final float MIN_GROUND_SIZE = 500.0f;

	private OpenRocketDocument document;
	private Simulation simulation;
	private FlightData flightData;
	private FlightConfigurationId replayConfigurationId;
	private GLScenePanel glPanel;
	private final AtomicReference<GLScenePanel> pendingCanvasRebuild = new AtomicReference<>();
	private final AtomicLong replayGeneration = new AtomicLong();
	private volatile BiConsumer<PlaybackClock, FlightReplayData> replayReadyCallback;
	private volatile long earliestRenderAtMs;
	private volatile boolean renderLoopRunning = false;
	private volatile FlightCameraMode cameraMode = FlightCameraMode.OVERVIEW;
	private volatile boolean panModeEnabled = false;
	private volatile boolean trailVisible = true;
	private volatile boolean exhaustVisible = true;
	private volatile Vector3f trajectoryCenter;
	private volatile Vector3f trajectoryDimensions;

	private static final int TRAIL_SAMPLES = 240;
	// Paths are split into chunks built once per decoration scale. Playback only toggles chunk
	// visibility and rebuilds the two short pieces either side of the playback position.
	static final int TRAIL_CHUNK_SAMPLES = 12;
	private static final float SEPARATED_TRAIL_RADIUS_SCALE = 0.7f;
	private static final float MIN_DECORATION_SCALE = 0.04f;
	private static final float DECORATION_SCALE_REBUILD_THRESHOLD = 0.06f;
	private static final Vector3f ACTIVE_FUTURE_COLOR = new Vector3f(0.16f, 0.42f, 0.28f);
	private static final Vector3f ACTIVE_PAST_COLOR = new Vector3f(0.35f, 1.0f, 0.55f);
	private static final Vector3f BOOSTER_FUTURE_COLOR = new Vector3f(0.40f, 0.24f, 0.12f);
	private static final Vector3f BOOSTER_PAST_COLOR = new Vector3f(1.0f, 0.55f, 0.18f);

	// Deterministic exhaust rendered through the real particle renderers: puff positions and
	// birth times are laid along the flown path up front, flame particles stream out from
	// their nozzles, and replay emitters (whose wall-clock simulation is a
	// no-op) expose them to the volumetric smoke and flame renderers. Everything shown is a
	// pure function of the playback time, so scrubbing is exact.
	private static final Vector3f SMOKE_COLOR = new Vector3f(0.80f, 0.80f, 0.83f);
	// Puffs render small when fresh and expand to full size over this many seconds, so in
	// follow mode the fresh smoke does not engulf the rocket.
	static final double SMOKE_GROWTH_SECONDS = 5.0;
	static final double SMOKE_LIFETIME_SECONDS = 10.0;
	// Keep the renderer at the start of its built-in fade after growth; replay smoke uses
	// explicit per-particle opacity so size and transparency can evolve independently.
	private static final float SMOKE_FADE_START_RATIO = 0.8f;
	// A slow buoyant rise of the hanging trail, in trail-radii per second.
	private static final float SMOKE_RISE_RATE = 0.02f;
	private static final int SMOKE_PATH_SAMPLES = 256;
	private static final int MAX_PUFFS_PER_BURN = 800;
	private static final int SMOKE_PARTICLES_PER_PUFF = 2;
	// The default flame exposure is tuned for the pad view's tightly packed plume; the
	// replay plume spreads its particles wider, so it needs more exposure to read as fire.
	private static final float FLAME_EXPOSURE = 0.2f;
	private static final int PARACHUTE_PANEL_COUNT = 8;
	private static final float PARACHUTE_CANOPY_FLATTENING = 0.42f;

	private final List<TrailPath> trailPaths = new ArrayList<>();
	private final List<TrailGeometry> trailGeometries = new ArrayList<>();
	private boolean trailsShown;
	private final List<SmokePuff> smokePuffs = new ArrayList<>();
	private final List<ReplayFlameEmitter> flameJets = new ArrayList<>();
	private final List<SceneObject> eventMarkers = new ArrayList<>();
	private final List<ParachuteCanopy> parachutes = new ArrayList<>();
	// Separately flying bodies the cameras can track, primary first. GL thread only.
	private final List<TrackedBody> trackedBodies = new ArrayList<>();
	private volatile int trackedBodyIndex = 0;
	private final AtomicBoolean dirty = new AtomicBoolean(true);
	private SmokeEmitter smokePuppet;
	private SceneObject positionMarker;
	private FlightOrientationGizmo orientationGizmo;
	private volatile FlightTargetMarker targetMarker;
	private float rocketLength = 1.0f;
	private volatile PlaybackClock playbackClock;
	private volatile Scene3DOrchestrator activeOrchestrator;
	private float trailRadius = 1.0f;
	private float trailDecorationScale = 1.0f;
	private float trailDecorationRadius = 1.0f;
	private float overviewFitDistance = Float.NaN;
	private float followTrailScale = MIN_DECORATION_SCALE;
	// The orbit angles the replay opened with, restored by the reset-view button.
	private volatile float initialCameraAngleX;
	private volatile float initialCameraAngleY;
	private volatile float initialCameraFieldOfView = (float) Math.toRadians(45.0);
	private double lastRebuildFraction = -1.0;

	/**
	 * One body's sampled center path. The provider and center offset let the elapsed/upcoming
	 * split be placed at the exact current position: the samples are too sparse for linear
	 * interpolation to keep up with a rocket accelerating at tens of g.
	 */
	private record TrailPath(List<Vector3f> points, List<Vector3f> ringFrames, boolean active,
			double startFraction, PoseProvider provider, Vector3f centerOffset) {
		private TrailPath(List<Vector3f> points, boolean active, double startFraction, PoseProvider provider,
				Vector3f centerOffset) {
			this(points, TrajectoryTrailGenerator.ringFrames(points), active, startFraction, provider, centerOffset);
		}

		private Vector3f positionAt(double time) {
			Vector3f position = provider.getPosition(time);
			if (centerOffset != null) {
				position.add(provider.getOrientation(time).transform(new Vector3f(centerOffset)));
			}
			return position;
		}
	}

	/** One path's scene objects: per-chunk elapsed and upcoming tubes plus the split chunk's pieces. */
	private static final class TrailGeometry {
		private final TrailPath path;
		// Indexed by chunk; null where a chunk produced no geometry.
		private final List<SceneObject> elapsedChunks = new ArrayList<>();
		private final List<SceneObject> upcomingChunks = new ArrayList<>();
		private final List<SceneObject> splitPieces = new ArrayList<>();
		private int splitChunk = -1;

		private TrailGeometry(TrailPath path) {
			this.path = path;
		}
	}

	/**
	 * One smoke particle of the trail: revealed at its birth time at a fixed world position,
	 * then carried by the wind recorded at that moment (engine units per second).
	 */
	record SmokePuff(Vector3f position, double birthTime, float size, Vector3f color, Vector3f drift) {
		SmokePuff(Vector3f position, double birthTime, float size, Vector3f color) {
			this(position, birthTime, size, color, new Vector3f());
		}
	}

	/** One evenly spaced exhaust position and its interpolated flight time. */
	record SmokeStation(Vector3f position, double time) {
	}

	/** A canopy and its lines, shown above a descending stage between deployment and touchdown. */
	private record ParachuteCanopy(List<SceneObject> panels, List<SceneObject> suspensionLines,
			PoseProvider provider, Vector3f packedLocation, double deployTime, double endTime, float lineLength) {
		private ParachuteCanopy {
			packedLocation = new Vector3f(packedLocation);
		}
	}

	record ParachuteGeometry(List<Mesh> canopyPanels, List<Mesh> suspensionLines, float lineLength) {
	}

	Flight3DPanel() {
		setLayout(new BorderLayout());
	}

	void setReplayReadyCallback(BiConsumer<PlaybackClock, FlightReplayData> replayReadyCallback) {
		this.replayReadyCallback = replayReadyCallback;
	}

	void setSimulation(OpenRocketDocument doc, Simulation sim) {
		debug("setSimulation start");
		if (doc != null && sim != null && doc == document && sim == simulation && glPanel != null) {
			debug("setSimulation: already set");
			return;
		}
		clearDoc();
		if (doc == null || sim == null) {
			debug("setSimulation: doc/sim=null");
			return;
		}

		document = doc;
		simulation = sim;
		flightData = sim.getSimulatedData();
		replayConfigurationId = sim.getFlightConfigurationId();

		GLScenePanel panel = createCanvas("3D flight replay view unavailable");
		if (panel == null) {
			document = null;
			simulation = null;
			flightData = null;
			replayConfigurationId = null;
			return;
		}
		installCanvas(panel);
		startRenderLoop();
		debug("setSimulation done");
	}

	void clearDoc() {
		debug("clearDoc");
		replayGeneration.incrementAndGet();
		Scene3DOrchestrator orchestrator = activeOrchestrator;
		if (orchestrator != null) {
			orchestrator.setFlightFrameListener(null);
		}
		orientationGizmo = null;
		targetMarker = null;
		stopRenderLoop();
		if (glPanel != null) {
			RENDER_SCHEDULER.awaitQuiescence(RENDER_SHUTDOWN_TIMEOUT_MS);
			disposeCurrentCanvas(glPanel);
		}
		pendingCanvasRebuild.set(null);
		trailPaths.clear();
		trailGeometries.clear();
		smokePuffs.clear();
		flameJets.clear();
		eventMarkers.clear();
		parachutes.clear();
		trackedBodies.clear();
		smokePuppet = null;
		positionMarker = null;
		playbackClock = null;
		activeOrchestrator = null;
		lastRebuildFraction = -1.0;
		document = null;
		simulation = null;
		flightData = null;
		replayConfigurationId = null;
	}

	private GLScenePanel createCanvas(String unavailableMessage) {
		try {
			return new GLScenePanel(document.getRocket(), null, replayConfigurationId);
		} catch (UnsatisfiedLinkError | ExceptionInInitializerError e) {
			log.warn("{}: LWJGL native libraries not found for {}/{}.",
					unavailableMessage, System.getProperty("os.name"), System.getProperty("os.arch"), e);
			return null;
		}
	}

	private void installCanvas(GLScenePanel panel) {
		glPanel = panel;
		panel.setFlightReplayInteraction(true);
		panel.setPanModeEnabled(panModeEnabled && cameraMode != FlightCameraMode.PAD);
		panel.setRenderActivityCallback(this::markDirty);
		panel.setRenderRequestCallback(this::requestRenderNow);
		long generation = replayGeneration.get();
		panel.setInitializationHook(orchestrator ->
				initializeFlightPanelOnGlThread(orchestrator, panel, generation));
		panel.setGraphicsResetCallback(() -> requestCanvasRebuild(panel));
		panel.setGlInitFailureCallback(() -> SwingUtilities.invokeLater(() -> showGLInitFailureUI(panel)));
		earliestRenderAtMs = System.currentTimeMillis() + STARTUP_RENDER_DELAY_MS;
		add(panel, BorderLayout.CENTER);
		revalidate();
		repaint();
	}

	private void startRenderLoop() {
		if (!SwingUtilities.isEventDispatchThread()) {
			SwingUtilities.invokeLater(this::startRenderLoop);
			return;
		}
		if (renderLoopRunning) {
			return;
		}
		renderLoopRunning = true;
		dirty.set(true);
		RENDER_SCHEDULER.register(this);
		RENDER_SCHEDULER.requestImmediate(this);
	}

	private void stopRenderLoop() {
		if (!SwingUtilities.isEventDispatchThread()) {
			SwingUtilities.invokeLater(this::stopRenderLoop);
			return;
		}
		if (renderLoopRunning) {
			renderLoopRunning = false;
			RENDER_SCHEDULER.unregister(this);
		}
	}

	private void renderFrame() {
		if (!renderLoopRunning) {
			return;
		}
		GLScenePanel panel = glPanel;
		if (panel == null) {
			return;
		}
		if (panel.hasGlInitFailed()) {
			stopRenderLoop();
			return;
		}
		if (System.currentTimeMillis() < earliestRenderAtMs) {
			dirty.set(true);
			return;
		}
		if (!panel.isDisplayable() || !panel.isShowing()) {
			return;
		}
		if (panel.getWidth() <= 0 || panel.getHeight() <= 0) {
			return;
		}
		panel.render();
		processPendingCanvasRebuild(panel);
	}

	@Override
	public boolean isRenderActive() {
		return renderLoopRunning && glPanel != null && document != null && flightData != null;
	}

	@Override
	public boolean shouldRenderOnTick() {
		PlaybackClock clock = playbackClock;
		Scene3DOrchestrator orchestrator = activeOrchestrator;
		if ((clock != null && clock.getRate() != 0.0)
				|| (orchestrator != null && orchestrator.isFlightCameraTransitioning())) {
			dirty.set(false);
			return true;
		}
		return dirty.getAndSet(false);
	}

	private void markDirty() {
		dirty.set(true);
	}

	void requestRenderNow() {
		markDirty();
		if (renderLoopRunning) {
			RENDER_SCHEDULER.requestImmediate(this);
		}
	}

	@Override
	public void renderScheduledFrame() {
		renderFrame();
	}

	@Override
	public String getRenderDebugName() {
		return "Flight3DPanel";
	}

	private void showGLInitFailureUI(GLScenePanel failedPanel) {
		if (glPanel == failedPanel) {
			disposeCurrentCanvas(failedPanel);
		}
		JLabel label = new JLabel(trans.get("PhotoPanel.glInitFailed"));
		label.setHorizontalAlignment(SwingConstants.CENTER);
		JPanel fallback = new JPanel(new GridBagLayout());
		fallback.add(label);
		add(fallback, BorderLayout.CENTER);
		revalidate();
		repaint();
	}

	private void requestCanvasRebuild(GLScenePanel failedPanel) {
		pendingCanvasRebuild.compareAndSet(null, failedPanel);
	}

	private void processPendingCanvasRebuild(GLScenePanel panel) {
		if (!pendingCanvasRebuild.compareAndSet(panel, null)) {
			return;
		}
		rebuildCanvasAfterBlankDefaultFramebuffer(panel);
	}

	private void rebuildCanvasAfterBlankDefaultFramebuffer(GLScenePanel failedPanel) {
		if (!SwingUtilities.isEventDispatchThread()) {
			SwingUtilities.invokeLater(() -> rebuildCanvasAfterBlankDefaultFramebuffer(failedPanel));
			return;
		}
		if (glPanel != failedPanel || document == null || flightData == null) {
			return;
		}

		boolean resumeRenderLoop = renderLoopRunning;
		stopRenderLoop();
		RENDER_SCHEDULER.awaitQuiescence(RENDER_SHUTDOWN_TIMEOUT_MS);
		if (glPanel != failedPanel || document == null || flightData == null) {
			if (resumeRenderLoop && glPanel != null) {
				startRenderLoop();
			}
			return;
		}

		disposeCurrentCanvas(failedPanel);
		GLScenePanel panel = createCanvas("3D flight replay view unavailable during recovery");
		if (panel == null) {
			return;
		}
		installCanvas(panel);
		if (resumeRenderLoop) {
			startRenderLoop();
		}
	}

	private void disposeCurrentCanvas(GLScenePanel panel) {
		panel.setInitializationHook(null);
		panel.setGraphicsResetCallback(null);
		panel.setRenderActivityCallback(null);
		panel.setRenderRequestCallback(null);
		if (IS_MACOS) {
			// Keep the same teardown order as PhotoPanel: detach first so cleanup avoids
			// re-entering the macOS JAWT surface path after peer teardown has begun.
			remove(panel);
			glPanel = null;
			panel.cleanup();
		} else {
			panel.cleanup();
			remove(panel);
			glPanel = null;
		}
		revalidate();
		repaint();
	}

	private void initializeFlightPanelOnGlThread(Scene3DOrchestrator orchestrator, GLScenePanel initializedPanel,
			long generation) {
		FlightData data = flightData;
		OpenRocketDocument doc = document;
		if (data == null || doc == null) {
			return;
		}

		SceneView scene = orchestrator.getScene();
		RenderingConfiguration config = orchestrator.getRenderingConfiguration();
		config.getDisplay().setMode(DisplaySettings.RenderMode.FINISHED);
		config.getVisualEffects().setCaretsVisible(false);
		config.getVisualEffects().setRotateRocketOnDrag(false);
		// The live particle simulation is tuned for the close-up pad views and does not read
		// at flight scale; the replay drives the smoke/flame renderers itself from
		// path-anchored data instead (see buildExhaustGeometry).
		config.getVisualEffects().setParticleEffectsEnabled(false);
		config.getVisualEffects().setFlameExposureScale(FLAME_EXPOSURE);
		orchestrator.rebuildRocketScene(false);
		scene = orchestrator.getScene();
		keepRocketInForeground(scene);
		addGroundReference(scene, data);
		applyFlightBackground(scene);
		disableComponentSelection(scene);

		FlightReplayData replayData = new FlightReplayData(data, doc.getRocket());
		// Reuse the design-view rocket-center computation as the fallback for body centers.
		Vector3f rocketCenterOffset = orchestrator.getCameraController().computeRocketCenter();
		CenteredPoses centeredPoses = centerBodiesOnThemselves(scene, replayData, rocketCenterOffset);
		GroundedPoseProviders groundedPoses = createGroundedPoseProviders(scene, centeredPoses.providersByStage(),
				centeredPoses.primaryProvider(), replayData.getStartTime());
		orchestrator.bindFlightPosesToRocket(groundedPoses.providersByStage(), groundedPoses.primaryProvider(),
				replayData.getStartTime(), replayData.getEndTime());
		Map<AxialStage, List<double[]>> burnTimeline = toStageTimeline(replayData.getBurnIntervalsByStage());
		int burnWindowCount = burnTimeline.values().stream().mapToInt(List::size).sum();
		log.info("Flight replay: {} stage(s) with {} total motor burn window(s)", burnTimeline.size(), burnWindowCount);

		// The cameras orbit the tracked body's middle, not the rocket's nose.
		collectTrackedBodies(data, replayData, groundedPoses, centeredPoses.bodyCenters());
		orchestrator.setFlightRocketCenterOffset(trackedBodies.get(0).centerOffset());
		computeTrajectoryBounds(orchestrator.getCameraController(), groundedPoses,
				replayData.getStartTime(), replayData.getEndTime());
		buildTrajectoryTrails(scene, groundedPoses, rocketCenterOffset,
				replayData.getStartTime(), replayData.getEndTime());
		rocketLength = rocketLengthWorld(orchestrator);
		followTrailScale = followTrailScale(rocketLength, trailRadius);
		addEventMarkers(scene, replayData, groundedPoses.primaryProvider(),
				bodyCenterOffset(groundedPoses.primaryProvider(), rocketCenterOffset));
		buildExhaustGeometry(scene, orchestrator, config, groundedPoses,
				replayData, burnTimeline, rocketCenterOffset);
		Camera camera = orchestrator.getCameraController().getCamera();
		initialCameraAngleX = camera.getAngleX();
		initialCameraAngleY = camera.getAngleY();
		initialCameraFieldOfView = camera.getFieldOfView();
		applyCameraMode(orchestrator, cameraMode);
		orchestrator.skipFlightCameraTransition();

		PlaybackClock clock = orchestrator.getPlaybackClock();
		if (clock != null) {
			clock.setRate(0.0);
		}
		this.playbackClock = clock;
		this.activeOrchestrator = orchestrator;
		this.lastRebuildFraction = -1.0;
		orchestrator.setFlightFrameListener(this::onFlightFrame);

		FlightOrientationGizmo gizmo = new FlightOrientationGizmo();
		FlightTargetMarker marker = new FlightTargetMarker(orchestrator.getCameraController().getCamera());
		orientationGizmo = gizmo;
		targetMarker = marker;
		orchestrator.getRenderer().setFrameOverlay(new FrameOverlay() {
			@Override
			public void render(Matrix4f cameraViewMatrix, int width, int height) {
				marker.render(cameraViewMatrix, width, height);
				gizmo.render(cameraViewMatrix, width, height);
			}

			@Override
			public void cleanup() {
				marker.cleanup();
				gizmo.cleanup();
			}
		});

		BiConsumer<PlaybackClock, FlightReplayData> callback = replayReadyCallback;
		if (callback != null && clock != null) {
			SwingUtilities.invokeLater(() -> {
				if (generation == replayGeneration.get() && glPanel == initializedPanel && document == doc) {
					callback.accept(clock, replayData);
				}
			});
		}
	}

	/**
	 * Switches the replay camera behaviour. Safe to call from the EDT: the orchestrator methods
	 * set volatile flags applied on the render thread.
	 */
	void setCameraMode(FlightCameraMode mode) {
		FlightCameraMode previous = this.cameraMode;
		this.cameraMode = mode;
		if (mode == FlightCameraMode.PAD) {
			setPanModeEnabled(false);
		}
		GLScenePanel panel = glPanel;
		if (panel == null) {
			return;
		}
		Scene3DOrchestrator orchestrator = panel.getScene3DOrchestrator();
		if (orchestrator != null) {
			if (previous == FlightCameraMode.PAD && mode != FlightCameraMode.PAD) {
				// The pad view's angles look steeply up from the ground; kept, they would put the
				// orbiting views' eye far below it. Start them from the replay's opening angles.
				restoreInitialCameraAngles(orchestrator);
			}
			applyCameraMode(orchestrator, mode);
		}
	}

	/** Queued before a mode's refit, which then frames its view from the original angles. */
	private void restoreInitialCameraAngles(Scene3DOrchestrator orchestrator) {
		orchestrator.enqueueGlTask(() -> {
			Camera camera = orchestrator.getCameraController().getCamera();
			camera.setAngleX(initialCameraAngleX);
			camera.setAngleY(initialCameraAngleY);
			camera.resetViewOffset();
		});
	}

	FlightCameraMode getCameraMode() {
		return cameraMode;
	}

	void zoomIn() {
		zoomBy(1.0f);
	}

	void zoomOut() {
		zoomBy(-1.0f);
	}

	private void zoomBy(float scrollAmount) {
		Scene3DOrchestrator orchestrator = activeOrchestrator;
		if (orchestrator != null) {
			orchestrator.zoomFlightCamera(scrollAmount);
			requestRenderNow();
		}
	}

	/** Resets the current camera mode's view, including any orbit the user applied. */
	void fitView() {
		Scene3DOrchestrator orchestrator = activeOrchestrator;
		if (orchestrator != null) {
			restoreInitialCameraAngles(orchestrator);
			applyCameraMode(orchestrator, cameraMode);
			requestRenderNow();
		}
	}

	void setPanModeEnabled(boolean enabled) {
		boolean accepted = enabled && cameraMode != FlightCameraMode.PAD;
		panModeEnabled = accepted;
		GLScenePanel panel = glPanel;
		if (panel != null) {
			panel.setPanModeEnabled(accepted);
		}
		requestRenderNow();
	}

	void setTrailVisible(boolean visible) {
		trailVisible = visible;
		Scene3DOrchestrator orchestrator = activeOrchestrator;
		if (orchestrator != null) {
			orchestrator.enqueueGlTask(() -> setTrailDecorationsVisible(cameraMode == FlightCameraMode.OVERVIEW));
		}
		requestRenderNow();
	}

	void setExhaustVisible(boolean visible) {
		exhaustVisible = visible;
		requestRenderNow();
	}

	private void applyCameraMode(Scene3DOrchestrator orchestrator, FlightCameraMode mode) {
		// The position marker is sized for the trajectory scale and would dwarf the rocket up
		// close, so it shows only in the overview. The trail follows its checkbox in every mode;
		// the next frame resizes it for the new camera.
		boolean markerView = mode == FlightCameraMode.OVERVIEW;
		orchestrator.enqueueGlTask(() -> {
			setTrailDecorationsVisible(markerView);
			lastRebuildFraction = -1.0;
		});
		orchestrator.setFlightPanEnabled(mode != FlightCameraMode.PAD);

		switch (mode) {
			case FOLLOW -> orchestrator.setFollowFlightCamera(true);
			case PAD -> {
				// A launch-footage viewpoint: a few meters out from the pad at head height.
				float away = Math.max(7.0f * RenderingConstants.WORLD_SCALE,
						rocketLengthWorld(orchestrator) * 4.0f);
				orchestrator.setPadFlightCamera(new Vector3f(away, 1.7f * RenderingConstants.WORLD_SCALE, away));
			}
			default -> {
				if (trajectoryCenter != null && trajectoryDimensions != null) {
					orchestrator.fitFlightTrajectory(trajectoryCenter, trajectoryDimensions);
				} else {
					orchestrator.setFollowFlightCamera(false);
					orchestrator.focusOnRocket();
				}
			}
		}
	}

	private static float rocketLengthWorld(Scene3DOrchestrator orchestrator) {
		Vector3f size = orchestrator.getCameraController().computeRocketSize();
		return size != null ? Math.max(size.x, 1.0f) : 20.0f;
	}

	private void computeTrajectoryBounds(CameraControls cameraControls, GroundedPoseProviders poses,
			double startTime, double endTime) {
		Vector3f min = new Vector3f(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY);
		Vector3f max = new Vector3f(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY);

		// Stages share providers until they separate; sample each distinct trajectory once.
		Set<PoseProvider> providers = Collections.newSetFromMap(new IdentityHashMap<>());
		providers.addAll(poses.providersByStage().values());
		providers.add(poses.primaryProvider());
		for (PoseProvider provider : providers) {
			for (int i = 0; i <= TRAIL_SAMPLES; i++) {
				double t = startTime + (endTime - startTime) * i / TRAIL_SAMPLES;
				Vector3f position = provider.getPosition(t);
				min.min(position);
				max.max(position);
			}
		}

		if (!Float.isFinite(min.x) || !Float.isFinite(max.x)) {
			trajectoryCenter = null;
			trajectoryDimensions = null;
			return;
		}

		// Pad by the rocket's largest dimension so its body is not clipped at the trajectory ends.
		Vector3f rocketSize = cameraControls.computeRocketSize();
		float pad = rocketSize != null
				? Math.max(rocketSize.x, Math.max(rocketSize.y, rocketSize.z)) * 0.5f
				: 0.0f;
		min.sub(pad, pad, pad);
		max.add(pad, pad, pad);

		trajectoryCenter = new Vector3f(min).add(max).mul(0.5f);
		trajectoryDimensions = new Vector3f(max).sub(min);
	}

	private void disableComponentSelection(SceneView scene) {
		scene.setSelection(List.of());
		for (SceneObject obj : scene.getObjects()) {
			obj.setSelected(false);
			obj.setSelectable(false);
		}
	}

	/** Keeps the animated rocket readable where the decorative centerline passes through it. */
	private static void keepRocketInForeground(SceneView scene) {
		for (SceneObject object : scene.getObjects()) {
			if (object.getRocketComponent() != null) {
				object.setRenderInForeground(true);
			}
		}
	}

	/**
	 * Builds a visible tube along each stage-center flight path. At the whole-flight zoom the
	 * rocket itself is only a few pixels, so the trail is what makes the trajectory legible.
	 * The full path is drawn faded ("still to come"); a brighter overlay grows over the elapsed
	 * portion as the flight plays (see {@link #rebuildTrails}). The active sustainer path and
	 * separated-booster paths use different hues.
	 */
	private void buildTrajectoryTrails(SceneView scene, GroundedPoseProviders poses, Vector3f centerOffset,
			double startTime, double endTime) {
		trailPaths.clear();
		trailGeometries.clear();
		positionMarker = null;
		Vector3f dimensions = trajectoryDimensions;
		if (dimensions == null) {
			return;
		}
		float maxExtent = Math.max(dimensions.x, Math.max(dimensions.y, dimensions.z));
		trailRadius = Math.max(maxExtent * 0.003f, 1.0f);
		trailDecorationScale = 1.0f;
		trailDecorationRadius = trailRadius;
		overviewFitDistance = Float.NaN;
		boolean overviewVisible = cameraMode == FlightCameraMode.OVERVIEW;

		PoseProvider primary = poses.primaryProvider();
		List<Vector3f> primaryPath = samplePath(primary, centerOffset, startTime, endTime);
		Vector3f primaryCenter = bodyCenterOffset(primary, centerOffset);
		trailPaths.add(new TrailPath(samplePath(primary, primaryCenter, startTime, endTime), true, 0.0,
				primary, primaryCenter));

		Set<PoseProvider> boosters = Collections.newSetFromMap(new IdentityHashMap<>());
		boosters.addAll(poses.providersByStage().values());
		boosters.remove(primary);
		for (PoseProvider booster : boosters) {
			// Only plot a booster from where its path diverges from the sustainer (post-separation),
			// since before separation it rides the same path and would z-fight the active trail.
			// Compare through the same point on both so only the separation registers.
			int from = firstDivergenceIndex(samplePath(booster, centerOffset, startTime, endTime),
					primaryPath, trailRadius * 3.0f);
			// Draw it through the booster's own center, where the booster flies.
			Vector3f boosterCenter = bodyCenterOffset(booster, centerOffset);
			List<Vector3f> full = samplePath(booster, boosterCenter, startTime, endTime);
			List<Vector3f> divergent = new ArrayList<>(full.subList(Math.max(0, from), full.size()));
			// The booster path covers global playback fractions [from/samples, 1], so elapsed
			// coloring only starts once the flight passes its separation point.
			trailPaths.add(new TrailPath(divergent, false, (double) from / TRAIL_SAMPLES, booster, boosterCenter));
		}

		// A bright marker at the rocket's current center — the rocket itself is sub-pixel at the
		// whole-flight zoom, so this shows where it is along the trail. Hidden in follow mode.
		Mesh markerMesh = SphereGenerator.create(trailRadius * 2.5f, 16, 12);
		Appearance3D markerAppearance = new Appearance3D(new Vector3f(1.0f, 0.95f, 0.35f));
		markerAppearance.setUnlit(true);
		positionMarker = new SceneObject(markerMesh,
				centerOffset != null ? new Vector3f(centerOffset) : new Vector3f(), markerAppearance);
		positionMarker.setSelectable(false);
		positionMarker.setForegroundDecoration(true);
		positionMarker.setPoseProvider(primary);
		positionMarker.setVisible(overviewVisible);
		scene.addObject(positionMarker);

		rebuildTrails(scene, 0.0, startTime);
	}

	/**
	 * Builds the replay's exhaust: for each stage's burn window, smoke puff positions laid
	 * along the flown path at fixed spatial spacing, plus a streaming flame plume per motor.
	 * The visuals come from the real volumetric-smoke and flame renderers via
	 * "puppet" emitters that never simulate: {@link #updateExhaust} fills their particle
	 * lists each frame as a pure function of the playback time, so any scrub shows the
	 * exact state continuous playback would have produced. Runs on the GL thread.
	 */
	private void buildExhaustGeometry(SceneView scene, Scene3DOrchestrator orchestrator,
			RenderingConfiguration config, GroundedPoseProviders poses, FlightReplayData replayData,
			Map<AxialStage, List<double[]>> burnTimeline, Vector3f centerOffset) {
		smokePuffs.clear();
		flameJets.clear();
		Vector3f rocketSize = orchestrator.getCameraController().computeRocketSize();
		// The rocket's long axis runs along X in the unposed scene (nose toward -X).
		float rocketLength = rocketSize != null ? Math.max(rocketSize.x, 1.0f) : trailRadius;
		// The smoke renderer draws a particle at up to 4x its size. Cap the trail-scaled
		// size against the rocket so exhaust also reads naturally in the follow view.
		float puffSize = replaySmokeSize(trailRadius, rocketLength);
		float spacing = puffSize * 1.2f;

		smokePuppet = new SmokeEmitter(new Vector3f(), new Vector3f(0.0f, 1.0f, 0.0f),
				SmokeSettings.medium(config)) {
			@Override
			public void update(float deltaTime) {
				// Scripted: the replay fills the particles as a function of playback time.
			}
		};
		scene.addParticleEmitter(smokePuppet);

		List<MotorExhaustMount> exhaustMounts = orchestrator.getMotorExhaustMounts();
		for (Map.Entry<AxialStage, List<double[]>> entry : burnTimeline.entrySet()) {
			PoseProvider provider = providerForStage(entry.getKey(), poses.providersByStage(), poses.primaryProvider());
			if (provider == null || entry.getValue().isEmpty()) {
				continue;
			}
			List<MotorExhaustMount> stageMounts = exhaustMounts.stream()
					.filter(mount -> stageFor(mount.mountComponent()) == entry.getKey())
					.toList();
			for (MotorExhaustMount mount : stageMounts) {
				for (double[] window : entry.getValue()) {
					addSmokeColumn(provider, mount.nozzlePosition(), window[0], window[1], puffSize, spacing);
				}
				addFlameJet(scene, config, provider, entry.getValue(), mount.nozzlePosition(),
						mount.exhaustDirection(), rocketLength);
			}
		}
		addEventBursts(replayData, poses, centerOffset, puffSize);
		addParachutes(scene, replayData, poses, rocketLength);
		addLaunchSiteReference(scene, rocketLength);
		log.info("Flight replay exhaust: {} smoke puff(s), {} flame jet(s), {} parachute(s)",
				smokePuffs.size(), flameJets.size(), parachutes.size());
	}

	/**
	 * Lays smoke puffs along the path flown during one burn window, a small cluster per
	 * fixed distance travelled (so the column is spatially uniform however fast the rocket
	 * moves), with deterministic jitter so it reads as a smoke column rather than beads.
	 */
	private void addSmokeColumn(PoseProvider provider, Vector3f nozzleLocal,
			double burnStart, double burnEnd, float puffSize, float spacing) {
		Random jitter = new Random(Double.hashCode(burnStart) * 31L + smokePuffs.size());
		WindField wind = windFor(provider);
		for (SmokeStation station : sampleSmokeStations(provider, nozzleLocal,
				burnStart, burnEnd, spacing, MAX_PUFFS_PER_BURN)) {
			for (int j = 0; j < SMOKE_PARTICLES_PER_PUFF; j++) {
				float size = puffSize * (0.7f + 0.6f * jitter.nextFloat());
				Vector3f puffCenter = new Vector3f(station.position()).add(
						(jitter.nextFloat() - 0.5f) * puffSize * 0.4f,
						(jitter.nextFloat() - 0.5f) * puffSize * 0.4f,
						(jitter.nextFloat() - 0.5f) * puffSize * 0.4f);
				smokePuffs.add(new SmokePuff(puffCenter, station.time(), size, SMOKE_COLOR,
						wind.velocityAt(station.time())));
			}
		}
	}

	static float replaySmokeSize(float pathRadius, float rocketLength) {
		// A high apogee must not turn fresh exhaust into a cloud larger than the rocket.
		return Math.max(0.01f, Math.min(pathRadius * 0.5f, rocketLength * 0.08f));
	}

	/**
	 * Converts the time-sampled nozzle path into exact spatial intervals. A fast rocket may
	 * cross several intervals between adjacent samples, so every crossing is interpolated
	 * instead of emitting only one puff for the whole sampled segment.
	 */
	static List<SmokeStation> sampleSmokeStations(PoseProvider provider, Vector3f nozzleLocal,
			double burnStart, double burnEnd, float spacing, int maximumStations) {
		if (!Float.isFinite(spacing) || spacing <= 0.0f || maximumStations <= 0
				|| !Double.isFinite(burnStart) || !Double.isFinite(burnEnd) || burnEnd < burnStart) {
			return List.of();
		}

		// Fit the budget to the entire burn instead of silently cutting the plume off midway.
		List<Vector3f> path = new ArrayList<>(SMOKE_PATH_SAMPLES + 1);
		double length = 0.0;
		for (int i = 0; i <= SMOKE_PATH_SAMPLES; i++) {
			Vector3f position = nozzlePosition(provider, nozzleLocal,
					burnStart + (burnEnd - burnStart) * i / SMOKE_PATH_SAMPLES);
			if (!path.isEmpty()) length += position.distance(path.get(path.size() - 1));
			path.add(position);
		}
		double interval = maximumStations > 1 ? Math.max(spacing, length / (maximumStations - 1)) : spacing;
		List<SmokeStation> stations = new ArrayList<>(maximumStations);
		double previousTime = burnStart;
		Vector3f previousPosition = path.get(0);
		stations.add(new SmokeStation(new Vector3f(previousPosition), previousTime));
		double travelled = 0.0;
		double nextStationDistance = interval;

		for (int i = 1; i <= SMOKE_PATH_SAMPLES && stations.size() < maximumStations; i++) {
			double time = burnStart + (burnEnd - burnStart) * i / SMOKE_PATH_SAMPLES;
			Vector3f position = path.get(i);
			double segmentLength = position.distance(previousPosition);
			double segmentEndDistance = travelled + segmentLength;

			while (nextStationDistance <= segmentEndDistance + 1e-6 && stations.size() < maximumStations) {
				double fraction = segmentLength > 0.0
						? Math.min(1.0, (nextStationDistance - travelled) / segmentLength) : 0.0;
				Vector3f stationPosition = new Vector3f(previousPosition)
						.lerp(position, (float) fraction);
				double stationTime = previousTime + (time - previousTime) * fraction;
				stations.add(new SmokeStation(stationPosition, stationTime));
				nextStationDistance += interval;
			}

			travelled = segmentEndDistance;
			previousPosition = position;
			previousTime = time;
		}
		return List.copyOf(stations);
	}

	private static Vector3f nozzlePosition(PoseProvider provider, Vector3f nozzleLocal, double time) {
		Vector3f position = new Vector3f(provider.getPosition(time));
		return position.add(provider.getOrientation(time).transform(new Vector3f(nozzleLocal)));
	}

	/**
	 * Adds static launch-site scenery: a pad disc with a launch rod at the origin and a compass
	 * rose whose arrow colors match the orientation gizmo's cardinal letters.
	 */
	private void addLaunchSiteReference(SceneView scene, float rocketLength) {
		// Pad disc, half sunk into the ground.
		Mesh padMesh = SphereGenerator.create(rocketLength * 1.5f, 24, 12);
		Appearance3D padAppearance = new Appearance3D(new Vector3f(0.32f, 0.32f, 0.34f));
		padAppearance.setUnlit(true);
		SceneObject pad = new SceneObject(padMesh, new Vector3f(), padAppearance);
		pad.setSelectable(false);
		pad.getModelMatrix().scaling(1.0f, 0.08f, 1.0f);
		scene.addObject(pad);

		// Launch rod, standing beside the rocket.
		float rodLength = rocketLength * 1.4f;
		Mesh rodMesh = AxesGenerator.createArrowMesh(rodLength, rocketLength * 0.015f,
				rocketLength * 0.02f, rocketLength * 0.015f);
		Appearance3D rodAppearance = new Appearance3D(new Vector3f(0.55f, 0.55f, 0.58f));
		rodAppearance.setUnlit(true);
		SceneObject rod = new SceneObject(rodMesh, new Vector3f(), rodAppearance);
		rod.setSelectable(false);
		// The arrow points along +X; stand it upright with its base on the pad.
		rod.getModelMatrix().translation(0.0f, rodLength * 0.5f, rocketLength * 0.08f)
				.rotateZ((float) (Math.PI / 2.0));
		scene.addObject(rod);

		// Compass rose: four flat arrows matching the gizmo's cardinal colors.
		record CompassArrow(float yawRadians, Vector3f color) {
		}
		CompassArrow[] arrows = {
				new CompassArrow((float) (Math.PI / 2.0), new Vector3f(0.95f, 0.27f, 0.27f)),  // N = -Z
				new CompassArrow(0.0f, new Vector3f(0.32f, 0.82f, 0.42f)),                     // E = +X
				new CompassArrow((float) (-Math.PI / 2.0), new Vector3f(0.40f, 0.58f, 1.0f)),  // S = +Z
				new CompassArrow((float) Math.PI, new Vector3f(1.0f, 0.82f, 0.22f))            // W = -X
		};
		float arrowLength = rocketLength * 1.2f;
		float arrowDistance = rocketLength * 2.4f;
		float arrowHeadRadius = rocketLength * 0.11f;
		for (CompassArrow arrow : arrows) {
			Mesh arrowMesh = AxesGenerator.createArrowMesh(arrowLength, rocketLength * 0.05f,
					rocketLength * 0.45f, arrowHeadRadius);
			Appearance3D appearance = new Appearance3D(arrow.color());
			appearance.setUnlit(true);
			SceneObject compassArrow = new SceneObject(arrowMesh, new Vector3f(), appearance);
			compassArrow.setSelectable(false);
			// Rotate the +X arrow to its cardinal direction and push it out from the pad,
			// lifted so the arrowhead clears the ground plane instead of clipping into it.
			compassArrow.getModelMatrix()
					.rotationY(arrow.yawRadians())
					.translate(arrowDistance, arrowHeadRadius * 1.3f, 0.0f);
			scene.addObject(compassArrow);
		}
	}

	/**
	 * Adds a canopy above each stage with a recovery deployment event, shown from the moment
	 * of deployment until touchdown so the slowed descent visually makes sense. The canopy
	 * stays upright regardless of how the stage tumbles.
	 */
	private void addParachutes(SceneView scene, FlightReplayData replayData, GroundedPoseProviders poses,
			float rocketLength) {
		parachutes.clear();
		for (var event : replayData.getAllEvents()) {
			if (event.getType() != FlightEvent.Type.RECOVERY_DEVICE_DEPLOYMENT) {
				continue;
			}
			PoseProvider provider = providerForEventSource(event.getSource(), poses);
			Vector3f packedLocation = findComponentAnchor(scene, event.getSource());
			double end = replayData.getGroundHitTime(event, replayData.getEndTime());
			ParachuteGeometry geometry = createParachuteGeometry(rocketLength);
			List<SceneObject> panels = new ArrayList<>(geometry.canopyPanels().size());
			for (int i = 0; i < geometry.canopyPanels().size(); i++) {
				Vector3f color = i % 2 == 0
						? new Vector3f(0.92f, 0.18f, 0.12f)
						: new Vector3f(1.0f, 0.82f, 0.56f);
				Appearance3D appearance = new Appearance3D(color);
				appearance.setShine(0.08f);
				SceneObject panel = addHiddenParachuteObject(scene, geometry.canopyPanels().get(i), appearance);
				panels.add(panel);
			}

			List<SceneObject> lines = new ArrayList<>(geometry.suspensionLines().size());
			for (Mesh lineMesh : geometry.suspensionLines()) {
				Appearance3D lineAppearance = new Appearance3D(new Vector3f(0.90f, 0.86f, 0.70f));
				lineAppearance.setUnlit(true);
				lines.add(addHiddenParachuteObject(scene, lineMesh, lineAppearance));
			}
			parachutes.add(new ParachuteCanopy(panels, lines, provider, packedLocation, event.getTime(), end,
					geometry.lineLength()));
		}
	}

	/** Returns the rendered component origin so the harness starts where the packed device sits. */
	static Vector3f findComponentAnchor(SceneView scene, RocketComponent component) {
		if (scene == null || component == null) {
			return new Vector3f();
		}
		for (SceneObject object : scene.getObjects()) {
			if (object.getRocketComponent() == component) {
				return object.getModelMatrix().transformPosition(new Vector3f());
			}
		}
		return new Vector3f();
	}

	private static SceneObject addHiddenParachuteObject(SceneView scene, Mesh mesh, Appearance3D appearance) {
		SceneObject object = new SceneObject(mesh, new Vector3f(), appearance);
		object.setSelectable(false);
		object.setVisible(false);
		scene.addObject(object);
		return object;
	}

	/** Builds an open, shallow canopy with radial suspension lines converging on the stage. */
	static ParachuteGeometry createParachuteGeometry(float rocketLength) {
		float radius = rocketLength * 0.6f;
		float lineLength = rocketLength * 0.9f;
		List<Mesh> panels = new ArrayList<>(PARACHUTE_PANEL_COUNT);
		List<Mesh> lines = new ArrayList<>(PARACHUTE_PANEL_COUNT);
		for (int i = 0; i < PARACHUTE_PANEL_COUNT; i++) {
			float startAngle = (float) (2.0 * Math.PI * i / PARACHUTE_PANEL_COUNT);
			float endAngle = (float) (2.0 * Math.PI * (i + 1) / PARACHUTE_PANEL_COUNT);
			Mesh panel = SphereGenerator.create(radius, 3, 6, 0.0f, (float) (Math.PI / 2.0),
					startAngle, endAngle);
			panels.add(doubleSided(panel));

			float angle = (startAngle + endAngle) * 0.5f;
			Vector3f rim = new Vector3f(
					radius * (float) Math.cos(angle), radius * (float) Math.sin(angle), 0.0f);
			Vector3f harness = new Vector3f(0.0f, 0.0f, -lineLength);
			lines.add(TrajectoryTrailGenerator.create(List.of(rim, harness), radius * 0.008f, 5));
		}
		return new ParachuteGeometry(List.copyOf(panels), List.copyOf(lines), lineLength);
	}

	private static Mesh doubleSided(Mesh mesh) {
		IntList source = mesh.getIndices();
		IntList indices = new IntList(source.size() * 2);
		indices.addAll(source);
		for (int i = 0; i + 2 < source.size(); i += 3) {
			indices.addTriangle(source.get(i), source.get(i + 2), source.get(i + 1));
		}
		return new Mesh(mesh.getVertices(), indices);
	}

	private static PoseProvider providerForEventSource(RocketComponent source, GroundedPoseProviders poses) {
		return providerForStage(stageFor(source), poses.providersByStage(), poses.primaryProvider());
	}

	/** Components without a stage, or stages without their own branch, follow the primary trajectory. */
	private static PoseProvider providerForStage(AxialStage stage, Map<AxialStage, PoseProvider> providersByStage,
			PoseProvider primaryProvider) {
		// The replay data's map is immutable and rejects null lookups.
		PoseProvider provider = stage != null ? providersByStage.get(stage) : null;
		return provider != null ? provider : primaryProvider;
	}

	private static AxialStage stageFor(RocketComponent component) {
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
	 * Adds a burst of white smoke puffs where an ejection charge fires and a smaller one
	 * where a stage separates, so the events read visually along the flight. The bursts ride
	 * the same aging pipeline as the trail puffs.
	 */
	private void addEventBursts(FlightReplayData replayData, GroundedPoseProviders poses, Vector3f centerOffset,
			float puffSize) {
		Vector3f burstColor = new Vector3f(0.96f, 0.96f, 0.97f);
		for (var event : replayData.getAllEvents()) {
			int puffs;
			switch (event.getType()) {
				case EJECTION_CHARGE -> puffs = 12;
				case STAGE_SEPARATION -> puffs = 6;
				default -> {
					continue;
				}
			}
			double t = event.getTime();
			if (t < replayData.getStartTime() || t > replayData.getEndTime()) {
				continue;
			}
			PoseProvider provider = providerForEventSource(event.getSource(), poses);
			Vector3f center = provider.getPosition(t);
			Vector3f bodyCenter = bodyCenterOffset(provider, centerOffset);
			if (bodyCenter != null) {
				center.add(provider.getOrientation(t).transform(new Vector3f(bodyCenter)));
			}
			Random jitter = new Random(Double.hashCode(t) * 127L + puffs);
			Vector3f drift = windFor(provider).velocityAt(t);
			float scatter = puffSize * 1.5f;
			for (int i = 0; i < puffs; i++) {
				Vector3f position = new Vector3f(center).add(
						(jitter.nextFloat() - 0.5f) * 2.0f * scatter,
						(jitter.nextFloat() - 0.5f) * 2.0f * scatter,
						(jitter.nextFloat() - 0.5f) * 2.0f * scatter);
				float size = puffSize * (0.8f + 0.6f * jitter.nextFloat());
				smokePuffs.add(new SmokePuff(position, t, size, burstColor, drift));
			}
		}
	}

	/**
	 * Builds a streaming flame plume for one motor, driven by replay emission times.
	 */
	private void addFlameJet(SceneView scene, RenderingConfiguration config, PoseProvider provider,
			List<double[]> burnWindows, Vector3f nozzleLocal, Vector3f exhaustDirection, float rocketLength) {
		ReplayFlameEmitter emitter = new ReplayFlameEmitter(config, provider, burnWindows,
				nozzleLocal, exhaustDirection, rocketLength, 31L * flameJets.size() + 17,
				ThrustProfile.fromBranch(branchFor(provider), burnWindows));
		scene.addParticleEmitter(emitter);
		flameJets.add(emitter);
	}

	/** Fills the puppet emitters with the exhaust state for the given playback time. */
	private void updateExhaust(double time) {
		SmokeEmitter smoke = smokePuppet;
		if (smoke != null) {
			if (exhaustVisible) updateSmokeParticles(smoke.getParticles(), smokePuffs, time);
			else smoke.getParticles().clear();
		}

		for (ParachuteCanopy parachute : parachutes) {
			boolean deployed = time >= parachute.deployTime() && time <= parachute.endTime();
			parachute.panels().forEach(object -> object.setVisible(deployed));
			parachute.suspensionLines().forEach(object -> object.setVisible(deployed));
			if (!deployed) {
				continue;
			}
			Quaternionf orientation = parachute.provider().getOrientation(time);
			Vector3f position = parachute.provider().getPosition(time)
					.add(orientation.transform(new Vector3f(parachute.packedLocation())));
			for (SceneObject panel : parachute.panels()) {
				panel.getModelMatrix()
						.translation(position.x, position.y + parachute.lineLength(), position.z)
						.rotateX((float) (-Math.PI / 2.0))
						.scale(1.0f, 1.0f, PARACHUTE_CANOPY_FLATTENING);
			}
			for (SceneObject line : parachute.suspensionLines()) {
				line.getModelMatrix()
						.translation(position.x, position.y + parachute.lineLength(), position.z)
						.rotateX((float) (-Math.PI / 2.0));
			}
		}

		for (ReplayFlameEmitter jet : flameJets) {
			jet.setReplayTime(time, exhaustVisible);
		}
	}

	static void updateSmokeParticles(List<Particle> particles, List<SmokePuff> puffs, double time) {
		int count = 0;
		for (SmokePuff puff : puffs) {
			double age = time - puff.birthTime();
			if (age < 0.0 || age >= SMOKE_LIFETIME_SECONDS) {
				continue;
			}
			Particle particle = count < particles.size() ? particles.get(count) : appendBlank(particles);
			particle.position.set(puff.position())
					.fma((float) age, puff.drift())
					.add(0.0f, (float) age * SMOKE_RISE_RATE * puff.size(), 0.0f);
			particle.color.set(puff.color());
			particle.size = puff.size();
			particle.setLifetime(1.0f - smokeAgeRatio(age), 1.0f);
			particle.setOpacity(smokeOpacity(age));
			count++;
		}
		trim(particles, count);
	}

	static float smokeAgeRatio(double age) {
		return SMOKE_FADE_START_RATIO
				* (float) Math.max(0.0, Math.min(1.0, age / SMOKE_GROWTH_SECONDS));
	}

	static float smokeOpacity(double age) {
		double fadeIn = Math.max(0.0, Math.min(1.0, age / 0.15));
		double remaining = Math.max(0.0, Math.min(1.0, 1.0 - age / SMOKE_LIFETIME_SECONDS));
		return (float) (fadeIn * fadeIn * (3.0 - 2.0 * fadeIn)
				* remaining * remaining * (3.0 - 2.0 * remaining));
	}

	private static Particle appendBlank(List<Particle> particles) {
		Particle particle = new Particle(new Vector3f(), new Vector3f(),
				new Vector3f(1.0f, 1.0f, 1.0f), 1.0f, 1.0f);
		particles.add(particle);
		return particle;
	}

	private static void trim(List<Particle> particles, int count) {
		while (particles.size() > count) {
			particles.remove(particles.size() - 1);
		}
	}

	private void setTrailDecorationsVisible(boolean markerVisible) {
		trailsShown = trailVisible;
		applyTrailVisibility();
		for (SceneObject marker : eventMarkers) {
			marker.setVisible(trailVisible);
		}
		if (positionMarker != null) {
			positionMarker.setVisible(markerVisible);
		}
	}

	/**
	 * Places one colored marker sphere on the trajectory for each notable flight event
	 * (burnout, apogee, deployment, ...). Colors match the scrub-slider event ticks.
	 */
	private void addEventMarkers(SceneView scene, FlightReplayData replayData, PoseProvider primary,
			Vector3f centerOffset) {
		eventMarkers.clear();
		boolean visible = trailVisible;
		for (var event : FlightEventMarkers.selectDisplayEvents(replayData.getAllEvents())) {
			double t = event.getTime();
			if (t < replayData.getStartTime() || t > replayData.getEndTime()) {
				continue;
			}
			Vector3f position = primary.getPosition(t);
			if (centerOffset != null) {
				position.add(primary.getOrientation(t).transform(new Vector3f(centerOffset)));
			}
			Mesh mesh = SphereGenerator.create(trailRadius * 1.2f, 12, 8);
			Appearance3D appearance = new Appearance3D(FlightEventMarkers.colorOf(event.getType()));
			appearance.setUnlit(true);
			SceneObject marker = new SceneObject(mesh, new Vector3f(), appearance);
			marker.setSelectable(false);
			marker.setForegroundDecoration(true);
			marker.setVisible(visible);
			marker.getModelMatrix().translation(position);
			scene.addObject(marker);
			eventMarkers.add(marker);
		}
	}

	private List<Vector3f> samplePath(PoseProvider provider, Vector3f centerOffset, double startTime, double endTime) {
		List<Vector3f> points = new ArrayList<>(TRAIL_SAMPLES + 1);
		for (int i = 0; i <= TRAIL_SAMPLES; i++) {
			double t = startTime + (endTime - startTime) * i / TRAIL_SAMPLES;
			Vector3f position = provider.getPosition(t);
			if (centerOffset != null) {
				position.add(provider.getOrientation(t).transform(new Vector3f(centerOffset)));
			}
			points.add(position);
		}
		return points;
	}

	private static int firstDivergenceIndex(List<Vector3f> path, List<Vector3f> reference, float threshold) {
		int count = Math.min(path.size(), reference.size());
		float thresholdSquared = threshold * threshold;
		for (int i = 0; i < count; i++) {
			if (path.get(i).distanceSquared(reference.get(i)) > thresholdSquared) {
				return i;
			}
		}
		return 0;
	}

	private SceneObject addTrailObject(SceneView scene, Mesh mesh, Vector3f color) {
		Appearance3D appearance = new Appearance3D(new Vector3f(color));
		appearance.setUnlit(true);
		SceneObject trailObject = new SceneObject(mesh, new Vector3f(0.0f, 0.0f, 0.0f), appearance);
		trailObject.setSelectable(false);
		trailObject.setForegroundDecoration(true);
		scene.addObject(trailObject);
		return trailObject;
	}

	// Invoked on the render thread every playback frame. The path boundary moves whenever
	// playback time changes so its elapsed/upcoming split stays exactly aligned with the rocket.
	private void onFlightFrame(double time) {
		updateExhaust(time);
		updateTargetMarker(time);
		Scene3DOrchestrator orchestrator = activeOrchestrator;
		PlaybackClock clock = playbackClock;
		if (orchestrator == null || clock == null || trailPaths.isEmpty()) {
			return;
		}
		double span = Math.max(1.0e-9, clock.getEnd() - clock.getStart());
		double fraction = Math.max(0.0, Math.min(1.0, (time - clock.getStart()) / span));
		CameraControls cameraControls = orchestrator.getCameraController();
		Camera camera = cameraControls.getCamera();
		// A narrowed (telephoto) lens magnifies like moving closer; size decorations by that.
		float cameraDistance = camera.getDistance() * (float) (Math.tan(camera.getFieldOfView() / 2.0)
				/ Math.tan(initialCameraFieldOfView / 2.0));
		// A camera transition shows interpolated distances, not the overview's fit.
		if (cameraMode == FlightCameraMode.OVERVIEW && cameraControls.isZoomFitting()
				&& !orchestrator.isFlightCameraTransitioning()) {
			overviewFitDistance = cameraDistance;
		}
		float scale = decorationScale(cameraDistance, overviewFitDistance);
		if (cameraMode != FlightCameraMode.OVERVIEW) {
			// Follow and the pad telephoto are close-ups of the rocket, and the trail runs through
			// its center: keep it a thin guide line.
			scale = Math.min(scale, followTrailScale);
		}
		boolean scaleChanged = relativeDifference(scale, trailDecorationScale)
				>= DECORATION_SCALE_REBUILD_THRESHOLD;
		if (!trailRebuildRequired(fraction, lastRebuildFraction, scaleChanged)) {
			return;
		}
		if (scaleChanged) {
			trailDecorationScale = scale;
			trailDecorationRadius = trailRadius * scale;
			updateMarkerScale(scale);
		}
		lastRebuildFraction = fraction;
		if (scaleChanged || trailGeometries.isEmpty()) {
			rebuildTrails(orchestrator.getScene(), fraction, time);
		} else {
			updateTrailSplit(orchestrator.getScene(), fraction, time);
		}
	}

	/** Decoration scale that makes the trail about 1% of the rocket's length thick in the close-up views. */
	static float followTrailScale(float rocketLength, float trailRadius) {
		if (!Float.isFinite(rocketLength) || rocketLength <= 0.0f || !Float.isFinite(trailRadius) || trailRadius <= 0.0f) {
			return MIN_DECORATION_SCALE;
		}
		return Math.min(1.0f, rocketLength * 0.01f / trailRadius);
	}

	private void updateTargetMarker(double time) {
		FlightTargetMarker marker = targetMarker;
		int index = trackedBodyIndex;
		if (marker == null || index < 0 || index >= trackedBodies.size()) {
			return;
		}
		TrackedBody body = trackedBodies.get(index);
		Vector3f center = body.provider().getPosition(time);
		if (body.centerOffset() != null) {
			center.add(body.provider().getOrientation(time).transform(new Vector3f(body.centerOffset())));
		}
		marker.setTarget(center, rocketLength);
	}

	static float decorationScale(float cameraDistance, float overviewDistance) {
		if (!Float.isFinite(cameraDistance) || !Float.isFinite(overviewDistance)
				|| cameraDistance <= 0.0f || overviewDistance <= 0.0f) {
			return 1.0f;
		}
		return Math.max(MIN_DECORATION_SCALE, Math.min(1.0f, cameraDistance / overviewDistance));
	}

	private static float relativeDifference(float first, float second) {
		return Math.abs(first - second) / Math.max(Math.abs(second), 1.0e-6f);
	}

	static boolean trailRebuildRequired(double fraction, double previousFraction, boolean scaleChanged) {
		return scaleChanged || previousFraction < 0.0 || Double.compare(fraction, previousFraction) != 0;
	}

	private void updateMarkerScale(float scale) {
		for (SceneObject marker : eventMarkers) {
			marker.setUniformScale(scale);
		}
		if (positionMarker != null) {
			positionMarker.setUniformScale(scale);
		}
	}

	/**
	 * Rebuilds every path's chunk tubes at the current decoration radius, in both the bright
	 * "elapsed" and faded "still to come" colors, then places the split at the playback time.
	 * Drawing elapsed and upcoming as separate non-overlapping tubes (rather than overlaying a
	 * bright tube on a faded full-length one) avoids coaxial z-fighting. Runs on the GL thread.
	 */
	private void rebuildTrails(SceneView scene, double fraction, double time) {
		if (scene == null) {
			return;
		}
		for (TrailGeometry geometry : trailGeometries) {
			removeAndCleanupObjects(scene, geometry.splitPieces);
			removeAndCleanupObjects(scene, geometry.elapsedChunks);
			removeAndCleanupObjects(scene, geometry.upcomingChunks);
		}
		trailGeometries.clear();
		trailsShown = trailVisible;

		for (TrailPath trail : trailPaths) {
			List<Vector3f> points = trail.points();
			if (points.size() < 2) {
				continue;
			}
			TrailGeometry geometry = new TrailGeometry(trail);
			for (int chunk = 0; chunk < trailChunkCount(points.size()); chunk++) {
				int start = chunk * TRAIL_CHUNK_SAMPLES;
				List<Vector3f> chunkPoints = points.subList(start, trailChunkEnd(chunk, points.size()) + 1);
				Vector3f seed = trail.ringFrames().get(start);
				geometry.elapsedChunks.add(addTrailPiece(scene, chunkPoints, seed, trailRadius(trail), elapsedColor(trail)));
				geometry.upcomingChunks.add(addTrailPiece(scene, chunkPoints, seed, trailRadius(trail), upcomingColor(trail)));
			}
			trailGeometries.add(geometry);
		}
		updateTrailSplit(scene, fraction, time);
	}

	/**
	 * Moves each path's elapsed/upcoming split to the playback fraction: whole chunks only
	 * change visibility, and the chunk containing the split is replaced by two short pieces
	 * meeting at the body's exact position at the playback time (not a sample or an
	 * interpolation between samples), so the boundary sits precisely on the rocket. Runs on
	 * the GL thread.
	 */
	private void updateTrailSplit(SceneView scene, double fraction, double time) {
		for (TrailGeometry geometry : trailGeometries) {
			removeAndCleanupObjects(scene, geometry.splitPieces);
			TrailPath trail = geometry.path;
			List<Vector3f> points = trail.points();
			int pointCount = points.size();
			int chunkCount = trailChunkCount(pointCount);
			double localFraction = (fraction - trail.startFraction())
					/ Math.max(1.0e-9, 1.0 - trail.startFraction());
			localFraction = Math.max(0.0, Math.min(1.0, localFraction));
			double indexValue = localFraction * (pointCount - 1);
			int index = Math.min((int) Math.floor(indexValue), pointCount - 2);
			geometry.splitChunk = splitChunk(localFraction, index, chunkCount);
			if (geometry.splitChunk < 0 || geometry.splitChunk >= chunkCount) {
				continue;
			}

			Vector3f boundary = trail.positionAt(time);
			int chunkStart = geometry.splitChunk * TRAIL_CHUNK_SAMPLES;
			List<Vector3f> elapsed = new ArrayList<>(points.subList(chunkStart, index + 1));
			elapsed.add(boundary);
			List<Vector3f> upcoming = new ArrayList<>();
			upcoming.add(new Vector3f(boundary));
			upcoming.addAll(points.subList(index + 1, trailChunkEnd(geometry.splitChunk, pointCount) + 1));
			addSplitPiece(scene, geometry, elapsed, trail.ringFrames().get(chunkStart), trailRadius(trail),
					elapsedColor(trail));
			addSplitPiece(scene, geometry, upcoming, trail.ringFrames().get(index), trailRadius(trail),
					upcomingColor(trail));
		}
		applyTrailVisibility();
	}

	/** Number of chunks covering a path of the given sample count; chunks share their end samples. */
	static int trailChunkCount(int pointCount) {
		return pointCount < 2 ? 0 : (pointCount - 2) / TRAIL_CHUNK_SAMPLES + 1;
	}

	/** Index of the last sample in the given chunk. */
	static int trailChunkEnd(int chunk, int pointCount) {
		return Math.min((chunk + 1) * TRAIL_CHUNK_SAMPLES, pointCount - 1);
	}

	/**
	 * Returns the chunk the split falls in for a path segment index; -1 when nothing has elapsed
	 * and the chunk count when everything has, so all chunks then show whole in one color.
	 */
	static int splitChunk(double localFraction, int segmentIndex, int chunkCount) {
		if (localFraction <= 0.0) {
			return -1;
		}
		if (localFraction >= 1.0) {
			return chunkCount;
		}
		return Math.min(segmentIndex / TRAIL_CHUNK_SAMPLES, chunkCount - 1);
	}

	/** All trail tube objects, for tests; call on the GL thread. */
	List<SceneObject> trailObjects() {
		List<SceneObject> objects = new ArrayList<>();
		for (TrailGeometry geometry : trailGeometries) {
			geometry.elapsedChunks.stream().filter(Objects::nonNull).forEach(objects::add);
			geometry.upcomingChunks.stream().filter(Objects::nonNull).forEach(objects::add);
			objects.addAll(geometry.splitPieces);
		}
		return objects;
	}

	private void applyTrailVisibility() {
		for (TrailGeometry geometry : trailGeometries) {
			for (int chunk = 0; chunk < geometry.elapsedChunks.size(); chunk++) {
				setVisibleIfPresent(geometry.elapsedChunks.get(chunk), trailsShown && chunk < geometry.splitChunk);
				setVisibleIfPresent(geometry.upcomingChunks.get(chunk), trailsShown && chunk > geometry.splitChunk);
			}
			for (SceneObject piece : geometry.splitPieces) {
				piece.setVisible(trailsShown);
			}
		}
	}

	private static void setVisibleIfPresent(SceneObject object, boolean visible) {
		if (object != null) {
			object.setVisible(visible);
		}
	}

	private static Vector3f elapsedColor(TrailPath trail) {
		return trail.active() ? ACTIVE_PAST_COLOR : BOOSTER_PAST_COLOR;
	}

	private static Vector3f upcomingColor(TrailPath trail) {
		return trail.active() ? ACTIVE_FUTURE_COLOR : BOOSTER_FUTURE_COLOR;
	}

	static void removeAndCleanupObjects(SceneView scene, List<SceneObject> objects) {
		for (SceneObject object : objects) {
			if (object != null) {
				scene.removeObject(object);
				object.cleanup();
			}
		}
		objects.clear();
	}

	/**
	 * A separated body climbs along the path the sustainer just flew, so their tubes coincide
	 * there; drawing it thinner lets the main trail cover it instead of the two flickering.
	 */
	private float trailRadius(TrailPath trail) {
		return trail.active() ? trailDecorationRadius : trailDecorationRadius * SEPARATED_TRAIL_RADIUS_SCALE;
	}

	private void addSplitPiece(SceneView scene, TrailGeometry geometry, List<Vector3f> points, Vector3f seed,
			float radius, Vector3f color) {
		SceneObject piece = addTrailPiece(scene, points, seed, radius, color);
		if (piece != null) {
			geometry.splitPieces.add(piece);
		}
	}

	/** Adds a hidden tube along the points, or returns null when they span no length. */
	private SceneObject addTrailPiece(SceneView scene, List<Vector3f> points, Vector3f seed, float radius,
			Vector3f color) {
		if (points.size() < 2) {
			return null;
		}
		Mesh mesh = TrajectoryTrailGenerator.create(points, radius, 8, seed);
		if (mesh.getVertices().isEmpty()) {
			return null;
		}
		SceneObject trailObject = addTrailObject(scene, mesh, color);
		trailObject.setVisible(false);
		return trailObject;
	}

	private void addGroundReference(SceneView scene, FlightData data) {
		float size = computeGroundSize(data);
		// CLOCKWISE winding makes the plane's up-facing side the front face (same as
		// TerrainGenerator). Double-sided so an orbit below the ground still shows it
		// instead of culling it away.
		Mesh groundMesh = doubleSided(PlaneGenerator.create(size, size, 1.0f, 1.0f,
				GeometryConstants.WindingOrder.CLOCKWISE));
		Appearance3D groundAppearance = new Appearance3D(new Vector3f(0.22f, 0.30f, 0.20f));
		groundAppearance.setUnlit(true);
		groundAppearance.setShine(0.05f);
		SceneObject ground = new SceneObject(groundMesh, new Vector3f(0.0f, 0.0f, 0.0f), groundAppearance);
		ground.setSelectable(false);
		scene.addObject(ground);
	}

	private GroundedPoseProviders createGroundedPoseProviders(SceneView scene,
			Map<AxialStage, PoseProvider> providersByStage, PoseProvider primaryProvider, double startTime) {
		float groundLift = computeStartGroundLift(scene, providersByStage, primaryProvider, startTime);
		if (groundLift <= 1.0e-4f) {
			return new GroundedPoseProviders(providersByStage, primaryProvider);
		}

		// Wrap each trajectory once: stages flying together must keep sharing one provider,
		// which is how the trails and tracked bodies tell the bodies apart.
		Vector3f offset = new Vector3f(0.0f, groundLift, 0.0f);
		Map<PoseProvider, PoseProvider> lifted = new IdentityHashMap<>();
		Map<AxialStage, PoseProvider> adjusted = new LinkedHashMap<>();
		for (Map.Entry<AxialStage, PoseProvider> entry : providersByStage.entrySet()) {
			adjusted.put(entry.getKey(),
					lifted.computeIfAbsent(entry.getValue(), provider -> new OffsetPoseProvider(provider, offset)));
		}
		return new GroundedPoseProviders(adjusted,
				lifted.computeIfAbsent(primaryProvider, provider -> new OffsetPoseProvider(provider, offset)));
	}

	private float computeStartGroundLift(SceneView scene, Map<AxialStage, PoseProvider> providersByStage,
			PoseProvider primaryProvider, double startTime) {
		float minY = Float.POSITIVE_INFINITY;
		int contributingObjects = 0;
		Matrix4f dynamicTransform = new Matrix4f();
		Matrix4f modelTransform = new Matrix4f();
		Vector3f boundsMin = new Vector3f();
		Vector3f boundsMax = new Vector3f();

		for (SceneObject obj : scene.getObjects()) {
			RocketComponent component = obj.getRocketComponent();
			if (component == null) {
				continue;
			}
			Mesh mesh = obj.getMesh();
			if (mesh == null) {
				continue;
			}
			// Fall back to the primary (sustainer) trajectory when a component's stage has no
			// dedicated provider, so the lift is always measured from real geometry and never
			// silently collapses to zero (which would leave the rocket sunk into the ground).
			PoseProvider provider = providerForStage(stageFor(component), providersByStage, primaryProvider);
			dynamicTransform.identity()
					.translate(provider.getPosition(startTime))
					.rotate(provider.getOrientation(startTime));
			modelTransform.set(dynamicTransform).mul(obj.getModelMatrix());

			mesh.getBoundsMin(boundsMin);
			mesh.getBoundsMax(boundsMax);
			minY = Math.min(minY, lowestTransformedCornerY(boundsMin, boundsMax, modelTransform));
			contributingObjects++;
		}

		float groundLift = (!Float.isFinite(minY) || minY >= 0.0f) ? 0.0f : -minY;
		log.info("Flight replay ground seating: {} rocket object(s), lowest Y {}, applying lift {}",
				contributingObjects, Float.isFinite(minY) ? minY : Float.NaN, groundLift);
		return groundLift;
	}

	/**
	 * Returns the minimum world-space Y of a mesh's axis-aligned bounds after the given
	 * transform. Pure geometry (no GL state), so it is unit-testable in isolation.
	 */
	static float lowestTransformedCornerY(Vector3f boundsMin, Vector3f boundsMax, Matrix4f transform) {
		float minY = Float.POSITIVE_INFINITY;
		Vector3f corner = new Vector3f();
		for (int x = 0; x < 2; x++) {
			for (int y = 0; y < 2; y++) {
				for (int z = 0; z < 2; z++) {
					corner.set(
							x == 0 ? boundsMin.x : boundsMax.x,
							y == 0 ? boundsMin.y : boundsMax.y,
							z == 0 ? boundsMin.z : boundsMax.z);
					transform.transformPosition(corner);
					minY = Math.min(minY, corner.y);
				}
			}
		}
		return minY;
	}

	private float computeGroundSize(FlightData data) {
		return Math.max(MIN_GROUND_SIZE,
				(float) (computeMaxHorizontalMeters(data) * RenderingConstants.WORLD_SCALE * 3.0));
	}

	private static double computeMaxHorizontalMeters(FlightData data) {
		double maxHorizontalMeters = 0.0;
		for (FlightDataBranch branch : data.getBranches()) {
			List<Double> east = branch.get(FlightDataType.TYPE_POSITION_X);
			List<Double> north = branch.get(FlightDataType.TYPE_POSITION_Y);
			if (east != null && north != null) {
				int count = Math.min(east.size(), north.size());
				for (int i = 0; i < count; i++) {
					double x = valueOrZero(east.get(i));
					double y = valueOrZero(north.get(i));
					maxHorizontalMeters = Math.max(maxHorizontalMeters, Math.hypot(x, y));
				}
				continue;
			}
			List<Double> horizontal = branch.get(FlightDataType.TYPE_POSITION_XY);
			if (horizontal != null) {
				for (Double value : horizontal) {
					maxHorizontalMeters = Math.max(maxHorizontalMeters, valueOrZero(value));
				}
			}
		}
		return maxHorizontalMeters;
	}

	private static double valueOrZero(Double value) {
		if (value == null || Double.isNaN(value) || Double.isInfinite(value)) {
			return 0.0;
		}
		return value;
	}

	private void applyFlightBackground(SceneView scene) {
		scene.setBackground(GradientBackground.worldAligned(
				new Vector3f(0.18f, 0.48f, 0.82f),
				new Vector3f(0.76f, 0.87f, 0.96f)));
	}

	private Map<AxialStage, List<double[]>> toStageTimeline(
			Map<AxialStage, List<FlightReplayData.BurnInterval>> intervalsByStage) {
		Map<AxialStage, List<double[]>> timeline = new LinkedHashMap<>();
		for (Map.Entry<AxialStage, List<FlightReplayData.BurnInterval>> entry : intervalsByStage.entrySet()) {
			List<double[]> stageIntervals = new ArrayList<>(entry.getValue().size());
			for (FlightReplayData.BurnInterval interval : entry.getValue()) {
				stageIntervals.add(new double[] { interval.start(), interval.end() });
			}
			timeline.put(entry.getKey(), stageIntervals);
		}
		return timeline;
	}

	/**
	 * Wraps each flying body's trajectory so the body rotates about its own center (see
	 * {@link BodyCenteredPoseProvider}), following which stages it is attached to over time.
	 * Runs on the GL thread before the first posed frame, while object transforms are still
	 * rocket-local.
	 */
	private static CenteredPoses centerBodiesOnThemselves(SceneView scene, FlightReplayData replayData,
			Vector3f fallbackCenter) {
		Map<PoseProvider, PoseProvider> centeredByOriginal = new IdentityHashMap<>();
		List<Vector3f> bodyCenters = new ArrayList<>();
		for (FlightReplayData.FlightBody body : replayData.getFlightBodies()) {
			AxialStage stage = body.stages().get(0);
			List<AxialStage> group = replayData.getAttachedStages(stage, replayData.getStartTime());
			List<Double> switchTimes = new ArrayList<>();
			List<Vector3f> centers = new ArrayList<>();
			centers.add(stageGroupCenter(scene, Set.copyOf(group), fallbackCenter));
			for (double time : replayData.getSeparationTimes()) {
				List<AxialStage> attached = replayData.getAttachedStages(stage, time);
				if (!attached.equals(group)) {
					switchTimes.add(time);
					centers.add(stageGroupCenter(scene, Set.copyOf(attached), fallbackCenter));
					group = attached;
				}
			}
			BodyCenteredPoseProvider centered = new BodyCenteredPoseProvider(body.provider(), switchTimes, centers);
			centeredByOriginal.put(body.provider(), centered);
			bodyCenters.add(centered.getBodyCenter());
		}
		Map<AxialStage, PoseProvider> byStage = new LinkedHashMap<>();
		replayData.getProvidersByStage().forEach((stage, provider) ->
				byStage.put(stage, centeredByOriginal.getOrDefault(provider, provider)));
		PoseProvider primary = replayData.getPrimaryProvider();
		return new CenteredPoses(byStage, centeredByOriginal.getOrDefault(primary, primary), bodyCenters);
	}

	/** Resolves each flying body to its ground-adjusted trajectory and its own center. */
	private void collectTrackedBodies(FlightData data, FlightReplayData replayData, GroundedPoseProviders poses,
			List<Vector3f> bodyCenters) {
		trackedBodies.clear();
		List<FlightReplayData.FlightBody> bodies = replayData.getFlightBodies();
		for (int i = 0; i < bodies.size(); i++) {
			PoseProvider provider = providerForStage(bodies.get(i).stages().get(0), poses.providersByStage(),
					poses.primaryProvider());
			FlightDataBranch branch = data.getBranch(bodies.get(i).branchIndex());
			trackedBodies.add(new TrackedBody(provider, bodyCenters.get(i), WindField.fromBranch(branch), branch));
		}
		trackedBodyIndex = 0;
	}

	private record CenteredPoses(Map<AxialStage, PoseProvider> providersByStage, PoseProvider primaryProvider,
			List<Vector3f> bodyCenters) {
	}

	/** Center of the rocket-local bounds of every object in the given stages, or the fallback. */
	private static Vector3f stageGroupCenter(SceneView scene, Set<AxialStage> stages, Vector3f fallback) {
		Vector3f min = new Vector3f(Float.POSITIVE_INFINITY);
		Vector3f max = new Vector3f(Float.NEGATIVE_INFINITY);
		Vector3f boundsMin = new Vector3f();
		Vector3f boundsMax = new Vector3f();
		Vector3f corner = new Vector3f();
		for (SceneObject object : scene.getObjects()) {
			Mesh mesh = object.getMesh();
			// Scenery has no stage, and the immutable set rejects null lookups.
			AxialStage stage = stageFor(object.getRocketComponent());
			if (mesh == null || stage == null || !stages.contains(stage)) {
				continue;
			}
			mesh.getBoundsMin(boundsMin);
			mesh.getBoundsMax(boundsMax);
			for (int i = 0; i < 8; i++) {
				corner.set((i & 1) == 0 ? boundsMin.x : boundsMax.x,
						(i & 2) == 0 ? boundsMin.y : boundsMax.y,
						(i & 4) == 0 ? boundsMin.z : boundsMax.z);
				object.getModelMatrix().transformPosition(corner);
				min.min(corner);
				max.max(corner);
			}
		}
		if (!Float.isFinite(min.x) || !Float.isFinite(max.x)) {
			return fallback;
		}
		return min.add(max).mul(0.5f);
	}

	/** The geometric center of the body flying on the given trajectory, or the fallback. */
	private Vector3f bodyCenterOffset(PoseProvider provider, Vector3f fallback) {
		for (TrackedBody body : trackedBodies) {
			if (body.provider() == provider && body.centerOffset() != null) {
				return body.centerOffset();
			}
		}
		return fallback;
	}

	/** Tracks another flying body with the follow and pad cameras and the position marker. */
	void setTrackedBody(int index) {
		trackedBodyIndex = index;
		Scene3DOrchestrator orchestrator = activeOrchestrator;
		if (orchestrator != null) {
			orchestrator.enqueueGlTask(() -> applyTrackedBody(orchestrator));
		}
		requestRenderNow();
	}

	private void applyTrackedBody(Scene3DOrchestrator orchestrator) {
		int index = trackedBodyIndex;
		if (index < 0 || index >= trackedBodies.size()) {
			return;
		}
		TrackedBody body = trackedBodies.get(index);
		orchestrator.setFlightTrackTarget(body.provider(), body.centerOffset());
		if (positionMarker != null) {
			positionMarker.setBasePosition(body.centerOffset() != null ? body.centerOffset() : new Vector3f());
			positionMarker.setPoseProvider(body.provider());
		}
	}

	private record TrackedBody(PoseProvider provider, Vector3f centerOffset, WindField wind,
			FlightDataBranch branch) {
	}

	/** The simulation branch of the body on the given trajectory, or the primary branch. */
	private FlightDataBranch branchFor(PoseProvider provider) {
		for (TrackedBody body : trackedBodies) {
			if (body.provider() == provider) {
				return body.branch();
			}
		}
		return trackedBodies.isEmpty() ? null : trackedBodies.get(0).branch();
	}

	/** The wind recorded along the flight of the body on the given trajectory. */
	private WindField windFor(PoseProvider provider) {
		for (TrackedBody body : trackedBodies) {
			if (body.provider() == provider) {
				return body.wind();
			}
		}
		return trackedBodies.isEmpty() ? WindField.calm() : trackedBodies.get(0).wind();
	}

	private record GroundedPoseProviders(Map<AxialStage, PoseProvider> providersByStage,
										 PoseProvider primaryProvider) {
	}

	private static final class OffsetPoseProvider implements PoseProvider {
		private final PoseProvider delegate;
		private final Vector3f offset;

		private OffsetPoseProvider(PoseProvider delegate, Vector3f offset) {
			this.delegate = delegate;
			this.offset = new Vector3f(offset);
		}

		@Override
		public Vector3f getPosition(double t) {
			return new Vector3f(delegate.getPosition(t)).add(offset);
		}

		@Override
		public Quaternionf getOrientation(double t) {
			return delegate.getOrientation(t);
		}

		@Override
		public Vector3f getLinearVelocity(double t) {
			return delegate.getLinearVelocity(t);
		}

		@Override
		public Vector3f getAngularVelocity(double t) {
			return delegate.getAngularVelocity(t);
		}

		@Override
		public double getStartTime() {
			return delegate.getStartTime();
		}

		@Override
		public double getEndTime() {
			return delegate.getEndTime();
		}
	}

	private static void debug(String message) {
		if (!DEBUG) {
			return;
		}
		System.out.println("[Flight3DPanel][" + Thread.currentThread().getName() + "] " + message);
	}
}
