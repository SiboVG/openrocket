package info.openrocket.swing.gui.figure3d.flight;

import info.openrocket.swing.gui.figure3d.animation.PlaybackClock;
import org.junit.jupiter.api.Test;

import javax.swing.JSlider;
import javax.swing.SwingUtilities;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlaybackTransportBarTest {

	@Test
	void sliderModelChangesSeekWithoutMouseDragging() throws Exception {
		SwingUtilities.invokeAndWait(() -> {
			PlaybackTransportBar bar = new PlaybackTransportBar();
			PlaybackClock clock = new PlaybackClock(0.0, 10.0);
			bar.setReplay(clock, null);
			try {
				JSlider slider = Arrays.stream(bar.getComponents())
						.filter(JSlider.class::isInstance)
						.map(JSlider.class::cast)
						.findFirst()
						.orElseThrow();

				slider.setValue(5_000);

				assertEquals(5.0, clock.getTime(), 1e-6);
			} finally {
				bar.dispose();
			}
		});
	}
}
