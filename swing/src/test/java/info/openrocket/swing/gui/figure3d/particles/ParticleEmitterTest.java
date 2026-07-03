package info.openrocket.swing.gui.figure3d.particles;

import info.openrocket.swing.gui.figure3d.animation.PoseProvider;
import info.openrocket.swing.gui.figure3d.scene.properties.RenderingConfiguration;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ParticleEmitterTest {

	@Test
	void appliesPoseProviderToBaseEmitterTransform() {
		TestEmitter emitter = new TestEmitter(new Vector3f(1.0f, 0.0f, 0.0f), new Vector3f(1.0f, 0.0f, 0.0f));
		emitter.setPoseProvider(new PoseProvider() {
			@Override
			public Vector3f getPosition(double t) {
				return new Vector3f(10.0f, 0.0f, 0.0f);
			}

			@Override
			public Quaternionf getOrientation(double t) {
				return new Quaternionf().rotateTo(new Vector3f(1.0f, 0.0f, 0.0f), new Vector3f(0.0f, 1.0f, 0.0f));
			}

			@Override
			public double getStartTime() {
				return 0.0;
			}

			@Override
			public double getEndTime() {
				return 1.0;
			}
		});

		emitter.applyPoseAtTime(0.0);

		assertEquals(10.0f, emitter.getEmitterPosition().x, 1e-5);
		assertEquals(1.0f, emitter.getEmitterPosition().y, 1e-5);
		assertEquals(0.0f, emitter.getEmitterPosition().z, 1e-5);
		assertEquals(0.0f, emitter.getDirection().x, 1e-5);
		assertEquals(1.0f, emitter.getDirection().y, 1e-5);
		assertEquals(0.0f, emitter.getDirection().z, 1e-5);
	}

	private static final class TestEmitter extends ParticleEmitter {
		TestEmitter(Vector3f emitterPosition, Vector3f direction) {
			super(emitterPosition, direction, new ParticleSettings(
					0.0f,
					0.0f,
					1.0f,
					1.0f,
					1.0f,
					1.0f,
					0.0f,
					false,
					new Vector3f(),
					new Vector3f(),
					new Vector3f(1.0f, 1.0f, 1.0f),
					RenderingConfiguration.builder().build()));
		}

		@Override
		protected void createParticle() {
		}
	}
}
