package info.openrocket.swing.gui.figure3d.flight;

import info.openrocket.swing.gui.figure3d.animation.PlaybackClock;
import info.openrocket.swing.util.BaseTestCase;
import info.openrocket.core.rocketcomponent.AxialStage;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.simulation.FlightData;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.simulation.FlightEvent;
import org.junit.jupiter.api.Test;

import javax.swing.JSlider;
import javax.swing.SwingUtilities;
import java.awt.event.MouseEvent;
import java.awt.event.KeyEvent;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaybackTransportBarTest extends BaseTestCase {
	@Test
	void eventMarkersShowTheirEventAndSeekToTheExactEventTime() throws Exception {
		SwingUtilities.invokeAndWait(() -> {
			Rocket rocket = new Rocket();
			rocket.addChild(new AxialStage());
			FlightDataBranch branch = replayBranch(0.0, 10.0);
			double eventTime = 10.0 / 3.0;
			branch.addEvent(new FlightEvent(FlightEvent.Type.APOGEE, eventTime));
			FlightReplayData replay = new FlightReplayData(new FlightData(branch), rocket);
			PlaybackClock clock = new PlaybackClock(0.0, 10.0);
			clock.setRate(0.0);
			PlaybackTransportBar bar = new PlaybackTransportBar();
			bar.setReplay(clock, replay);
			try {
				JSlider slider = bar.getScrubSlider();
				slider.setSize(600, 40);
				int markerX = 12 + (int) Math.round((600 - 24) / 3.0);
				int markerY = 40 / 2 + 8;
				MouseEvent hover = mouseEvent(slider, MouseEvent.MOUSE_MOVED, markerX, markerY);
				String tooltip = slider.getToolTipText(hover);
				assertNotNull(tooltip);
				assertFalse(tooltip.isBlank());

				slider.dispatchEvent(mouseEvent(slider, MouseEvent.MOUSE_PRESSED, markerX, markerY));
				slider.dispatchEvent(mouseEvent(slider, MouseEvent.MOUSE_RELEASED, markerX, markerY));

				assertEquals(eventTime, clock.getTime(), 1e-9);
			} finally {
				bar.dispose();
			}
		});
	}

	@Test
	void playbackButtonsRestartStepAndSwitchBetweenPlayAndPauseIcons() throws Exception {
		SwingUtilities.invokeAndWait(() -> {
			PlaybackTransportBar bar = new PlaybackTransportBar();
			PlaybackClock clock = new PlaybackClock(0.0, 10.0);
			clock.setRate(0.0);
			clock.setTime(5.0);
			bar.setReplay(clock, null);
			try {
				var playPressedIcon = bar.getPlayPauseButton().getPressedIcon();
				bar.getPreviousFrameButton().doClick();
				assertEquals(5.0 - 1.0 / 60.0, clock.getTime(), 1e-9);
				assertEquals(0.0, clock.getRate(), 1e-9);

				bar.getNextFrameButton().doClick();
				assertEquals(5.0, clock.getTime(), 1e-9);

				bar.getPlayPauseButton().doClick();
				assertEquals(1.0, clock.getRate(), 1e-9);
				assertNotSame(playPressedIcon, bar.getPlayPauseButton().getPressedIcon());

				bar.getRestartButton().doClick();
				assertEquals(clock.getStart(), clock.getTime(), 1e-9);
				assertEquals(0.0, clock.getRate(), 1e-9);
			} finally {
				bar.dispose();
			}
		});
	}

	@Test
	void viewButtonsDispatchActionsAndPadModeDisablesPan() throws Exception {
		SwingUtilities.invokeAndWait(() -> {
			PlaybackTransportBar bar = new PlaybackTransportBar();
			AtomicInteger zoomOut = new AtomicInteger();
			AtomicInteger zoomIn = new AtomicInteger();
			AtomicInteger fit = new AtomicInteger();
			AtomicBoolean pan = new AtomicBoolean();
			bar.setViewControlListeners(zoomOut::incrementAndGet, zoomIn::incrementAndGet,
					fit::incrementAndGet, pan::set);
			bar.setReplay(new PlaybackClock(0.0, 10.0), null);
			try {
				bar.getZoomOutButton().doClick();
				bar.getZoomInButton().doClick();
				bar.getZoomFitButton().doClick();
				var panButton = bar.getPanButton();
				panButton.doClick();

				assertEquals(1, zoomOut.get());
				assertEquals(1, zoomIn.get());
				assertEquals(1, fit.get());
				assertTrue(pan.get());

				bar.getCameraModeCombo().setSelectedItem(FlightCameraMode.PAD);

				assertFalse(panButton.isEnabled());
				assertFalse(panButton.isSelected());
				assertFalse(pan.get());
			} finally {
				bar.dispose();
			}
		});
	}


	@Test
	void sliderModelChangesSeekWithoutMouseDragging() throws Exception {
		SwingUtilities.invokeAndWait(() -> {
			PlaybackTransportBar bar = new PlaybackTransportBar();
			PlaybackClock clock = new PlaybackClock(0.0, 10.0);
			bar.setReplay(clock, null);
			try {
				JSlider slider = bar.getScrubSlider();

				slider.setValue(5_000);

				assertEquals(5.0, clock.getTime(), 1e-6);
			} finally {
				bar.dispose();
			}
		});
	}

	@Test
	void scrubbingPausesAndResumesAtThePreviousRateWithoutLosingTheFinalPosition() throws Exception {
		SwingUtilities.invokeAndWait(() -> {
			PlaybackTransportBar bar = new PlaybackTransportBar();
			PlaybackClock clock = new PlaybackClock(0.0, 10.0);
			bar.setReplay(clock, null);
			clock.setRate(2.0);
			try {
				JSlider slider = bar.getScrubSlider();
				slider.setSize(600, 44);
				slider.dispatchEvent(mouseEvent(slider, MouseEvent.MOUSE_PRESSED, 300, 18));
				assertEquals(5.0, clock.getTime(), 1e-6);
				assertEquals(0.0, clock.getRate());
				clock.update(2.0);
				assertEquals(5.0, clock.getTime(), 1e-6);
				slider.dispatchEvent(mouseEvent(slider, MouseEvent.MOUSE_DRAGGED, 444, 18));
				assertEquals(7.5, clock.getTime(), 1e-6);
				slider.dispatchEvent(mouseEvent(slider, MouseEvent.MOUSE_RELEASED, 444, 18));
				assertEquals(2.0, clock.getRate());
				clock.update(3.0);
				assertEquals(7.5, clock.getTime(), 1e-6, "Resuming must not charge the drag duration");
				clock.update(0.1);
				assertEquals(7.7, clock.getTime(), 1e-6);

				clock.setRate(0.0);
				slider.dispatchEvent(mouseEvent(slider, MouseEvent.MOUSE_PRESSED, 156, 18));
				slider.dispatchEvent(mouseEvent(slider, MouseEvent.MOUSE_RELEASED, 156, 18));
				assertEquals(2.5, clock.getTime(), 1e-6);
				assertEquals(0.0, clock.getRate(), "Scrubbing a paused replay must leave it paused");
			} finally {
				bar.dispose();
			}
		});
	}

	@Test
	void keyboardEventNavigationSkipsSimultaneousEventsAndPausesAtExactTimes() throws Exception {
		SwingUtilities.invokeAndWait(() -> {
			Rocket rocket = new Rocket();
			rocket.addChild(new AxialStage());
			FlightDataBranch branch = replayBranch(0.0, 10.0);
			branch.addEvent(new FlightEvent(FlightEvent.Type.APOGEE, 10.0 / 3.0));
			branch.addEvent(new FlightEvent(FlightEvent.Type.RECOVERY_DEVICE_DEPLOYMENT, 10.0 / 3.0));
			branch.addEvent(new FlightEvent(FlightEvent.Type.GROUND_HIT, 9.0));
			PlaybackTransportBar bar = new PlaybackTransportBar();
			PlaybackClock clock = new PlaybackClock(0.0, 10.0);
			bar.setReplay(clock, new FlightReplayData(new FlightData(branch), rocket));
			try {
				assertTrue(bar.handleReplayKey(keyEvent(bar, KeyEvent.VK_RIGHT, KeyEvent.SHIFT_DOWN_MASK)));
				assertEquals(10.0 / 3.0, clock.getTime(), 1e-9);
				assertEquals(0.0, clock.getRate());
				bar.handleReplayKey(keyEvent(bar, KeyEvent.VK_RIGHT, KeyEvent.SHIFT_DOWN_MASK));
				assertEquals(9.0, clock.getTime(), 1e-9);
				bar.handleReplayKey(keyEvent(bar, KeyEvent.VK_LEFT, KeyEvent.SHIFT_DOWN_MASK));
				assertEquals(10.0 / 3.0, clock.getTime(), 1e-9);
				bar.handleReplayKey(keyEvent(bar, KeyEvent.VK_SPACE, 0));
				assertEquals(1.0, clock.getRate());
				assertFalse(bar.handleReplayKey(keyEvent(bar, KeyEvent.VK_LEFT, KeyEvent.META_DOWN_MASK)));
				bar.clearReplay();
				assertEquals(0, bar.getScrubSlider().getValue());
				assertFalse(bar.handleReplayKey(keyEvent(bar, KeyEvent.VK_SPACE, 0)));
			} finally {
				bar.dispose();
			}
		});
	}

	private static KeyEvent keyEvent(PlaybackTransportBar bar, int key, int modifiers) {
		return new KeyEvent(bar, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), modifiers, key, KeyEvent.CHAR_UNDEFINED);
	}

	private static FlightDataBranch replayBranch(double start, double end) {
		FlightDataBranch branch = new FlightDataBranch("flight",
				FlightDataType.TYPE_TIME, FlightDataType.TYPE_ALTITUDE,
				FlightDataType.TYPE_POSITION_X, FlightDataType.TYPE_POSITION_Y);
		for (double time : new double[] { start, end }) {
			branch.addPoint();
			branch.setValue(FlightDataType.TYPE_TIME, time);
			branch.setValue(FlightDataType.TYPE_ALTITUDE, time);
			branch.setValue(FlightDataType.TYPE_POSITION_X, 0.0);
			branch.setValue(FlightDataType.TYPE_POSITION_Y, 0.0);
		}
		return branch;
	}

	private static MouseEvent mouseEvent(JSlider slider, int id, int x, int y) {
		return new MouseEvent(slider, id, System.currentTimeMillis(), 0, x, y, 1, false,
				id == MouseEvent.MOUSE_MOVED || id == MouseEvent.MOUSE_DRAGGED ? MouseEvent.NOBUTTON : MouseEvent.BUTTON1);
	}

}
