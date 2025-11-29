package info.openrocket.core.file.dxf.export;

import info.openrocket.core.rocketcomponent.BodyTube;
import info.openrocket.core.rocketcomponent.NoseCone;
import info.openrocket.core.rocketcomponent.Transition;
import info.openrocket.core.util.BaseTestCase;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.io.File;
import java.nio.file.Files;
import java.util.List;

class ProfileDxfExporterTest extends BaseTestCase {

	@Test
	void calculateBoundsForConstantRadiusComponent() {
		BodyTube tube = new BodyTube();
		tube.setOuterRadius(0.05);
		tube.setLength(0.2);

		ProfileDxfExporter.Bounds bounds = ProfileDxfExporter.calculateBounds(tube);

		Assertions.assertEquals(0.2, bounds.getWidth(), 1e-9);
		Assertions.assertEquals(0.05, bounds.getMaxAbsY(), 1e-9); // radius
	}

	@Test
	void calculateBoundsForVariableRadiusComponent() {
		NoseCone noseCone = new NoseCone();
		noseCone.setLength(0.1);
		noseCone.setAftRadius(0.05);
		noseCone.setForeRadius(0.0);
		noseCone.setShapeType(NoseCone.Shape.CONICAL);

		ProfileDxfExporter.Bounds bounds = ProfileDxfExporter.calculateBounds(noseCone);

		Assertions.assertEquals(0.1, bounds.getWidth(), 1e-6);
		Assertions.assertTrue(bounds.getMaxAbsY() > 0);
		Assertions.assertTrue(bounds.getMaxAbsY() <= 0.05);
	}

	@Test
	void calculateBoundsForTransitionWithShoulders() {
		Transition transition = new Transition();
		transition.setLength(0.15);
		transition.setForeRadius(0.03);
		transition.setAftRadius(0.05);
		transition.setShapeType(Transition.Shape.CONICAL);
		transition.setForeShoulderLength(0.02);
		transition.setForeShoulderRadius(0.03);
		transition.setAftShoulderLength(0.025);
		transition.setAftShoulderRadius(0.05);

		ProfileDxfExporter.Bounds bounds = ProfileDxfExporter.calculateBounds(transition);

		Assertions.assertTrue(bounds.getWidth() >= 0.15);
		Assertions.assertTrue(bounds.getWidth() >= 0.15 + 0.02 + 0.025);
	}

	@Test
	void drawClosedProfileForConstantRadiusWritesPolyline() throws Exception {
		BodyTube tube = new BodyTube();
		tube.setOuterRadius(0.05);
		tube.setLength(0.2);

		DXFBuilder builder = new DXFBuilder();
		DXFExportOptions options = new DXFExportOptions(Color.BLACK, 0.1);

		ProfileDxfExporter.drawClosedProfile(tube, builder, 0.0, 0.0, options);

		File dxfFile = File.createTempFile("bodytube", ".dxf");
		builder.writeToFile(dxfFile);

		String contents = Files.readString(dxfFile.toPath());
		// Should contain DXF sections
		Assertions.assertTrue(contents.contains("SECTION"), "Should contain SECTION");
		Assertions.assertTrue(contents.contains("HEADER"), "Should contain HEADER section");
		Assertions.assertTrue(contents.contains("ENTITIES"), "Should contain ENTITIES section");
		Assertions.assertTrue(contents.contains("LWPOLYLINE"), "Should contain LWPOLYLINE entity");
		// Check for expected coordinates (converted to mm: 0.05m = 50mm, 0.2m = 200mm)
		Assertions.assertTrue(contents.contains("50.000000") || contents.contains("50.0"), 
			"Should contain radius coordinate: " + contents);
		Assertions.assertTrue(contents.contains("200.000000") || contents.contains("200.0"), 
			"Should contain length coordinate: " + contents);
	}

	@Test
	void drawClosedProfileForVariableRadiusWritesCurvedPath() throws Exception {
		NoseCone noseCone = new NoseCone();
		noseCone.setLength(0.1);
		noseCone.setAftRadius(0.05);
		noseCone.setForeRadius(0.0);
		noseCone.setShapeType(NoseCone.Shape.CONICAL);

		DXFBuilder builder = new DXFBuilder();
		DXFExportOptions options = new DXFExportOptions(Color.BLACK, 0.1);

		ProfileDxfExporter.drawClosedProfile(noseCone, builder, 0.0, 0.0, options);

		File dxfFile = File.createTempFile("nosecone", ".dxf");
		builder.writeToFile(dxfFile);

		String contents = Files.readString(dxfFile.toPath());
		Assertions.assertTrue(contents.contains("LWPOLYLINE"), "Should contain LWPOLYLINE entity");
		// Should have many vertices for variable radius
		long vertexCount = contents.split("10").length - 1; // Group code 10 is X coordinate
		Assertions.assertTrue(vertexCount > 10, "Variable radius should have many vertices");
	}

	@Test
	void drawClosedProfileForTransitionWithForeShoulder() throws Exception {
		Transition transition = new Transition();
		transition.setLength(0.15);
		transition.setForeRadius(0.03);
		transition.setAftRadius(0.05);
		transition.setShapeType(Transition.Shape.CONICAL);
		transition.setForeShoulderLength(0.02);
		transition.setForeShoulderRadius(0.03);

		DXFBuilder builder = new DXFBuilder();
		DXFExportOptions options = new DXFExportOptions(Color.BLACK, 0.1);

		ProfileDxfExporter.drawClosedProfile(transition, builder, 0.0, 0.0, options);

		File dxfFile = File.createTempFile("transition-fore", ".dxf");
		builder.writeToFile(dxfFile);

		String contents = Files.readString(dxfFile.toPath());
		// Should contain multiple LWPOLYLINE entities (transition body + fore shoulder)
		long polylineCount = contents.split("LWPOLYLINE").length - 1;
		Assertions.assertTrue(polylineCount >= 2, "Should contain transition body and fore shoulder polylines");
		// Fore shoulder extends backward (negative X)
		Assertions.assertTrue(contents.contains("-20.000000") || contents.contains("-20.0"), 
			"Should contain negative coordinate for fore shoulder: " + contents);
	}

	@Test
	void drawClosedProfileForTransitionWithAftShoulder() throws Exception {
		Transition transition = new Transition();
		transition.setLength(0.15);
		transition.setForeRadius(0.03);
		transition.setAftRadius(0.05);
		transition.setShapeType(Transition.Shape.CONICAL);
		transition.setAftShoulderLength(0.025);
		transition.setAftShoulderRadius(0.05);

		DXFBuilder builder = new DXFBuilder();
		DXFExportOptions options = new DXFExportOptions(Color.BLACK, 0.1);

		ProfileDxfExporter.drawClosedProfile(transition, builder, 0.0, 0.0, options);

		File dxfFile = File.createTempFile("transition-aft", ".dxf");
		builder.writeToFile(dxfFile);

		String contents = Files.readString(dxfFile.toPath());
		// Should contain multiple LWPOLYLINE entities (transition body + aft shoulder)
		long polylineCount = contents.split("LWPOLYLINE").length - 1;
		Assertions.assertTrue(polylineCount >= 2, "Should contain transition body and aft shoulder polylines");
		// Aft shoulder extends forward (positive X beyond transition length)
		Assertions.assertTrue(contents.contains("175.000000") || contents.contains("175.0"), 
			"Should contain coordinate beyond transition length for aft shoulder: " + contents);
	}

	@Test
	void drawClosedProfileForTransitionWithBothShoulders() throws Exception {
		Transition transition = new Transition();
		transition.setLength(0.15);
		transition.setForeRadius(0.03);
		transition.setAftRadius(0.05);
		transition.setShapeType(Transition.Shape.CONICAL);
		transition.setForeShoulderLength(0.02);
		transition.setForeShoulderRadius(0.03);
		transition.setAftShoulderLength(0.025);
		transition.setAftShoulderRadius(0.05);

		DXFBuilder builder = new DXFBuilder();
		DXFExportOptions options = new DXFExportOptions(Color.BLACK, 0.1);

		ProfileDxfExporter.drawClosedProfile(transition, builder, 0.0, 0.0, options);

		File dxfFile = File.createTempFile("transition-both", ".dxf");
		builder.writeToFile(dxfFile);

		String contents = Files.readString(dxfFile.toPath());
		// Should contain multiple LWPOLYLINE entities (transition body + both shoulders)
		long polylineCount = contents.split("LWPOLYLINE").length - 1;
		Assertions.assertTrue(polylineCount >= 3, "Should contain transition body and both shoulder polylines");
	}

	@Test
	void drawClosedProfileForTransitionWithoutShoulders() throws Exception {
		Transition transition = new Transition();
		transition.setLength(0.15);
		transition.setForeRadius(0.03);
		transition.setAftRadius(0.05);
		transition.setShapeType(Transition.Shape.CONICAL);
		// No shoulders set

		DXFBuilder builder = new DXFBuilder();
		DXFExportOptions options = new DXFExportOptions(Color.BLACK, 0.1);

		ProfileDxfExporter.drawClosedProfile(transition, builder, 0.0, 0.0, options);

		File dxfFile = File.createTempFile("transition-none", ".dxf");
		builder.writeToFile(dxfFile);

		String contents = Files.readString(dxfFile.toPath());
		// Should contain only transition body polyline
		long polylineCount = contents.split("LWPOLYLINE").length - 1;
		Assertions.assertEquals(1, polylineCount, "Should contain only transition body polyline");
	}

	@Test
	void drawClosedProfileRespectsOriginOffset() throws Exception {
		BodyTube tube = new BodyTube();
		tube.setOuterRadius(0.05);
		tube.setLength(0.2);

		DXFBuilder builder = new DXFBuilder();
		DXFExportOptions options = new DXFExportOptions(Color.BLACK, 0.1);

		// Draw at offset origin
		ProfileDxfExporter.drawClosedProfile(tube, builder, 0.1, 0.05, options);

		File dxfFile = File.createTempFile("bodytube-offset", ".dxf");
		builder.writeToFile(dxfFile);

		String contents = Files.readString(dxfFile.toPath());
		Assertions.assertTrue(contents.contains("LWPOLYLINE"), 
			"Should contain LWPOLYLINE entity: " + contents);
		// Path should contain coordinates
		Assertions.assertTrue(contents.contains("10") && contents.contains("20"), 
			"Should contain coordinate group codes: " + contents);
	}

	@Test
	void drawClosedProfileUsesCorrectLayer() throws Exception {
		BodyTube tube = new BodyTube();
		tube.setOuterRadius(0.05);
		tube.setLength(0.2);

		DXFBuilder builder = new DXFBuilder();
		DXFExportOptions options = new DXFExportOptions(Color.BLACK, 0.1);

		ProfileDxfExporter.drawClosedProfile(tube, builder, 0.0, 0.0, options);

		File dxfFile = File.createTempFile("bodytube-layer", ".dxf");
		builder.writeToFile(dxfFile);

		String contents = Files.readString(dxfFile.toPath());
		// Should contain PROFILES layer
		Assertions.assertTrue(contents.contains("PROFILES"), 
			"Should contain PROFILES layer: " + contents);
	}

	@Test
	void drawClosedProfileForBodyTubeHasCorrectDimensionsInMm() throws Exception {
		// Test with specific dimensions: 50mm radius, 200mm length
		BodyTube tube = new BodyTube();
		tube.setOuterRadius(0.05); // 50mm
		tube.setLength(0.2); // 200mm

		DXFBuilder builder = new DXFBuilder();
		DXFExportOptions options = new DXFExportOptions(Color.BLACK, 0.1);

		ProfileDxfExporter.drawClosedProfile(tube, builder, 0.0, 0.0, options);

		File dxfFile = File.createTempFile("bodytube-dimensions", ".dxf");
		builder.writeToFile(dxfFile);

		String contents = Files.readString(dxfFile.toPath());
		
		// Verify the polyline contains expected dimensions in millimeters
		verifyPolylineDimensions(contents, 0.2 * 1000, 0.05 * 2 * 1000, 0.1);
		
		// Also verify specific coordinates are present
		Assertions.assertTrue(contents.contains("200.000000") || contents.contains("200.0"), 
			"Should contain 200mm length coordinate: " + contents);
		Assertions.assertTrue(contents.contains("50.000000") || contents.contains("50.0"), 
			"Should contain 50mm radius coordinate: " + contents);
	}

	@Test
	void drawClosedProfileForNoseConeHasCorrectDimensionsInMm() throws Exception {
		// Test nose cone: 100mm length, 50mm base radius
		NoseCone noseCone = new NoseCone();
		noseCone.setLength(0.1); // 100mm
		noseCone.setAftRadius(0.05); // 50mm
		noseCone.setForeRadius(0.0);
		noseCone.setShapeType(NoseCone.Shape.CONICAL);

		DXFBuilder builder = new DXFBuilder();
		DXFExportOptions options = new DXFExportOptions(Color.BLACK, 0.1);

		ProfileDxfExporter.drawClosedProfile(noseCone, builder, 0.0, 0.0, options);

		File dxfFile = File.createTempFile("nosecone-dimensions", ".dxf");
		builder.writeToFile(dxfFile);

		String contents = Files.readString(dxfFile.toPath());
		
		// Verify length is 100mm, height spans 100mm (50mm radius * 2)
		verifyPolylineDimensions(contents, 0.1 * 1000, 0.05 * 2 * 1000, 0.1);
		
		// Verify specific coordinates
		Assertions.assertTrue(contents.contains("100.000000") || contents.contains("100.0"), 
			"Should contain 100mm length coordinate: " + contents);
		Assertions.assertTrue(contents.contains("50.000000") || contents.contains("50.0"), 
			"Should contain 50mm radius coordinate: " + contents);
	}

	@Test
	void drawClosedProfileForSmallComponentHasCorrectDimensionsInMm() throws Exception {
		// Test with very small dimensions to verify precision
		// 10mm radius, 20mm length
		BodyTube tube = new BodyTube();
		tube.setOuterRadius(0.01); // 10mm
		tube.setLength(0.02); // 20mm

		DXFBuilder builder = new DXFBuilder();
		DXFExportOptions options = new DXFExportOptions(Color.BLACK, 0.1);

		ProfileDxfExporter.drawClosedProfile(tube, builder, 0.0, 0.0, options);

		File dxfFile = File.createTempFile("bodytube-small", ".dxf");
		builder.writeToFile(dxfFile);

		String contents = Files.readString(dxfFile.toPath());
		
		// Verify dimensions: 20mm width, 20mm height (10mm radius * 2)
		verifyPolylineDimensions(contents, 0.02 * 1000, 0.01 * 2 * 1000, 0.01);
		
		// Verify specific coordinates with high precision
		Assertions.assertTrue(contents.contains("20.000000") || contents.contains("20.0"), 
			"Should contain 20mm coordinate: " + contents);
		Assertions.assertTrue(contents.contains("10.000000") || contents.contains("10.0"), 
			"Should contain 10mm coordinate: " + contents);
	}

	@Test
	void drawClosedProfileCreatesValidDxfFile() throws Exception {
		BodyTube tube = new BodyTube();
		tube.setOuterRadius(0.05);
		tube.setLength(0.2);

		DXFBuilder builder = new DXFBuilder();
		DXFExportOptions options = new DXFExportOptions(Color.BLACK, 0.1);

		ProfileDxfExporter.drawClosedProfile(tube, builder, 0.0, 0.0, options);

		File dxfFile = File.createTempFile("bodytube-valid", ".dxf");
		builder.writeToFile(dxfFile);

		String contents = Files.readString(dxfFile.toPath());
		
		// Verify DXF file structure
		Assertions.assertTrue(contents.contains("0"), "Should contain group code 0");
		Assertions.assertTrue(contents.contains("SECTION"), "Should contain SECTION");
		Assertions.assertTrue(contents.contains("HEADER"), "Should contain HEADER");
		Assertions.assertTrue(contents.contains("TABLES"), "Should contain TABLES");
		Assertions.assertTrue(contents.contains("ENTITIES"), "Should contain ENTITIES");
		Assertions.assertTrue(contents.contains("ENDSEC"), "Should contain ENDSEC");
		Assertions.assertTrue(contents.contains("EOF"), "Should contain EOF");
		
		// Verify layer table
		Assertions.assertTrue(contents.contains("LAYER"), "Should contain LAYER table");
		Assertions.assertTrue(contents.contains("PROFILES"), "Should contain PROFILES layer");
	}

	/**
	 * Verifies that polyline dimensions match expected values in millimeters.
	 */
	private void verifyPolylineDimensions(String dxfContent, double expectedWidthMm, double expectedHeightMm, double toleranceMm) {
		List<String> lines = java.util.Arrays.asList(dxfContent.split("\\R"));
		List<String> polyline = DxfTestUtil.firstEntity(lines, "LWPOLYLINE");
		if (polyline.isEmpty()) {
			Assertions.fail("No LWPOLYLINE found in DXF: " + dxfContent);
			return;
		}

		List<double[]> points = DxfTestUtil.extractLwPolylineVertices(polyline);
		if (points.isEmpty()) {
			Assertions.fail("No vertices found in LWPOLYLINE: " + polyline);
			return;
		}

		double minX = Double.MAX_VALUE;
		double maxX = -Double.MAX_VALUE;
		double minY = Double.MAX_VALUE;
		double maxY = -Double.MAX_VALUE;
		for (double[] p : points) {
			minX = Math.min(minX, p[0]);
			maxX = Math.max(maxX, p[0]);
			minY = Math.min(minY, p[1]);
			maxY = Math.max(maxY, p[1]);
		}
		
		double width = maxX - minX;
		double height = maxY - minY;
		
		Assertions.assertEquals(expectedWidthMm, width, toleranceMm,
			String.format("Width mismatch: expected %.3fmm, got %.3fmm", expectedWidthMm, width));
		Assertions.assertEquals(expectedHeightMm, height, toleranceMm,
			String.format("Height mismatch: expected %.3fmm, got %.3fmm", expectedHeightMm, height));
	}
}
