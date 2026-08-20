package info.openrocket.swing.gui.figure3d.scene.graph;

import info.openrocket.swing.gui.figure3d.geometry.Mesh;
import info.openrocket.swing.gui.figure3d.materials.Appearance3D;
import info.openrocket.swing.gui.figure3d.rendering.Renderable;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SceneObjectTest {
	@Test
	void foregroundRedrawIsOptIn() {
		SceneObject object = SceneObject.withRenderable(null, mock(Mesh.class), mock(Renderable.class),
				new Vector3f(), mock(Appearance3D.class));

		assertFalse(object.isRenderInForeground());
		object.setRenderInForeground(true);
		assertTrue(object.isRenderInForeground());
	}

	@Test
	void uniformVisualScalePreservesTheObjectOrigin() {
		SceneObject object = SceneObject.withRenderable(null, mock(Mesh.class), mock(Renderable.class),
				new Vector3f(2.0f, 3.0f, 4.0f), mock(Appearance3D.class));

		object.setUniformScale(0.25f);

		Matrix4f transform = object.getModelMatrix();
		assertEquals(new Vector3f(2.0f, 3.0f, 4.0f), transform.transformPosition(new Vector3f()));
		assertEquals(new Vector3f(2.25f, 3.0f, 4.0f),
				transform.transformPosition(new Vector3f(1.0f, 0.0f, 0.0f)));
	}

	@Test
	void cleanupReleasesOwnedResourcesOnlyOnce() {
		Renderable renderable = mock(Renderable.class);
		Appearance3D appearance = mock(Appearance3D.class);
		SceneObject object = SceneObject.withRenderable(null, mock(Mesh.class), renderable,
				new Vector3f(), appearance);

		object.cleanup();
		object.cleanup();

		verify(renderable).cleanup();
		verify(appearance).cleanup();
	}
}
