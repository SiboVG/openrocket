package info.openrocket.swing.gui.figure3d.flight;

import info.openrocket.swing.gui.figure3d.animation.PoseProvider;
import info.openrocket.swing.gui.figure3d.particles.Particle;
import info.openrocket.swing.gui.figure3d.particles.flame.FlameEmitter;
import info.openrocket.swing.gui.figure3d.particles.flame.FlameSettings;
import info.openrocket.swing.gui.figure3d.scene.properties.RenderingConfiguration;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;

/** Adapts the existing flame emitter to absolute replay time and moving motor nozzles. */
final class ReplayFlameEmitter extends FlameEmitter {
	private final PoseProvider provider;
	private final List<double[]> burnWindows;
	private final long seed;
	private final float particleTimeScale;

	ReplayFlameEmitter(RenderingConfiguration config, PoseProvider provider, List<double[]> burnWindows,
			Vector3f nozzle, Vector3f direction, float rocketLength, long seed) {
		// The pad/photo emitter's long lifetimes would leave burning particles far behind an
		// accelerating rocket. Run the same particle lifecycle faster, preserving its plume length.
		this(FlameSettings.normal(config, null, rocketLength * 0.03f, 3.0f),
				provider, burnWindows, nozzle, direction, seed, 24.0f);
	}

	ReplayFlameEmitter(FlameSettings settings, PoseProvider provider, List<double[]> burnWindows,
			Vector3f nozzle, Vector3f direction, long seed) {
		this(settings, provider, burnWindows, nozzle, direction, seed, 1.0f);
	}

	private ReplayFlameEmitter(FlameSettings settings, PoseProvider provider, List<double[]> burnWindows,
			Vector3f nozzle, Vector3f direction, long seed, float particleTimeScale) {
		super(nozzle, direction, settings);
		this.provider = provider;
		this.burnWindows = burnWindows.stream().map(double[]::clone).toList();
		this.seed = seed;
		this.particleTimeScale = particleTimeScale;
	}

	@Override
	public void update(float deltaTime) {
		// The replay supplies absolute time; the scene must not integrate wall-clock deltas too.
	}

	void setReplayTime(double time, boolean visible) {
		particles.clear();
		double rate = settings.getQualityAdjustedCreationRate() * particleTimeScale;
		double maximumAge = settings.maxLife / particleTimeScale;
		if (!visible || !Double.isFinite(time) || rate <= 0 || settings.maxLife <= 0) return;

		for (int windowIndex = 0; windowIndex < burnWindows.size(); windowIndex++) {
			double[] window = burnWindows.get(windowIndex);
			if (time < window[0] || time >= window[1] + maximumAge || window[1] <= window[0]) continue;
			// Only the last maximum lifetime can affect this frame, even after a long seek.
			long first = Math.max(1, (long) Math.floor((time - maximumAge - window[0]) * rate) + 1);
			long last = (long) Math.floor((Math.min(time, Math.nextDown(window[1])) - window[0]) * rate);
			for (long index = first; index <= last; index++) {
				double birthTime = window[0] + index / rate;
				float age = (float) (time - birthTime);
				if (age < 0) continue;

				// Reuse FlameEmitter's noise, colors, size, lifetime, velocity and spin.
				// Seeding each emission makes it reconstructible without replaying the whole burn.
				random.setSeed(emissionSeed(seed, windowIndex, index));
				currentTime = (float) (birthTime * particleTimeScale);
				super.createParticle();
				Particle particle = particles.get(particles.size() - 1);
				if (!particle.update(age * particleTimeScale, settings.gravity)) {
					particles.remove(particles.size() - 1);
					continue;
				}

				Quaternionf orientation = provider.getOrientation(birthTime);
				orientation.transform(particle.position);
				particle.position.add(provider.getPosition(birthTime));
				orientation.transform(particle.velocity);
				particle.velocity.mul(particleTimeScale);
				Vector3f inheritedVelocity = provider.getLinearVelocity(birthTime);
				if (inheritedVelocity != null) {
					particle.position.fma(age, inheritedVelocity);
					particle.velocity.add(inheritedVelocity);
				}
			}
		}
	}

	static long emissionSeed(long motorSeed, int windowIndex, long particleIndex) {
		long value = particleIndex + motorSeed * 31 + windowIndex * 104729L;
		value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
		value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
		return value ^ (value >>> 31);
	}
}
