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
import info.openrocket.core.startup.Application;
import info.openrocket.swing.gui.figure3d.SharedCanvasRenderScheduler;
import info.openrocket.swing.gui.figure3d.animation.PlaybackClock;
import info.openrocket.swing.gui.figure3d.animation.PoseProvider;
import info.openrocket.swing.gui.figure3d.constants.GeometryConstants;
import info.openrocket.swing.gui.figure3d.constants.RenderingConstants;
import info.openrocket.swing.gui.figure3d.core.geometry.Mesh;
import info.openrocket.swing.gui.figure3d.core.geometry.basic.PlaneGenerator;
import info.openrocket.swing.gui.figure3d.geometry.basic.TrajectoryTrailGenerator;
import info.openrocket.swing.gui.figure3d.materials.Appearance3D;
import info.openrocket.swing.gui.figure3d.rendering.backgrounds.GradientBackground;
import info.openrocket.swing.gui.figure3d.scene.controllers.CameraControls;
import info.openrocket.swing.gui.figure3d.scene.core.SceneObject;
import info.openrocket.swing.gui.figure3d.scene.core.SceneView;
import info.openrocket.swing.gui.figure3d.scene.orchestration.Scene3DOrchestrator;
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
import java.util.Set;
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
	private FlightConfigurationId originalConfigurationId;
	private GLScenePanel glPanel;
	private final AtomicReference<GLScenePanel> pendingCanvasRebuild = new AtomicReference<>();
	private volatile BiConsumer<PlaybackClock, FlightReplayData> replayReadyCallback;
	private volatile long earliestRenderAtMs;
	private volatile boolean renderLoopRunning = false;
	private volatile FlightCameraMode cameraMode = FlightCameraMode.OVERVIEW;
	private volatile Vector3f trajectoryCenter;
	private volatile Vector3f trajectoryDimensions;

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
		originalConfigurationId = doc.getRocket().getSelectedConfiguration().getFlightConfigurationID();
		// The 3D scene is built from the rocket's selected configuration, so we switch it to the
		// simulation's configuration here. This is a side effect on shared document state: the main
		// design view's selected configuration changes too (a NONFUNCTIONAL_CHANGE, no undo entry)
		// until this window closes, when clearDoc() restores originalConfigurationId.
		doc.getRocket().setSelectedConfiguration(replayConfigurationId);

		GLScenePanel panel = createCanvas("3D flight replay view unavailable");
		if (panel == null) {
			restoreOriginalConfiguration();
			document = null;
			simulation = null;
			flightData = null;
			replayConfigurationId = null;
			originalConfigurationId = null;
			return;
		}
		installCanvas(panel);
		startRenderLoop();
		debug("setSimulation done");
	}

	void clearDoc() {
		debug("clearDoc");
		stopRenderLoop();
		if (glPanel != null) {
			RENDER_SCHEDULER.awaitQuiescence(RENDER_SHUTDOWN_TIMEOUT_MS);
			disposeCurrentCanvas(glPanel);
		}
		restoreOriginalConfiguration();
		pendingCanvasRebuild.set(null);
		document = null;
		simulation = null;
		flightData = null;
		replayConfigurationId = null;
		originalConfigurationId = null;
	}

	private GLScenePanel createCanvas(String unavailableMessage) {
		try {
			return new GLScenePanel(document.getRocket(), null, false);
		} catch (UnsatisfiedLinkError | ExceptionInInitializerError e) {
			log.warn("{}: LWJGL native libraries not found for {}/{}.",
					unavailableMessage, System.getProperty("os.name"), System.getProperty("os.arch"), e);
			return null;
		}
	}

	private void installCanvas(GLScenePanel panel) {
		glPanel = panel;
		panel.setInitializationHook(this::initializeFlightPanelOnGlThread);
		panel.setBlankDefaultFramebufferCallback(() -> requestCanvasRebuild(panel));
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
		if (panel.glInitFailed) {
			stopRenderLoop();
			return;
		}
		if (System.currentTimeMillis() < earliestRenderAtMs) {
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
		return true;
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
		document.getRocket().setSelectedConfiguration(replayConfigurationId);

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
		panel.setBlankDefaultFramebufferCallback(null);
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

	private void initializeFlightPanelOnGlThread(Scene3DOrchestrator orchestrator) {
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
		config.getVisualEffects().setParticleEffectsEnabled(true);
		config.getVisualEffects().setFlameParticlesEnabled(true);
		config.getVisualEffects().setSmokeParticlesEnabled(true);
		config.getVisualEffects().setSparkParticlesEnabled(false);
		config.getVisualEffects().setStaticParticles(false);
		orchestrator.rebuildRocketScene(false);
		scene = orchestrator.getScene();
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
		orchestrator.setFlightBurnIntervals(burnTimeline);

		// Reuse the design-view rocket-center computation so the follow camera orbits the
		// rocket's middle, not its nose. Compute the whole-flight framing for the default view.
		orchestrator.setFlightRocketCenterOffset(orchestrator.getCameraController().computeRocketCenter());
		computeTrajectoryBounds(orchestrator.getCameraController(), groundedPoses,
				replayData.getStartTime(), replayData.getEndTime());
		addTrajectoryTrails(scene, groundedPoses, replayData.getStartTime(), replayData.getEndTime());
		applyCameraMode(orchestrator, cameraMode);

		PlaybackClock clock = orchestrator.getPlaybackClock();
		if (clock != null) {
			clock.setRate(0.0);
		}
		BiConsumer<PlaybackClock, FlightReplayData> callback = replayReadyCallback;
		if (callback != null && clock != null) {
			SwingUtilities.invokeLater(() -> callback.accept(clock, replayData));
		}
	}

	/**
	 * Switches the replay camera behaviour. Safe to call from the EDT: the orchestrator methods
	 * set volatile flags applied on the render thread.
	 */
	void setCameraMode(FlightCameraMode mode) {
		this.cameraMode = mode;
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

	private void applyCameraMode(Scene3DOrchestrator orchestrator, FlightCameraMode mode) {
		if (mode == FlightCameraMode.FOLLOW) {
			orchestrator.setFollowFlightCamera(true);
		} else if (trajectoryCenter != null && trajectoryDimensions != null) {
			orchestrator.fitFlightTrajectory(trajectoryCenter, trajectoryDimensions);
		} else {
			orchestrator.setFollowFlightCamera(false);
			orchestrator.focusOnRocket();
		}
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

	/**
	 * Adds a visible tube along each flight path. At the whole-flight zoom the rocket itself is
	 * only a few pixels, so the trail is what makes the trajectory legible.
	 */
	private void addTrajectoryTrails(SceneView scene, GroundedPoseProviders poses, double startTime, double endTime) {
		Vector3f dimensions = trajectoryDimensions;
		if (dimensions == null) {
			return;
		}
		float maxExtent = Math.max(dimensions.x, Math.max(dimensions.y, dimensions.z));
		float radius = Math.max(maxExtent * 0.003f, 1.0f);

		Set<PoseProvider> uniqueProviders = Collections.newSetFromMap(new IdentityHashMap<>());
		uniqueProviders.add(poses.primaryProvider());
		uniqueProviders.addAll(poses.providersByStage().values());

		for (PoseProvider provider : uniqueProviders) {
			List<Vector3f> path = new ArrayList<>();
			int samples = 240;
			for (int i = 0; i <= samples; i++) {
				double t = startTime + (endTime - startTime) * i / samples;
				path.add(provider.getPosition(t));
			}
			Mesh trailMesh = TrajectoryTrailGenerator.create(path, radius, 8);
			if (trailMesh.getVertices().isEmpty()) {
				continue;
			}
			Appearance3D appearance = new Appearance3D(new Vector3f(1.0f, 0.55f, 0.15f));
			appearance.setUnlit(true);
			SceneObject trailObject = new SceneObject(trailMesh, new Vector3f(0.0f, 0.0f, 0.0f), appearance);
			trailObject.setSelectable(false);
			scene.addObject(trailObject);
		}
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
		return Math.max(MIN_GROUND_SIZE, (float) (maxHorizontalMeters * RenderingConstants.WORLD_SCALE * 3.0));
	}

	private static double valueOrZero(Double value) {
		if (value == null || Double.isNaN(value) || Double.isInfinite(value)) {
			return 0.0;
		}
		return value;
	}

	private void applyFlightBackground(SceneView scene) {
		scene.setBackground(new GradientBackground(
				new Vector3f(0.70f, 0.82f, 0.96f),
				new Vector3f(0.78f, 0.88f, 0.74f)));
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

	private void restoreOriginalConfiguration() {
		if (document == null || originalConfigurationId == null) {
			return;
		}
		document.getRocket().setSelectedConfiguration(originalConfigurationId);
	}

	private static void debug(String message) {
		if (!DEBUG) {
			return;
		}
		System.out.println("[Flight3DPanel][" + Thread.currentThread().getName() + "] " + message);
	}
}
