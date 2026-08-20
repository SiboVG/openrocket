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
import info.openrocket.swing.gui.figure3d.particles.flame.FlameEmitter;
import info.openrocket.swing.gui.figure3d.particles.flame.FlameSettings;
import info.openrocket.swing.gui.figure3d.particles.smoke.SmokeEmitter;
import info.openrocket.swing.gui.figure3d.particles.smoke.SmokeSettings;
import info.openrocket.swing.gui.figure3d.materials.Appearance3D;
import info.openrocket.swing.gui.figure3d.rendering.backgrounds.GradientBackground;
import info.openrocket.swing.gui.figure3d.scene.controllers.CameraControls;
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
	private volatile Vector3f trajectoryCenter;
	private volatile Vector3f trajectoryDimensions;

	private static final int TRAIL_SAMPLES = 240;
	private static final float MIN_DECORATION_SCALE = 0.04f;
	private static final float DECORATION_SCALE_REBUILD_THRESHOLD = 0.06f;
	private static final Vector3f ACTIVE_FUTURE_COLOR = new Vector3f(0.16f, 0.42f, 0.28f);
	private static final Vector3f ACTIVE_PAST_COLOR = new Vector3f(0.35f, 1.0f, 0.55f);
	private static final Vector3f BOOSTER_FUTURE_COLOR = new Vector3f(0.40f, 0.24f, 0.12f);
	private static final Vector3f BOOSTER_PAST_COLOR = new Vector3f(1.0f, 0.55f, 0.18f);

	// Deterministic exhaust rendered through the real particle renderers: puff positions and
	// birth times are laid along the flown path up front, flame plume particles are posed
	// rigidly against the current rocket pose, and "puppet" emitters (whose simulation is a
	// no-op) expose them to the volumetric smoke and flame renderers. Everything shown is a
	// pure function of the playback time, so scrubbing is exact.
	private static final Vector3f SMOKE_COLOR = new Vector3f(0.80f, 0.80f, 0.83f);
	private static final Vector3f FLAME_CORE_COLOR = new Vector3f(1.0f, 0.95f, 0.75f);
	private static final Vector3f FLAME_TIP_COLOR = new Vector3f(1.0f, 0.45f, 0.10f);
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
	private static final int MAX_PUFFS_PER_BURN = 400;
	private static final int SMOKE_PARTICLES_PER_PUFF = 3;
	private static final int FLAME_PLUME_PARTICLES = 240;
	// The default flame exposure is tuned for the pad view's tightly packed plume; the
	// replay plume spreads its particles wider, so it needs more exposure to read as fire.
	private static final float FLAME_EXPOSURE = 0.2f;
	private static final int PARACHUTE_PANEL_COUNT = 8;
	private static final float PARACHUTE_CANOPY_FLATTENING = 0.42f;

	private final List<TrailPath> trailPaths = new ArrayList<>();
	private final List<SceneObject> dynamicTrails = new ArrayList<>();
	private final List<SmokePuff> smokePuffs = new ArrayList<>();
	private final List<FlameJet> flameJets = new ArrayList<>();
	private final List<SceneObject> eventMarkers = new ArrayList<>();
	private final List<ParachuteCanopy> parachutes = new ArrayList<>();
	private final AtomicBoolean dirty = new AtomicBoolean(true);
	private SmokeEmitter smokePuppet;
	private SceneObject positionMarker;
	private FlightOrientationGizmo orientationGizmo;
	private volatile PlaybackClock playbackClock;
	private volatile Scene3DOrchestrator activeOrchestrator;
	private float trailRadius = 1.0f;
	private float trailDecorationScale = 1.0f;
	private float trailDecorationRadius = 1.0f;
	private float overviewFitDistance = Float.NaN;
	private double lastRebuildFraction = -1.0;

	private record TrailPath(List<Vector3f> points, boolean active, double startFraction) {
	}

	/** One smoke particle of the trail: a fixed world position revealed at its birth time. */
	record SmokePuff(Vector3f position, double birthTime, float size, Vector3f color) {
	}

	/** One particle of a flame plume, in the rocket's local frame (nose toward -X). */
	private record FlameShapePoint(Vector3f localOffset, float ageRatio, float size) {
	}

	private record FlameJet(FlameEmitter emitter, PoseProvider provider, List<double[]> burnWindows,
			List<FlameShapePoint> shape) {
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
		stopRenderLoop();
		if (glPanel != null) {
			RENDER_SCHEDULER.awaitQuiescence(RENDER_SHUTDOWN_TIMEOUT_MS);
			disposeCurrentCanvas(glPanel);
		}
		pendingCanvasRebuild.set(null);
		trailPaths.clear();
		dynamicTrails.clear();
		smokePuffs.clear();
		flameJets.clear();
		eventMarkers.clear();
		parachutes.clear();
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
		if (clock != null && clock.getRate() != 0.0) {
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
		GroundedPoseProviders groundedPoses = createGroundedPoseProviders(scene, replayData);
		orchestrator.bindFlightPosesToRocket(groundedPoses.providersByStage(), groundedPoses.primaryProvider(),
				replayData.getStartTime(), replayData.getEndTime());
		Map<AxialStage, List<double[]>> burnTimeline = toStageTimeline(replayData.getBurnIntervalsByStage());
		int burnWindowCount = burnTimeline.values().stream().mapToInt(List::size).sum();
		log.info("Flight replay: {} stage(s) with {} total motor burn window(s)", burnTimeline.size(), burnWindowCount);

		// Reuse the design-view rocket-center computation so the follow camera orbits the
		// rocket's middle, not its nose. Compute the whole-flight framing for the default view.
		Vector3f rocketCenterOffset = orchestrator.getCameraController().computeRocketCenter();
		orchestrator.setFlightRocketCenterOffset(rocketCenterOffset);
		computeTrajectoryBounds(orchestrator.getCameraController(), groundedPoses,
				replayData.getStartTime(), replayData.getEndTime());
		buildTrajectoryTrails(scene, groundedPoses, rocketCenterOffset,
				replayData.getStartTime(), replayData.getEndTime());
		addEventMarkers(scene, replayData, groundedPoses.primaryProvider(), rocketCenterOffset);
		buildExhaustGeometry(scene, orchestrator, config, groundedPoses,
				replayData, burnTimeline, rocketCenterOffset);
		applyCameraMode(orchestrator, cameraMode);

		PlaybackClock clock = orchestrator.getPlaybackClock();
		if (clock != null) {
			clock.setRate(0.0);
		}
		this.playbackClock = clock;
		this.activeOrchestrator = orchestrator;
		this.lastRebuildFraction = -1.0;
		orchestrator.setFlightFrameListener(this::onFlightFrame);

		orientationGizmo = new FlightOrientationGizmo();
		orchestrator.getRenderer().setFrameOverlay(orientationGizmo);

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
			applyCameraMode(orchestrator, mode);
		}
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

	void fitView() {
		Scene3DOrchestrator orchestrator = activeOrchestrator;
		if (orchestrator != null) {
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

	private void applyCameraMode(Scene3DOrchestrator orchestrator, FlightCameraMode mode) {
		// The path trail runs through the rocket's center, so it clips the rocket up close:
		// show it only in the distant views, and the position marker only in the overview.
		boolean distantView = mode == FlightCameraMode.OVERVIEW || mode == FlightCameraMode.PAD;
		boolean markerView = mode == FlightCameraMode.OVERVIEW;
		orchestrator.enqueueGlTask(() -> {
			setTrailDecorationsVisible(distantView, markerView);
			if (distantView) {
				lastRebuildFraction = -1.0;
			}
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

		List<PoseProvider> providers = new ArrayList<>(poses.providersByStage().values());
		providers.add(poses.primaryProvider());
		int samples = 240;
		for (PoseProvider provider : providers) {
			for (int i = 0; i <= samples; i++) {
				double t = startTime + (endTime - startTime) * i / samples;
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
		dynamicTrails.clear();
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
		trailPaths.add(new TrailPath(primaryPath, true, 0.0));

		Set<PoseProvider> boosters = Collections.newSetFromMap(new IdentityHashMap<>());
		boosters.addAll(poses.providersByStage().values());
		boosters.remove(primary);
		for (PoseProvider booster : boosters) {
			List<Vector3f> full = samplePath(booster, centerOffset, startTime, endTime);
			// Only plot a booster from where its path diverges from the sustainer (post-separation),
			// since before separation it rides the same path and would z-fight the active trail.
			int from = firstDivergenceIndex(full, primaryPath, trailRadius * 3.0f);
			List<Vector3f> divergent = new ArrayList<>(full.subList(Math.max(0, from), full.size()));
			// The booster path covers global playback fractions [from/samples, 1], so elapsed
			// coloring only starts once the flight passes its separation point.
			trailPaths.add(new TrailPath(divergent, false, (double) from / TRAIL_SAMPLES));
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

		rebuildTrails(scene, 0.0);
	}

	/**
	 * Builds the replay's exhaust: for each stage's burn window, smoke puff positions laid
	 * along the flown path at fixed spatial spacing, plus one flame plume shape per burning
	 * stage. The visuals come from the real volumetric-smoke and flame renderers via
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
		// The smoke renderer draws a particle at up to 4x its size; target a full-grown puff
		// of ~2 trail radii so the column reads at the whole-flight zoom.
		float puffSize = trailRadius * 0.5f;
		float spacing = trailRadius * 0.9f;

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
			PoseProvider provider = poses.providersByStage().getOrDefault(entry.getKey(), poses.primaryProvider());
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
		Vector3f previous = null;
		double sinceLastPuff = spacing; // place a puff right at ignition
		int stations = 0;
		for (int i = 0; i <= SMOKE_PATH_SAMPLES && stations < MAX_PUFFS_PER_BURN; i++) {
			double t = burnStart + (burnEnd - burnStart) * i / SMOKE_PATH_SAMPLES;
			Vector3f position = provider.getPosition(t);
			position.add(provider.getOrientation(t).transform(new Vector3f(nozzleLocal)));
			if (previous != null) {
				sinceLastPuff += position.distance(previous);
			}
			previous = position;
			if (sinceLastPuff < spacing) {
				continue;
			}
			sinceLastPuff = 0.0;
			stations++;

			for (int j = 0; j < SMOKE_PARTICLES_PER_PUFF; j++) {
				float size = puffSize * (0.7f + 0.6f * jitter.nextFloat());
				Vector3f puffCenter = new Vector3f(position).add(
						(jitter.nextFloat() - 0.5f) * spacing,
						(jitter.nextFloat() - 0.5f) * spacing,
						(jitter.nextFloat() - 0.5f) * spacing);
				smokePuffs.add(new SmokePuff(puffCenter, t, size, SMOKE_COLOR));
			}
		}
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
		if (source != null) {
			try {
				AxialStage stage = source instanceof AxialStage axialStage ? axialStage : source.getStage();
				PoseProvider provider = poses.providersByStage().get(stage);
				if (provider != null) {
					return provider;
				}
			} catch (IllegalStateException e) {
				// Component not attached to a stage; fall through to the primary trajectory.
			}
		}
		return poses.primaryProvider();
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
			if (centerOffset != null) {
				center.add(provider.getOrientation(t).transform(new Vector3f(centerOffset)));
			}
			Random jitter = new Random(Double.hashCode(t) * 127L + puffs);
			float scatter = trailRadius * 1.5f;
			for (int i = 0; i < puffs; i++) {
				Vector3f position = new Vector3f(center).add(
						(jitter.nextFloat() - 0.5f) * 2.0f * scatter,
						(jitter.nextFloat() - 0.5f) * 2.0f * scatter,
						(jitter.nextFloat() - 0.5f) * 2.0f * scatter);
				float size = puffSize * (0.8f + 0.6f * jitter.nextFloat());
				smokePuffs.add(new SmokePuff(position, t, size, burstColor));
			}
		}
	}

	/**
	 * Builds one flame plume for a stage: a fixed cloud of particles distributed along the
	 * plume axis in the rocket's local frame (throat at the nozzle, tapering tip), rendered
	 * by the flame renderer whose size/temperature profile is driven by each particle's age
	 * ratio. Posed rigidly against the stage's current pose every frame, so it stays glued
	 * to the nozzle no matter how the rocket accelerates.
	 */
	private void addFlameJet(SceneView scene, RenderingConfiguration config, PoseProvider provider,
			List<double[]> burnWindows, Vector3f nozzleLocal, Vector3f exhaustDirection, float rocketLength) {
		float plumeLength = rocketLength * 0.9f;
		float plumeRadius = rocketLength * 0.10f;
		float particleSize = rocketLength * 0.09f;
		// The renderer ramps size and alpha up over the first stretch of the plume, so start
		// the shape inside the rocket: the visible flame then begins right at the motor.
		float plumeStart = -0.12f * plumeLength;

		Random shapeRandom = new Random(31L * flameJets.size() + 17);
		Vector3f axis = new Vector3f(exhaustDirection).normalize();
		Vector3f side = new Vector3f(axis).cross(Math.abs(axis.y) < 0.9f
				? new Vector3f(0.0f, 1.0f, 0.0f) : new Vector3f(0.0f, 0.0f, 1.0f)).normalize();
		Vector3f up = new Vector3f(axis).cross(side).normalize();
		List<FlameShapePoint> shape = new ArrayList<>(FLAME_PLUME_PARTICLES);
		for (int i = 0; i < FLAME_PLUME_PARTICLES; i++) {
			float along = (i + shapeRandom.nextFloat()) / FLAME_PLUME_PARTICLES;
			float scatter = plumeRadius * (0.2f + 0.8f * along);
			Vector3f offset = new Vector3f(nozzleLocal)
					.add(new Vector3f(axis).mul(plumeStart + along * plumeLength))
					.add(new Vector3f(side).mul((shapeRandom.nextFloat() - 0.5f) * scatter))
					.add(new Vector3f(up).mul((shapeRandom.nextFloat() - 0.5f) * scatter));
			float size = particleSize * (0.8f + 0.4f * shapeRandom.nextFloat());
			shape.add(new FlameShapePoint(offset, along, size));
		}

		FlameEmitter emitter = new FlameEmitter(new Vector3f(), new Vector3f(1.0f, 0.0f, 0.0f),
				FlameSettings.normal(config)) {
			@Override
			public void update(float deltaTime) {
				// Scripted: the replay fills the particles as a function of playback time.
			}
		};
		scene.addParticleEmitter(emitter);
		flameJets.add(new FlameJet(emitter, provider, burnWindows, shape));
	}

	/** Fills the puppet emitters with the exhaust state for the given playback time. */
	private void updateExhaust(double time) {
		SmokeEmitter smoke = smokePuppet;
		if (smoke != null) {
			updateSmokeParticles(smoke.getParticles(), smokePuffs, time, trailRadius);
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

		for (FlameJet jet : flameJets) {
			List<Particle> particles = jet.emitter().getParticles();
			if (!isWithinAnyWindow(jet.burnWindows(), time)) {
				particles.clear();
				continue;
			}
			Vector3f base = jet.provider().getPosition(time);
			Quaternionf orientation = jet.provider().getOrientation(time);
			int count = 0;
			for (FlameShapePoint point : jet.shape()) {
				Particle particle = count < particles.size() ? particles.get(count) : appendBlank(particles);
				Vector3f local = new Vector3f(point.localOffset());
				orientation.transform(local);
				particle.position.set(base).add(local);
				// Hot core at the throat, cooling toward the tip; the flame renderer derives
				// its size and temperature profile from the age ratio.
				particle.color.set(FLAME_CORE_COLOR).lerp(FLAME_TIP_COLOR, point.ageRatio());
				particle.size = point.size();
				particle.setLifetime(1.0f - point.ageRatio(), 1.0f);
				count++;
			}
			trim(particles, count);
		}
	}

	static void updateSmokeParticles(List<Particle> particles, List<SmokePuff> puffs,
			double time, float radius) {
		int count = 0;
		for (SmokePuff puff : puffs) {
			double age = time - puff.birthTime();
			if (age < 0.0 || age >= SMOKE_LIFETIME_SECONDS) {
				continue;
			}
			Particle particle = count < particles.size() ? particles.get(count) : appendBlank(particles);
			particle.position.set(puff.position())
					.add(0.0f, (float) Math.min(age, 30.0) * SMOKE_RISE_RATE * radius, 0.0f);
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
		return (float) Math.max(0.0, Math.min(1.0, 1.0 - age / SMOKE_LIFETIME_SECONDS));
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

	private static boolean isWithinAnyWindow(List<double[]> windows, double time) {
		for (double[] window : windows) {
			if (time >= window[0] && time <= window[1]) {
				return true;
			}
		}
		return false;
	}

	// The trajectory decorations show in the distant views; the rocket position marker only
	// in the whole-flight overview (up close the rocket itself is visible and the marker,
	// sized for the trajectory scale, would dwarf it).
	private boolean isDistantView() {
		return cameraMode == FlightCameraMode.OVERVIEW || cameraMode == FlightCameraMode.PAD;
	}

	private void setTrailDecorationsVisible(boolean trailsVisible, boolean markerVisible) {
		for (SceneObject trailObject : dynamicTrails) {
			trailObject.setVisible(trailsVisible);
		}
		for (SceneObject marker : eventMarkers) {
			marker.setVisible(trailsVisible);
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
		boolean visible = isDistantView();
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

	// Invoked on the render thread every playback frame. The path boundary is rebuilt whenever
	// playback time changes so its elapsed/upcoming split stays exactly aligned with the rocket.
	private void onFlightFrame(double time) {
		updateExhaust(time);
		if (!isDistantView()) {
			return;
		}
		Scene3DOrchestrator orchestrator = activeOrchestrator;
		PlaybackClock clock = playbackClock;
		if (orchestrator == null || clock == null || trailPaths.isEmpty()) {
			return;
		}
		double span = Math.max(1.0e-9, clock.getEnd() - clock.getStart());
		double fraction = Math.max(0.0, Math.min(1.0, (time - clock.getStart()) / span));
		CameraControls cameraControls = orchestrator.getCameraController();
		float cameraDistance = cameraControls.getCamera().getDistance();
		if (cameraMode == FlightCameraMode.OVERVIEW && cameraControls.isZoomFitting()) {
			overviewFitDistance = cameraDistance;
		}
		float scale = decorationScale(cameraDistance, overviewFitDistance);
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
		rebuildTrails(orchestrator.getScene(), fraction);
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
	 * Rebuilds each path as two non-overlapping tubes meeting end-to-end at the current playback
	 * time: a bright "elapsed" segment and a faded "still to come" segment. The join is an exact
	 * interpolated point on the path (not a sample), so the boundary sits precisely under the
	 * moving marker. Drawing them as separate segments (rather than overlaying a bright tube on a
	 * faded full-length one) avoids coaxial z-fighting. Runs on the GL thread.
	 */
	private void rebuildTrails(SceneView scene, double fraction) {
		if (scene == null) {
			return;
		}
		removeAndCleanupObjects(scene, dynamicTrails);

		boolean visible = isDistantView();
		for (TrailPath trail : trailPaths) {
			List<Vector3f> points = trail.points();
			int pointCount = points.size();
			if (pointCount < 2) {
				continue;
			}
			double localFraction = (fraction - trail.startFraction())
					/ Math.max(1.0e-9, 1.0 - trail.startFraction());
			localFraction = Math.max(0.0, Math.min(1.0, localFraction));
			double indexValue = localFraction * (pointCount - 1);
			int index = Math.min((int) Math.floor(indexValue), pointCount - 2);
			Vector3f boundary = new Vector3f(points.get(index))
					.lerp(points.get(index + 1), (float) (indexValue - index));

			if (localFraction > 0.0) {
				List<Vector3f> elapsed = new ArrayList<>(points.subList(0, index + 1));
				elapsed.add(boundary);
				addTrailSegment(scene, elapsed, trail.active() ? ACTIVE_PAST_COLOR : BOOSTER_PAST_COLOR, visible);
			}
			if (localFraction < 1.0) {
				List<Vector3f> upcoming = new ArrayList<>();
				upcoming.add(new Vector3f(boundary));
				upcoming.addAll(points.subList(index + 1, pointCount));
				addTrailSegment(scene, upcoming, trail.active() ? ACTIVE_FUTURE_COLOR : BOOSTER_FUTURE_COLOR, visible);
			}
		}
	}

	static void removeAndCleanupObjects(SceneView scene, List<SceneObject> objects) {
		for (SceneObject object : objects) {
			scene.removeObject(object);
			object.cleanup();
		}
		objects.clear();
	}

	private void addTrailSegment(SceneView scene, List<Vector3f> points, Vector3f color, boolean visible) {
		if (points.size() < 2) {
			return;
		}
		Mesh mesh = TrajectoryTrailGenerator.create(points, trailDecorationRadius, 8);
		if (mesh.getVertices().isEmpty()) {
			return;
		}
		SceneObject trailObject = addTrailObject(scene, mesh, color);
		trailObject.setVisible(visible);
		dynamicTrails.add(trailObject);
	}

	private void addGroundReference(SceneView scene, FlightData data) {
		float size = computeGroundSize(data);
		// CLOCKWISE winding makes the plane's up-facing side the front face (same as
		// TerrainGenerator), so the ground is visible from above instead of culled.
		Mesh groundMesh = PlaneGenerator.create(size, size, 1.0f, 1.0f,
				GeometryConstants.WindingOrder.CLOCKWISE);
		Appearance3D groundAppearance = new Appearance3D(new Vector3f(0.22f, 0.30f, 0.20f));
		groundAppearance.setUnlit(true);
		groundAppearance.setShine(0.05f);
		SceneObject ground = new SceneObject(groundMesh, new Vector3f(0.0f, 0.0f, 0.0f), groundAppearance);
		ground.setSelectable(false);
		scene.addObject(ground);
	}

	private GroundedPoseProviders createGroundedPoseProviders(SceneView scene, FlightReplayData replayData) {
		float groundLift = computeStartGroundLift(scene, replayData.getProvidersByStage(),
				replayData.getPrimaryProvider(), replayData.getStartTime());
		if (groundLift <= 1.0e-4f) {
			return new GroundedPoseProviders(replayData.getProvidersByStage(), replayData.getPrimaryProvider());
		}

		Vector3f offset = new Vector3f(0.0f, groundLift, 0.0f);
		Map<AxialStage, PoseProvider> adjusted = new LinkedHashMap<>();
		for (Map.Entry<AxialStage, PoseProvider> entry : replayData.getProvidersByStage().entrySet()) {
			adjusted.put(entry.getKey(), new OffsetPoseProvider(entry.getValue(), offset));
		}
		return new GroundedPoseProviders(adjusted, new OffsetPoseProvider(replayData.getPrimaryProvider(), offset));
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
			PoseProvider provider = providerForComponent(component, providersByStage);
			if (provider == null) {
				provider = primaryProvider;
			}
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

	private PoseProvider providerForComponent(RocketComponent component, Map<AxialStage, PoseProvider> providersByStage) {
		try {
			AxialStage stage = component instanceof AxialStage
					? (AxialStage) component
					: component.getStage();
			return providersByStage.get(stage);
		} catch (IllegalStateException e) {
			return null;
		}
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
			List<double[]> stageIntervals = new java.util.ArrayList<>(entry.getValue().size());
			for (FlightReplayData.BurnInterval interval : entry.getValue()) {
				stageIntervals.add(new double[] { interval.start(), interval.end() });
			}
			timeline.put(entry.getKey(), stageIntervals);
		}
		return timeline;
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
