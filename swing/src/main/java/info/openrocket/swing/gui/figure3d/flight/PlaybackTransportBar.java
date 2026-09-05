package info.openrocket.swing.gui.figure3d.flight;

import info.openrocket.core.l10n.Translator;
import info.openrocket.core.simulation.FlightEvent;
import info.openrocket.core.startup.Application;
import info.openrocket.swing.gui.figure3d.animation.PlaybackClock;
import info.openrocket.swing.gui.util.Icons;
import info.openrocket.swing.gui.widgets.IconButton;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JList;
import javax.swing.JOptionPane;
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
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.KeyEvent;
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
	private static final double FRAME_STEP_SECONDS = 1.0 / 60.0;
	private static final EnumSet<FlightEvent.Type> MARKER_TYPES = EnumSet.of(
			FlightEvent.Type.IGNITION,
			FlightEvent.Type.LAUNCHROD,
			FlightEvent.Type.LIFTOFF,
			FlightEvent.Type.BURNOUT,
			FlightEvent.Type.STAGE_SEPARATION,
			FlightEvent.Type.APOGEE,
			FlightEvent.Type.RECOVERY_DEVICE_DEPLOYMENT,
			FlightEvent.Type.GROUND_HIT);

	private final JButton restartButton = new IconButton(Icons.PLAYBACK_RESTART);
	private final JButton previousFrameButton = new IconButton(Icons.PLAYBACK_STEP_BACK);
	private final JButton playPauseButton = new IconButton(Icons.PLAYBACK_PLAY);
	private final JButton nextFrameButton = new IconButton(Icons.PLAYBACK_STEP_FORWARD);
	private final JCheckBox loopButton = new JCheckBox(trans.get("Flight3DFrame.loop"));
	private final JComboBox<EventMarker> eventCombo = new JComboBox<>();
	private final JCheckBox trailButton = new JCheckBox(trans.get("Flight3DFrame.showTrail"), true);
	private final JCheckBox exhaustButton = new JCheckBox(trans.get("Flight3DFrame.showExhaust"), true);
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
	private Consumer<Boolean> trailVisibilityListener;
	private Consumer<Boolean> exhaustVisibilityListener;
	private Runnable replayChangeListener;
	private boolean viewControlsEnabled;
	private boolean userIsDragging;
	private boolean programmaticUpdate;
	private double rateBeforeScrub;
	private boolean updatingEvents;

	PlaybackTransportBar() {
		setLayout(new BorderLayout(8, 4));
		setBorder(BorderFactory.createEmptyBorder(4, 6, 6, 6));

		JPanel leftControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
		restartButton.setToolTipText(trans.get("Flight3DFrame.restart.ttip"));
		restartButton.addActionListener(e -> restartPlayback());
		leftControls.add(restartButton);
		previousFrameButton.setToolTipText(trans.get("Flight3DFrame.previousFrame.ttip"));
		previousFrameButton.addActionListener(e -> stepFrame(-1));
		leftControls.add(previousFrameButton);
		leftControls.add(playPauseButton);
		nextFrameButton.setToolTipText(trans.get("Flight3DFrame.nextFrame.ttip"));
		nextFrameButton.addActionListener(e -> stepFrame(1));
		leftControls.add(nextFrameButton);
		leftControls.add(speedCombo);
		speedCombo.setToolTipText(trans.get("Flight3DFrame.speed.ttip"));
		loopButton.setToolTipText(trans.get("Flight3DFrame.loop.ttip"));
		loopButton.addActionListener(e -> {
			if (clock != null) clock.setLooping(loopButton.isSelected());
		});
		leftControls.add(loopButton);
		eventCombo.setPrototypeDisplayValue(new EventMarker(999.99, trans.get("Flight3DFrame.events"), null));
		eventCombo.setRenderer(new DefaultListCellRenderer() {
			@Override
			public Component getListCellRendererComponent(JList<?> list, Object value, int index,
					boolean selected, boolean focused) {
				return super.getListCellRendererComponent(list,
						value == null ? trans.get("Flight3DFrame.events") : value, index, selected, focused);
			}
		});
		eventCombo.setToolTipText(trans.get("Flight3DFrame.events.ttip"));
		eventCombo.addActionListener(e -> {
			if (!updatingEvents && eventCombo.getSelectedItem() instanceof EventMarker marker) {
				pauseAndSeek(marker.time());
			}
		});
		leftControls.add(eventCombo);
		add(leftControls, BorderLayout.NORTH);

		JPanel viewControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
		cameraModeCombo.setSelectedItem(FlightCameraMode.OVERVIEW);
		cameraModeCombo.setToolTipText(trans.get("Flight3DFrame.cameraMode.ttip"));
		cameraModeCombo.addActionListener(e -> {
			updatePanControlAvailability();
			if (cameraModeListener != null) {
				cameraModeListener.accept((FlightCameraMode) cameraModeCombo.getSelectedItem());
			}
		});
		viewControls.add(cameraModeCombo);

		zoomOutButton.setToolTipText(trans.get("ScaleSelector.btn.ZoomOut.ttip"));
		zoomOutButton.addActionListener(e -> runViewAction(zoomOutListener));
		viewControls.add(zoomOutButton);
		zoomInButton.setToolTipText(trans.get("ScaleSelector.btn.ZoomIn.ttip"));
		zoomInButton.addActionListener(e -> runViewAction(zoomInListener));
		viewControls.add(zoomInButton);
		zoomFitButton.setToolTipText(trans.get("ScaleSelector.btn.ZoomFit.ttip"));
		zoomFitButton.addActionListener(e -> runViewAction(zoomFitListener));
		viewControls.add(zoomFitButton);
		panButton.setToolTipText(trans.get("Flight3DFrame.pan.ttip"));
		panButton.addActionListener(e -> {
			if (panModeListener != null) {
				panModeListener.accept(panButton.isSelected());
			}
		});
		viewControls.add(panButton);
		trailButton.addActionListener(e -> {
			if (trailVisibilityListener != null) trailVisibilityListener.accept(trailButton.isSelected());
		});
		exhaustButton.addActionListener(e -> {
			if (exhaustVisibilityListener != null) exhaustVisibilityListener.accept(exhaustButton.isSelected());
		});
		viewControls.add(trailButton);
		viewControls.add(exhaustButton);
		JButton help = new JButton(trans.get("Flight3DFrame.controls"), Icons.HELP);
		help.setToolTipText(trans.get("Flight3DFrame.controls.ttip"));
		help.addActionListener(e -> JOptionPane.showMessageDialog(this, trans.get("Flight3DFrame.controls.ttip"),
				trans.get("Flight3DFrame.controls"), JOptionPane.INFORMATION_MESSAGE));
		viewControls.add(help);
		add(viewControls, BorderLayout.SOUTH);

		scrubSlider.setMinimum(0);
		scrubSlider.setMaximum(SLIDER_STEPS);
		scrubSlider.setValue(0);
		scrubSlider.setPaintTicks(false);
		scrubSlider.setEnabled(false);
		MouseAdapter scrubMouseListener = new MouseAdapter() {
			@Override
			public void mouseMoved(MouseEvent e) {
				scrubSlider.updateMarkerHover(e.getX(), e.getY());
			}

			@Override
			public void mouseExited(MouseEvent e) {
				scrubSlider.updateMarkerHover(-1, -1);
			}
		};
		scrubSlider.addMouseListener(scrubMouseListener);
		scrubSlider.addMouseMotionListener(scrubMouseListener);
		scrubSlider.addChangeListener(this::handleSliderChanged);
		JPanel timeline = new JPanel(new BorderLayout(8, 0));
		timeline.add(scrubSlider, BorderLayout.CENTER);
		add(timeline, BorderLayout.CENTER);

		timeLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 12));
		timeLabel.setPreferredSize(new Dimension(160, timeLabel.getPreferredSize().height));
		timeline.add(timeLabel, BorderLayout.EAST);

		speedCombo.setSelectedIndex(2);
		speedCombo.addActionListener(e -> {
			if (clock != null && clock.getRate() != 0.0) {
				clock.setRate(selectedSpeed());
			}
		});
		playPauseButton.addActionListener(e -> togglePlayback());
		updatePlaybackButton();
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

	JCheckBox getTrailButton() {
		return trailButton;
	}

	JComboBox<FlightCameraMode> getCameraModeCombo() {
		return cameraModeCombo;
	}

	JButton getRestartButton() {
		return restartButton;
	}

	JButton getPreviousFrameButton() {
		return previousFrameButton;
	}

	JButton getPlayPauseButton() {
		return playPauseButton;
	}

	JButton getNextFrameButton() {
		return nextFrameButton;
	}

	JSlider getScrubSlider() {
		return scrubSlider;
	}

	void setReplayChangeListener(Runnable listener) {
		this.replayChangeListener = listener;
	}

	void setVisibilityListeners(Consumer<Boolean> trailListener, Consumer<Boolean> exhaustListener) {
		trailVisibilityListener = trailListener;
		exhaustVisibilityListener = exhaustListener;
	}

	void setReplay(PlaybackClock clock, FlightReplayData replayData) {
		clearReplay();
		this.clock = clock;
		List<EventMarker> markers = createMarkers(replayData);
		scrubSlider.setMarkers(markers);
		updatingEvents = true;
		try {
			eventCombo.removeAllItems();
			markers.forEach(eventCombo::addItem);
			eventCombo.setSelectedIndex(-1);
		} finally {
			updatingEvents = false;
		}
		setControlsEnabled(clock != null);
		if (clock == null) {
			pollTimer.stop();
			updateTimeLabel(0.0, 0.0);
			return;
		}
		clock.setLooping(loopButton.isSelected());
		if (clock.getRate() != 0.0) clock.setRate(selectedSpeed());
		updatePlaybackButton();
		updateFromClock();
		pollTimer.start();
	}

	void clearReplay() {
		pollTimer.stop();
		userIsDragging = false;
		rateBeforeScrub = 0.0;
		if (clock != null) {
			clock.setRate(0.0);
		}
		clock = null;
		updatingEvents = true;
		try {
			eventCombo.removeAllItems();
		} finally {
			updatingEvents = false;
		}
		scrubSlider.setMarkers(List.of());
		scrubSlider.setValue(0);
		setControlsEnabled(false);
		updatePlaybackButton();
		updateTimeLabel(0.0, 0.0);
	}

	void dispose() {
		pollTimer.stop();
		clearReplay();
	}

	private void setControlsEnabled(boolean enabled) {
		restartButton.setEnabled(enabled);
		previousFrameButton.setEnabled(enabled);
		playPauseButton.setEnabled(enabled);
		nextFrameButton.setEnabled(enabled);
		speedCombo.setEnabled(enabled);
		loopButton.setEnabled(enabled);
		eventCombo.setEnabled(enabled && eventCombo.getItemCount() > 0);
		cameraModeCombo.setEnabled(enabled);
		trailButton.setEnabled(enabled);
		exhaustButton.setEnabled(enabled);
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
		updatePlaybackButton();
		updateFromClock();
		notifyReplayChanged();
	}

	private void restartPlayback() {
		if (clock == null) {
			return;
		}
		clock.setRate(0.0);
		clock.setTime(clock.getStart());
		updatePlaybackButton();
		updateFromClock();
		notifyReplayChanged();
	}

	private void stepFrame(int direction) {
		if (clock == null) {
			return;
		}
		clock.setRate(0.0);
		clock.setTime(clock.getTime() + direction * FRAME_STEP_SECONDS);
		updatePlaybackButton();
		updateFromClock();
		notifyReplayChanged();
	}

	private void pauseAndSeek(double time) {
		if (clock == null) return;
		clock.setRate(0.0);
		seekToTime(time);
		updatePlaybackButton();
	}

	private void jumpToEvent(int direction) {
		if (clock == null) return;
		double time = clock.getTime();
		double target = direction < 0 ? clock.getStart() : clock.getEnd();
		for (EventMarker marker : scrubSlider.markers) {
			if (direction > 0 && marker.time() > time + 1e-6) {
				target = marker.time();
				break;
			}
			if (direction < 0 && marker.time() < time - 1e-6) target = marker.time();
		}
		pauseAndSeek(target);
	}

	/** Called on the EDT by the replay window, including when its heavyweight canvas has focus. */
	boolean handleReplayKey(KeyEvent event) {
		if (clock == null || userIsDragging || event.getID() != KeyEvent.KEY_PRESSED
				|| event.isAltDown() || event.isControlDown() || event.isMetaDown()) return false;
		switch (event.getKeyCode()) {
			case KeyEvent.VK_SPACE -> togglePlayback();
			case KeyEvent.VK_LEFT -> {
				if (event.isShiftDown()) jumpToEvent(-1); else stepFrame(-1);
			}
			case KeyEvent.VK_RIGHT -> {
				if (event.isShiftDown()) jumpToEvent(1); else stepFrame(1);
			}
			case KeyEvent.VK_HOME -> pauseAndSeek(clock.getStart());
			case KeyEvent.VK_END -> pauseAndSeek(clock.getEnd());
			case KeyEvent.VK_F -> runViewAction(zoomFitListener);
			case KeyEvent.VK_1 -> cameraModeCombo.setSelectedItem(FlightCameraMode.OVERVIEW);
			case KeyEvent.VK_2 -> cameraModeCombo.setSelectedItem(FlightCameraMode.FOLLOW);
			case KeyEvent.VK_3 -> cameraModeCombo.setSelectedItem(FlightCameraMode.PAD);
			default -> { return false; }
		}
		return true;
	}

	private void pollClock() {
		if (clock == null) {
			return;
		}
		if (clock.getRate() > 0.0 && clock.getTime() >= clock.getEnd()
				&& (!clock.isLooping() || clock.getEnd() <= clock.getStart())) {
			clock.setRate(0.0);
		}
		updatePlaybackButton();
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
		seekToTime(sliderValueToTime(scrubSlider.getValue()));
	}

	private void seekToTime(double time) {
		if (clock == null) {
			return;
		}
		clock.setTime(time);
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

	private void updatePlaybackButton() {
		boolean playing = clock != null && clock.getRate() != 0.0;
		playPauseButton.setIcon(playing ? Icons.PLAYBACK_PAUSE : Icons.PLAYBACK_PLAY);
		String label = trans.get(playing ? "Flight3DFrame.pause" : "Flight3DFrame.play");
		playPauseButton.setToolTipText(label + " (Space)");
		playPauseButton.getAccessibleContext().setAccessibleName(label);
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
			if (MARKER_TYPES.contains(event.getType()) && Double.isFinite(event.getTime())
					&& event.getTime() >= replayData.getStartTime() && event.getTime() <= replayData.getEndTime()) {
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
		@Override
		public String toString() {
			return String.format(trans.get("Flight3DFrame.eventTimeFormat"), label, time);
		}
	}

	private final class EventMarkerSlider extends JSlider {
		private List<EventMarker> markers = List.of();
		private EventMarker hoveredMarker;
		private boolean markerGesture;

		private EventMarkerSlider() {
			setToolTipText("");
			setPreferredSize(new Dimension(300, 44));
			getAccessibleContext().setAccessibleName(trans.get("Flight3DFrame.timeline"));
		}

		@Override
		protected void processMouseEvent(MouseEvent event) {
			if (isEnabled() && clock != null && javax.swing.SwingUtilities.isLeftMouseButton(event)) {
				if (event.getID() == MouseEvent.MOUSE_PRESSED) {
					requestFocusInWindow();
					EventMarker marker = findMarkerNear(event.getX(), event.getY());
					markerGesture = marker != null;
					if (markerGesture) {
						pauseAndSeek(marker.time());
					} else {
						rateBeforeScrub = clock.getRate();
						clock.setRate(0.0);
						userIsDragging = true;
						seekAtX(event.getX());
						updatePlaybackButton();
					}
					return;
				}
				if (event.getID() == MouseEvent.MOUSE_RELEASED) {
					if (userIsDragging) {
						seekAtX(event.getX());
						userIsDragging = false;
						clock.setRate(rateBeforeScrub);
						updateFromClock();
						updatePlaybackButton();
						notifyReplayChanged();
					}
					markerGesture = false;
					return;
				}
			}
			super.processMouseEvent(event);
		}

		@Override
		protected void processMouseMotionEvent(MouseEvent event) {
			if (event.getID() == MouseEvent.MOUSE_DRAGGED && (userIsDragging || markerGesture)) {
				if (userIsDragging) seekAtX(event.getX());
				return;
			}
			super.processMouseMotionEvent(event);
		}

		private void seekAtX(int x) {
			int left = getInsets().left + 12;
			int width = Math.max(1, getWidth() - getInsets().right - 12 - left);
			setValue((int) Math.round((x - left) * (double) SLIDER_STEPS / width));
			seekToSliderValue();
		}

		private void setMarkers(List<EventMarker> markers) {
			this.markers = markers != null ? markers : List.of();
			hoveredMarker = null;
			markerGesture = false;
			repaint();
		}

		@Override
		public String getToolTipText(MouseEvent event) {
			EventMarker marker = findMarkerNear(event.getX(), event.getY());
			if (marker == null) {
				return null;
			}
			return String.format(trans.get("Flight3DFrame.eventTimeFormat"), marker.label(), marker.time());
		}

		@Override
		protected void paintComponent(Graphics graphics) {
			Graphics2D g2 = (Graphics2D) graphics.create();
			try {
				g2.setColor(getBackground());
				g2.fillRect(0, 0, getWidth(), getHeight());
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				int left = getInsets().left + 12;
				int right = Math.max(left, getWidth() - getInsets().right - 12);
				int trackY = getHeight() / 2 - 4;
				int thumbX = left + (int) Math.round((right - left) * getValue() / (double) SLIDER_STEPS);
				Color trackColor = UIManager.getColor("Slider.trackColor");
				g2.setColor(trackColor != null ? trackColor : Color.GRAY);
				g2.fillRoundRect(left, trackY - 2, right - left, 4, 4, 4);
				if (!isEnabled()) return;
				g2.setColor(markerColor());
				g2.fillRoundRect(left, trackY - 2, thumbX - left, 4, 4, 4);
				g2.fillOval(thumbX - 6, trackY - 6, 12, 12);
				if (hasFocus()) g2.drawOval(thumbX - 9, trackY - 9, 18, 18);
				if (clock == null || clock.getEnd() <= clock.getStart()) return;
				int y = markerY();
				for (EventMarker marker : markers) {
					// Match the trajectory's event marker colors, so the slider ticks and the
					// 3D markers read as the same events.
					g2.setColor(FlightEventMarkers.hasColor(marker.type())
							? FlightEventMarkers.awtColorOf(marker.type()) : markerColor());
					int x = xForTime(marker.time());
					g2.drawLine(x, y - 6, x, y + 5);
					int diameter = marker == hoveredMarker ? 8 : 6;
					g2.fillOval(x - diameter / 2, y - diameter / 2, diameter, diameter);
				}
			} finally {
				g2.dispose();
			}
		}

		private EventMarker findMarkerNear(int x, int y) {
			if (clock == null || markers.isEmpty()) {
				return null;
			}
			if (Math.abs(markerY() - y) > 9) {
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
			return nearestDistance <= 8 ? nearest : null;
		}

		private void updateMarkerHover(int x, int y) {
			EventMarker marker = findMarkerNear(x, y);
			if (marker == hoveredMarker) {
				return;
			}
			hoveredMarker = marker;
			setCursor(marker != null
					? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
					: Cursor.getDefaultCursor());
			repaint();
		}

		private int markerY() {
			return getHeight() / 2 + 8;
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
