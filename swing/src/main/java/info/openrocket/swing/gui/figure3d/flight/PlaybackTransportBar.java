package info.openrocket.swing.gui.figure3d.flight;

import info.openrocket.core.l10n.Translator;
import info.openrocket.core.simulation.FlightEvent;
import info.openrocket.core.startup.Application;
import info.openrocket.swing.gui.figure3d.animation.PlaybackClock;
import info.openrocket.swing.gui.util.Icons;
import info.openrocket.swing.gui.widgets.IconButton;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.JToggleButton;
import javax.swing.event.ChangeEvent;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Consumer;

@SuppressWarnings("serial")
class PlaybackTransportBar extends JPanel {
	private static final Translator trans = Application.getTranslator();
	private static final int SLIDER_STEPS = 10_000;
	private static final int POLL_INTERVAL_MS = 100;
	private static final EnumSet<FlightEvent.Type> MARKER_TYPES = EnumSet.of(
			FlightEvent.Type.IGNITION,
			FlightEvent.Type.LAUNCHROD,
			FlightEvent.Type.LIFTOFF,
			FlightEvent.Type.BURNOUT,
			FlightEvent.Type.STAGE_SEPARATION,
			FlightEvent.Type.APOGEE,
			FlightEvent.Type.RECOVERY_DEVICE_DEPLOYMENT,
			FlightEvent.Type.GROUND_HIT);

	private final JButton playPauseButton = new JButton(trans.get("Flight3DFrame.play"));
	private final EventMarkerSlider scrubSlider = new EventMarkerSlider();
	private final JComboBox<SpeedOption> speedCombo = new JComboBox<>(new SpeedOption[] {
			new SpeedOption(0.25),
			new SpeedOption(0.5),
			new SpeedOption(1.0),
			new SpeedOption(2.0),
			new SpeedOption(4.0)
	});
	private final JComboBox<FlightCameraMode> cameraModeCombo = new JComboBox<>(FlightCameraMode.values());
	private final JButton zoomOutButton = new IconButton(Icons.ZOOM_OUT);
	private final JButton zoomInButton = new IconButton(Icons.ZOOM_IN);
	private final JButton zoomFitButton = new IconButton(Icons.ZOOM_RESET);
	private final JToggleButton panButton = new JToggleButton(Icons.PAN_VIEW);
	private final JLabel timeLabel = new JLabel(formatTime(0.0, 0.0), SwingConstants.RIGHT);
	private final Timer pollTimer = new Timer(POLL_INTERVAL_MS, e -> pollClock());

	private PlaybackClock clock;
	private Consumer<FlightCameraMode> cameraModeListener;
	private Runnable zoomOutListener;
	private Runnable zoomInListener;
	private Runnable zoomFitListener;
	private Consumer<Boolean> panModeListener;
	private Runnable replayChangeListener;
	private boolean viewControlsEnabled;
	private boolean userIsDragging;
	private boolean programmaticUpdate;

	PlaybackTransportBar() {
		setLayout(new BorderLayout(8, 0));

		JPanel leftControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
		leftControls.add(playPauseButton);
		leftControls.add(speedCombo);
		cameraModeCombo.setSelectedItem(FlightCameraMode.OVERVIEW);
		cameraModeCombo.setToolTipText(trans.get("Flight3DFrame.cameraMode.ttip"));
		cameraModeCombo.addActionListener(e -> {
			updatePanControlAvailability();
			if (cameraModeListener != null) {
				cameraModeListener.accept((FlightCameraMode) cameraModeCombo.getSelectedItem());
			}
		});
		leftControls.add(cameraModeCombo);

		zoomOutButton.setToolTipText(trans.get("ScaleSelector.btn.ZoomOut.ttip"));
		zoomOutButton.addActionListener(e -> runViewAction(zoomOutListener));
		leftControls.add(zoomOutButton);
		zoomInButton.setToolTipText(trans.get("ScaleSelector.btn.ZoomIn.ttip"));
		zoomInButton.addActionListener(e -> runViewAction(zoomInListener));
		leftControls.add(zoomInButton);
		zoomFitButton.setToolTipText(trans.get("ScaleSelector.btn.ZoomFit.ttip"));
		zoomFitButton.addActionListener(e -> runViewAction(zoomFitListener));
		leftControls.add(zoomFitButton);
		panButton.setToolTipText(trans.get("Flight3DFrame.pan.ttip"));
		panButton.addActionListener(e -> {
			if (panModeListener != null) {
				panModeListener.accept(panButton.isSelected());
			}
		});
		leftControls.add(panButton);
		add(leftControls, BorderLayout.WEST);

		scrubSlider.setMinimum(0);
		scrubSlider.setMaximum(SLIDER_STEPS);
		scrubSlider.setValue(0);
		scrubSlider.setPaintTicks(false);
		scrubSlider.setEnabled(false);
		scrubSlider.addMouseListener(new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent e) {
				userIsDragging = true;
			}

			@Override
			public void mouseReleased(MouseEvent e) {
				seekToSliderValue();
				userIsDragging = false;
			}
		});
		scrubSlider.addChangeListener(this::handleSliderChanged);
		add(scrubSlider, BorderLayout.CENTER);

		timeLabel.setPreferredSize(new Dimension(110, timeLabel.getPreferredSize().height));
		add(timeLabel, BorderLayout.EAST);

		speedCombo.setSelectedIndex(2);
		speedCombo.addActionListener(e -> {
			if (clock != null && clock.getRate() != 0.0) {
				clock.setRate(selectedSpeed());
			}
		});
		playPauseButton.addActionListener(e -> togglePlayback());
		setControlsEnabled(false);
	}

	void setCameraModeListener(Consumer<FlightCameraMode> listener) {
		this.cameraModeListener = listener;
	}

	void setViewControlListeners(Runnable zoomOutListener, Runnable zoomInListener,
			Runnable zoomFitListener, Consumer<Boolean> panModeListener) {
		this.zoomOutListener = zoomOutListener;
		this.zoomInListener = zoomInListener;
		this.zoomFitListener = zoomFitListener;
		this.panModeListener = panModeListener;
	}

	JButton getZoomOutButton() {
		return zoomOutButton;
	}

	JButton getZoomInButton() {
		return zoomInButton;
	}

	JButton getZoomFitButton() {
		return zoomFitButton;
	}

	JToggleButton getPanButton() {
		return panButton;
	}

	JComboBox<FlightCameraMode> getCameraModeCombo() {
		return cameraModeCombo;
	}

	void setReplayChangeListener(Runnable listener) {
		this.replayChangeListener = listener;
	}

	void setReplay(PlaybackClock clock, FlightReplayData replayData) {
		this.clock = clock;
		scrubSlider.setMarkers(createMarkers(replayData));
		setControlsEnabled(clock != null);
		if (clock == null) {
			pollTimer.stop();
			updateTimeLabel(0.0, 0.0);
			return;
		}
		updateFromClock();
		pollTimer.start();
	}

	void clearReplay() {
		if (clock != null) {
			clock.setRate(0.0);
		}
		clock = null;
		scrubSlider.setMarkers(List.of());
		setControlsEnabled(false);
		updateButtonText();
		updateTimeLabel(0.0, 0.0);
	}

	void dispose() {
		pollTimer.stop();
		clearReplay();
	}

	private void setControlsEnabled(boolean enabled) {
		playPauseButton.setEnabled(enabled);
		speedCombo.setEnabled(enabled);
		scrubSlider.setEnabled(enabled);
		viewControlsEnabled = enabled;
		zoomOutButton.setEnabled(enabled);
		zoomInButton.setEnabled(enabled);
		zoomFitButton.setEnabled(enabled);
		if (!enabled && panButton.isSelected()) {
			panButton.setSelected(false);
			if (panModeListener != null) {
				panModeListener.accept(false);
			}
		}
		updatePanControlAvailability();
	}

	private void updatePanControlAvailability() {
		boolean allowed = cameraModeCombo.getSelectedItem() != FlightCameraMode.PAD;
		if (!allowed && panButton.isSelected()) {
			panButton.setSelected(false);
			if (panModeListener != null) {
				panModeListener.accept(false);
			}
		}
		panButton.setEnabled(viewControlsEnabled && allowed);
	}

	private static void runViewAction(Runnable action) {
		if (action != null) {
			action.run();
		}
	}

	private void togglePlayback() {
		if (clock == null) {
			return;
		}
		if (clock.getRate() == 0.0) {
			if (clock.getTime() >= clock.getEnd()) {
				clock.setTime(clock.getStart());
			}
			clock.setRate(selectedSpeed());
		} else {
			clock.setRate(0.0);
		}
		updateButtonText();
		updateFromClock();
		notifyReplayChanged();
	}

	private void pollClock() {
		if (clock == null) {
			return;
		}
		if (clock.getRate() > 0.0 && clock.getTime() >= clock.getEnd()) {
			clock.setRate(0.0);
		}
		updateButtonText();
		updateFromClock();
	}

	private void handleSliderChanged(ChangeEvent event) {
		if (programmaticUpdate || clock == null) {
			return;
		}
		seekToSliderValue();
	}

	private void seekToSliderValue() {
		if (clock == null) {
			return;
		}
		clock.setTime(sliderValueToTime(scrubSlider.getValue()));
		updateFromClock();
		notifyReplayChanged();
	}

	private void notifyReplayChanged() {
		if (replayChangeListener != null) {
			replayChangeListener.run();
		}
	}

	private void updateFromClock() {
		if (clock == null) {
			return;
		}
		double time = clock.getTime();
		if (!userIsDragging) {
			programmaticUpdate = true;
			try {
				scrubSlider.setValue(timeToSliderValue(time));
			} finally {
				programmaticUpdate = false;
			}
		}
		updateTimeLabel(time, clock.getEnd());
	}

	private void updateButtonText() {
		playPauseButton.setText(clock != null && clock.getRate() != 0.0
				? trans.get("Flight3DFrame.pause") : trans.get("Flight3DFrame.play"));
	}

	private void updateTimeLabel(double time, double end) {
		timeLabel.setText(formatTime(time, end));
	}

	private static String formatTime(double time, double end) {
		return String.format(trans.get("Flight3DFrame.timeFormat"), time, end);
	}

	private int timeToSliderValue(double time) {
		if (clock == null || clock.getEnd() <= clock.getStart()) {
			return 0;
		}
		double fraction = (time - clock.getStart()) / (clock.getEnd() - clock.getStart());
		return (int) Math.round(Math.max(0.0, Math.min(1.0, fraction)) * SLIDER_STEPS);
	}

	private double sliderValueToTime(int value) {
		if (clock == null || clock.getEnd() <= clock.getStart()) {
			return clock != null ? clock.getStart() : 0.0;
		}
		double fraction = Math.max(0.0, Math.min(1.0, value / (double) SLIDER_STEPS));
		return clock.getStart() + fraction * (clock.getEnd() - clock.getStart());
	}

	private double selectedSpeed() {
		Object selected = speedCombo.getSelectedItem();
		return selected instanceof SpeedOption option ? option.rate() : 1.0;
	}

	private List<EventMarker> createMarkers(FlightReplayData replayData) {
		if (replayData == null) {
			return List.of();
		}
		List<EventMarker> markers = new ArrayList<>();
		for (FlightEvent event : replayData.getAllEvents()) {
			if (MARKER_TYPES.contains(event.getType())) {
				markers.add(new EventMarker(event.getTime(), event.getType().toString(), event.getType()));
			}
		}
		markers.sort(Comparator.comparingDouble(EventMarker::time));
		return List.copyOf(markers);
	}

	private record SpeedOption(double rate) {
		@Override
		public String toString() {
			if (rate == 0.25) {
				return "0.25x";
			}
			if (rate == 0.5) {
				return "0.5x";
			}
			if (rate == Math.rint(rate)) {
				return String.format("%.0fx", rate);
			}
			return String.format("%.2fx", rate);
		}
	}

	private record EventMarker(double time, String label, FlightEvent.Type type) {
	}

	private final class EventMarkerSlider extends JSlider {
		private List<EventMarker> markers = List.of();

		private EventMarkerSlider() {
			setToolTipText("");
		}

		private void setMarkers(List<EventMarker> markers) {
			this.markers = markers != null ? markers : List.of();
			repaint();
		}

		@Override
		public String getToolTipText(MouseEvent event) {
			EventMarker marker = findMarkerNear(event.getX());
			if (marker == null) {
				return null;
			}
			return String.format(trans.get("Flight3DFrame.eventTimeFormat"), marker.label(), marker.time());
		}

		@Override
		protected void paintComponent(Graphics graphics) {
			super.paintComponent(graphics);
			if (clock == null || markers.isEmpty() || clock.getEnd() <= clock.getStart()) {
				return;
			}
			Graphics2D g2 = (Graphics2D) graphics.create();
			try {
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				int y = getHeight() / 2 + 8;
				for (EventMarker marker : markers) {
					// Match the trajectory's event marker colors, so the slider ticks and the
					// 3D markers read as the same events.
					g2.setColor(FlightEventMarkers.hasColor(marker.type())
							? FlightEventMarkers.awtColorOf(marker.type()) : markerColor());
					int x = xForTime(marker.time());
					g2.drawLine(x, y - 5, x, y + 5);
				}
			} finally {
				g2.dispose();
			}
		}

		private EventMarker findMarkerNear(int x) {
			if (clock == null || markers.isEmpty()) {
				return null;
			}
			EventMarker nearest = null;
			int nearestDistance = Integer.MAX_VALUE;
			for (EventMarker marker : markers) {
				int distance = Math.abs(xForTime(marker.time()) - x);
				if (distance < nearestDistance) {
					nearest = marker;
					nearestDistance = distance;
				}
			}
			return nearestDistance <= 6 ? nearest : null;
		}

		private int xForTime(double time) {
			Insets insets = getInsets();
			int left = insets.left + 12;
			int right = getWidth() - insets.right - 12;
			if (right <= left || clock == null || clock.getEnd() <= clock.getStart()) {
				return left;
			}
			double fraction = (time - clock.getStart()) / (clock.getEnd() - clock.getStart());
			fraction = Math.max(0.0, Math.min(1.0, fraction));
			return left + (int) Math.round(fraction * (right - left));
		}

		private Color markerColor() {
			Color color = UIManager.getColor("Component.accentColor");
			return color != null ? color : new Color(0xC35A00);
		}
	}
}
