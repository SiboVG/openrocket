package info.openrocket.core.file.step;

import info.openrocket.core.file.wavefrontobj.DefaultObj;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class STEPWriterTest {
	private static final OffsetDateTime FIXED_TIMESTAMP = OffsetDateTime.parse("2026-08-14T12:30:00+02:00");

	@Test
	void writesClosedMeshAsFacetedBrepInMillimetres() throws Exception {
		DefaultObj cube = createCube();
		ByteArrayOutputStream output = new ByteArrayOutputStream();

		STEPWriter.Result result = STEPWriter.write(cube, output, "Test cube", "cube.step", FIXED_TIMESTAMP);
		String step = output.toString(StandardCharsets.US_ASCII);

		assertEquals(1, result.solidCount());
		assertEquals(0, result.surfaceModelCount());
		assertEquals(0, result.skippedFaceCount());
		assertTrue(step.startsWith("ISO-10303-21;"));
		assertTrue(step.contains("FILE_SCHEMA(('AUTOMOTIVE_DESIGN'))"));
		assertTrue(step.contains("SI_UNIT(.MILLI.,.METRE.)"));
		assertTrue(step.contains("CARTESIAN_POINT('',(1000.,1000.,1000.))"));
		assertTrue(step.contains("FACE_SURFACE("));
		assertTrue(step.contains("PLANE("));
		assertTrue(step.contains("FACETED_BREP('Box'"));
		assertFalse(step.contains("SHELL_BASED_SURFACE_MODEL"));
		assertTrue(step.endsWith("END-ISO-10303-21;\n"));
	}

	@Test
	void writesOpenMeshAsSurfaceModelAndSkipsDegenerateFaces() throws Exception {
		DefaultObj obj = new DefaultObj();
		obj.setActiveGroupNames("Open panel");
		obj.addVertex(0, 0, 0);
		obj.addVertex(1, 0, 0);
		obj.addVertex(0, 1, 0);
		obj.addFace(0, 1, 2);
		obj.addFace(0, 0, 1);
		ByteArrayOutputStream output = new ByteArrayOutputStream();

		STEPWriter.Result result = STEPWriter.write(obj, output, "Panel", "panel.step", FIXED_TIMESTAMP);
		String step = output.toString(StandardCharsets.US_ASCII);

		assertEquals(0, result.solidCount());
		assertEquals(1, result.surfaceModelCount());
		assertEquals(1, result.skippedFaceCount());
		assertTrue(step.contains("OPEN_SHELL('Open panel'"));
		assertTrue(step.contains("SHELL_BASED_SURFACE_MODEL('Open panel'"));
		assertFalse(step.contains("FACETED_BREP"));
	}

	@Test
	void splitsWarpedPolygonIntoPlanarFacets() throws Exception {
		DefaultObj obj = new DefaultObj();
		obj.setActiveGroupNames("Warped panel");
		obj.addVertex(0, 0, 0);
		obj.addVertex(1, 0, 0);
		obj.addVertex(1, 1, 0.25f);
		obj.addVertex(0, 1, 0);
		obj.addFace(0, 1, 2, 3);
		ByteArrayOutputStream output = new ByteArrayOutputStream();

		STEPWriter.Result result = STEPWriter.write(obj, output, "Warped panel", "panel.step", FIXED_TIMESTAMP);
		String step = output.toString(StandardCharsets.US_ASCII);

		assertEquals(0, result.solidCount());
		assertEquals(1, result.surfaceModelCount());
		assertEquals(2, countOccurrences(step, "FACE_SURFACE("));
	}

	@Test
	void escapesApostrophesBackslashesAndUnicode() {
		String greekCapitalDelta = Character.toString(0x0394);
		String encoded = STEPWriter.stepString("Builder's " + greekCapitalDelta + "\\path");

		assertTrue(encoded.startsWith("'Builder''s "));
		assertTrue(encoded.contains("\\X2\\0394\\X0\\"));
		assertTrue(encoded.contains("\\\\path"));
		assertTrue(encoded.endsWith("'"));
	}

	private static int countOccurrences(String value, String search) {
		int count = 0;
		int index = value.indexOf(search);
		while (index >= 0) {
			count++;
			index = value.indexOf(search, index + search.length());
		}
		return count;
	}

	private static DefaultObj createCube() {
		DefaultObj obj = new DefaultObj();
		obj.setActiveGroupNames("Box");
		obj.addVertex(0, 0, 0);
		obj.addVertex(1, 0, 0);
		obj.addVertex(1, 1, 0);
		obj.addVertex(0, 1, 0);
		obj.addVertex(0, 0, 1);
		obj.addVertex(1, 0, 1);
		obj.addVertex(1, 1, 1);
		obj.addVertex(0, 1, 1);

		// Outward winding is required for a consistently oriented closed shell.
		obj.addFace(0, 3, 2, 1);
		obj.addFace(4, 5, 6, 7);
		obj.addFace(0, 1, 5, 4);
		obj.addFace(3, 7, 6, 2);
		obj.addFace(0, 4, 7, 3);
		obj.addFace(1, 2, 6, 5);
		return obj;
	}
}
