package info.openrocket.swing.gui.figure3d.flight;

import com.google.inject.Guice;
import info.openrocket.core.document.Simulation;
import info.openrocket.core.plugin.PluginModule;
import info.openrocket.core.startup.Application;
import info.openrocket.swing.ServicesForTesting;
import info.openrocket.swing.gui.figure3d.GoldenImageTestSupport;
import info.openrocket.swing.gui.figure3d.animation.PlaybackClock;
import info.openrocket.swing.gui.figure3d.particles.flame.FlameEmitter;
import info.openrocket.swing.gui.figure3d.particles.smoke.SmokeEmitter;
import info.openrocket.swing.gui.figure3d.ui.GLScenePanel;
import info.openrocket.swing.gui.figure3d.scene.graph.SceneObject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.imageio.ImageIO;
import javax.swing.Timer;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static info.openrocket.swing.gui.figure3d.GoldenImageTestSupport.onEdt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises replay initialization, camera changes, seeking and exhaust on a real GL canvas. */
@Tag("requires-live-opengl")
@Timeout(value = 90, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class FlightReplayRenderTest {
	@Test
	void replayRendersAndCanHideAndRestoreExhaustWhilePaused() throws Exception {
		Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
		Application.setInjector(Guice.createInjector(new ServicesForTesting(), new PluginModule()));
		var document = GoldenImageTestSupport.createStyledRocketDocument();
		Simulation simulation = new Simulation(document, document.getRocket());
		simulation.setName("Alpha III replay");
		simulation.simulate();
		Flight3DFrame frame = onEdt(() -> {
			Flight3DFrame.openForSimulation(document, simulation, null);
			for (Window window : Window.getWindows()) {
				if (window instanceof Flight3DFrame result && result.isShowing()) {
					result.setSize(1024, 768);
					return result;
				}
			}
			throw new AssertionError("Replay window was not opened");
		});
		try {
			Flight3DPanel panel = onEdt(() -> find(frame, Flight3DPanel.class));
			PlaybackTransportBar bar = onEdt(() -> find(frame, PlaybackTransportBar.class));
			CompletableFuture<GLScenePanel> ready = new CompletableFuture<>();
			Timer timer = onEdt(() -> {
				Timer poll = new Timer(50, event -> {
					if (bar.getPlayPauseButton().isEnabled()) {
						((Timer) event.getSource()).stop();
						ready.complete(find(panel, GLScenePanel.class));
					}
				});
				poll.start();
				return poll;
			});
			GLScenePanel canvas;
			try {
				canvas = ready.get(25, TimeUnit.SECONDS);
			} finally {
				onEdt(() -> { timer.stop(); return null; });
			}
			assertNotNull(canvas);
			PlaybackClock clock = canvas.getScene3DOrchestrator().getPlaybackClock();
			onEdt(() -> {
				clock.setRate(0.0);
				clock.setTime(0.5);
				bar.getCameraModeCombo().setSelectedItem(FlightCameraMode.FOLLOW);
				panel.requestRenderNow();
				return null;
			});
			BufferedImage first = capture(canvas, "flight-follow-exhaust.png");
			BufferedImage paused = capture(canvas, "flight-flame-paused.png");
			assertTrue(GoldenImageTestSupport.compare(first, paused, 1).meanAbsoluteError() < 0.01,
					"Flame shading must remain still when the replay clock is paused");
			onEdt(() -> { clock.setTime(0.56); panel.requestRenderNow(); return null; });
			capture(canvas, "flight-flame-next.png");
			onEdt(() -> { clock.setTime(0.5); panel.requestRenderNow(); return null; });
			BufferedImage rewound = capture(canvas, "flight-flame-rewound.png");
			assertTrue(GoldenImageTestSupport.compare(first, rewound, 1).meanAbsoluteError() < 0.01,
					"Rewinding must restore both the plume and its shader flicker");
			assertTrue(exhaustCount(canvas, panel) > 0);
			onEdt(() -> {
				Flight3DFrame.openForSimulation(document, simulation, null);
				assertTrue(bar.getPlayPauseButton().isEnabled(), "Reopening the same replay must retain its transport");
				assertEquals(0.5, clock.getTime(), 1e-6);
				return null;
			});
			onEdt(() -> { panel.setExhaustVisible(false); return null; });
			capture(canvas, "flight-follow-clean.png");
			assertEquals(0, exhaustCount(canvas, panel));
			onEdt(() -> {
				panel.setExhaustVisible(true);
				clock.setTime(0.25);
				bar.getCameraModeCombo().setSelectedItem(FlightCameraMode.OVERVIEW);
				return null;
			});
			capture(canvas, "flight-overview.png");
			assertTrue(exhaustCount(canvas, panel) > 0);
			for (FlightCameraMode mode : List.of(FlightCameraMode.PAD, FlightCameraMode.OVERVIEW)) {
				onEdt(() -> {
					bar.getCameraModeCombo().setSelectedItem(mode);
					bar.getTrailButton().doClick();
					assertFalse(bar.getTrailButton().isSelected());
					clock.setRate(1.0);
					panel.requestRenderNow();
					return null;
				});
				capture(canvas, "flight-trail-hidden-" + mode.name() + ".png");
				assertTrailVisibility(canvas, panel, false);
				onEdt(() -> { clock.setTime(clock.getTime() + 0.25); return null; });
				capture(canvas, "flight-trail-hidden-advanced-" + mode.name() + ".png");
				assertTrailVisibility(canvas, panel, false);
				onEdt(() -> { bar.getTrailButton().doClick(); return null; });
				capture(canvas, "flight-trail-restored-" + mode.name() + ".png");
				assertTrailVisibility(canvas, panel, true);
			}
			onEdt(() -> {
				clock.setRate(0.0);
				bar.setSize(frame.getWidth(), bar.getPreferredSize().height);
				bar.doLayout();
				BufferedImage image = new BufferedImage(bar.getWidth(), bar.getHeight(), BufferedImage.TYPE_INT_ARGB);
				var graphics = image.createGraphics();
				try { bar.printAll(graphics); } finally { graphics.dispose(); }
				writeImage(image, "flight-controls.png");
				assertFalse(bar.getScrubSlider().getWidth() < 500, "Timeline must have usable room at default window size");
				return null;
			});
		} finally {
			onEdt(() -> { frame.dispose(); return null; });
		}
	}

	private static void assertTrailVisibility(GLScenePanel canvas, Flight3DPanel panel, boolean visible) throws Exception {
		var field = Flight3DPanel.class.getDeclaredField("dynamicTrails");
		field.setAccessible(true);
		CompletableFuture<List<Boolean>> result = new CompletableFuture<>();
		canvas.getScene3DOrchestrator().enqueueGlTask(() -> {
			try {
				List<?> trails = (List<?>) field.get(panel);
				result.complete(trails.stream().map(object -> ((SceneObject) object).isVisible()).toList());
			} catch (IllegalAccessException exception) {
				result.completeExceptionally(exception);
			}
		});
		panel.requestRenderNow();
		List<Boolean> states = result.get(10, TimeUnit.SECONDS);
		assertFalse(states.isEmpty(), "The test must exercise rebuilt trajectory meshes");
		assertTrue(states.stream().allMatch(state -> state == visible),
				"Every rebuilt segment must respect the trajectory checkbox during playback");
	}

	private static int exhaustCount(GLScenePanel canvas, Flight3DPanel panel) throws Exception {
		CompletableFuture<Integer> result = new CompletableFuture<>();
		canvas.getScene3DOrchestrator().enqueueGlTask(() -> result.complete(
				canvas.getScene3DOrchestrator().getScene().getParticleEmitters().stream()
						.filter(emitter -> emitter instanceof SmokeEmitter || emitter instanceof FlameEmitter)
						.mapToInt(emitter -> emitter.getParticles().size()).sum()));
		panel.requestRenderNow();
		return result.get(10, TimeUnit.SECONDS);
	}

	private static BufferedImage capture(GLScenePanel canvas, String name) throws Exception {
		CompletableFuture<BufferedImage> result = new CompletableFuture<>();
		onEdt(() -> { canvas.requestImageCapture(false, result::complete); return null; });
		BufferedImage image = result.get(20, TimeUnit.SECONDS);
		assertNotNull(image);
		writeImage(image, name);
		return image;
	}

	private static void writeImage(BufferedImage image, String name) throws Exception {
		Path path = Path.of("build", "visual-regression", name);
		Files.createDirectories(path.getParent());
		ImageIO.write(image, "png", path.toFile());
	}

	private static <T extends Component> T find(Container container, Class<T> type) {
		for (Component child : container.getComponents()) {
			if (type.isInstance(child)) return type.cast(child);
			if (child instanceof Container nested) {
				T found = find(nested, type);
				if (found != null) return found;
			}
		}
		return null;
	}
}
