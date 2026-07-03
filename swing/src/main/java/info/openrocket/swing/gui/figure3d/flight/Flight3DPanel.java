package info.openrocket.swing.gui.figure3d.flight;

import info.openrocket.core.arch.SystemInfo;
import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.core.document.Simulation;
import info.openrocket.core.l10n.Translator;
import info.openrocket.core.rocketcomponent.FlightConfigurationId;
import info.openrocket.core.simulation.FlightData;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.startup.Application;
import info.openrocket.swing.gui.figure3d.SharedCanvasRenderScheduler;
import info.openrocket.swing.gui.figure3d.animation.PlaybackClock;
import info.openrocket.swing.gui.figure3d.constants.RenderingConstants;
import info.openrocket.swing.gui.figure3d.core.geometry.Mesh;
import info.openrocket.swing.gui.figure3d.core.geometry.basic.PlaneGenerator;
import info.openrocket.swing.gui.figure3d.materials.Appearance3D;
import info.openrocket.swing.gui.figure3d.rendering.backgrounds.GradientBackground;
import info.openrocket.swing.gui.figure3d.scene.core.SceneObject;
import info.openrocket.swing.gui.figure3d.scene.core.SceneView;
import info.openrocket.swing.gui.figure3d.scene.orchestration.Scene3DOrchestrator;
import info.openrocket.swing.gui.figure3d.scene.properties.DisplaySettings;
import info.openrocket.swing.gui.figure3d.scene.properties.RenderingConfiguration;
import info.openrocket.swing.gui.figure3d.ui.GLScenePanel;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.GridBagLayout;
import java.util.List;
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
		orchestrator.bindFlightPosesToRocket(replayData.getProvidersByStage(), replayData.getPrimaryProvider(),
				replayData.getStartTime(), replayData.getEndTime());
		orchestrator.setFlightBurnIntervals(toTimeline(replayData.getBurnIntervals()));
		orchestrator.setFollowFlightCamera(true);
		PlaybackClock clock = orchestrator.getPlaybackClock();
		if (clock != null) {
			clock.setRate(0.0);
		}
		orchestrator.focusOnRocket();
		BiConsumer<PlaybackClock, FlightReplayData> callback = replayReadyCallback;
		if (callback != null && clock != null) {
			SwingUtilities.invokeLater(() -> callback.accept(clock, replayData));
		}
	}

	private void disableComponentSelection(SceneView scene) {
		scene.setSelection(List.of());
		for (SceneObject obj : scene.getObjects()) {
			obj.setSelected(false);
			obj.setSelectable(false);
		}
	}

	private void addGroundReference(SceneView scene, FlightData data) {
		float size = computeGroundSize(data);
		Mesh groundMesh = PlaneGenerator.create(size, size, 1.0f, 1.0f);
		Appearance3D groundAppearance = new Appearance3D(new Vector3f(0.22f, 0.30f, 0.20f));
		groundAppearance.setUnlit(true);
		groundAppearance.setShine(0.05f);
		SceneObject ground = new SceneObject(groundMesh, new Vector3f(0.0f, 0.0f, 0.0f), groundAppearance);
		ground.setSelectable(false);
		scene.addObject(ground);
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

	private List<double[]> toTimeline(List<FlightReplayData.BurnInterval> intervals) {
		List<double[]> timeline = new java.util.ArrayList<>(intervals.size());
		for (FlightReplayData.BurnInterval interval : intervals) {
			timeline.add(new double[] { interval.start(), interval.end() });
		}
		return timeline;
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
