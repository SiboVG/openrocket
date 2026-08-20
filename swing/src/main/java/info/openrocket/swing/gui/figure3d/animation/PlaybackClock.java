package info.openrocket.swing.gui.figure3d.animation;

import info.openrocket.core.util.MathUtil;

/** Small playback clock with play/pause/speed and clamping. */
public final class PlaybackClock {
	private double time;
	private double rate = 1.0;
	private final double start, end;

	public PlaybackClock(double start, double end) {
		this.start = start;
		this.end = Math.max(end, start);
		this.time = start;
	}

	public synchronized void update(double dtRealSeconds) {
		time += rate * dtRealSeconds;
		if (time < start) time = start;
		if (time > end)   time = end;
	}

	public synchronized double getTime()        { return time; }
	public synchronized void   setTime(double t){ time = MathUtil.clamp(t, start, end); }
	public synchronized double getRate()        { return rate; }
	public synchronized void   setRate(double r){ rate = r; }
	public double getStart()       { return start; }
	public double getEnd()         { return end; }
}
