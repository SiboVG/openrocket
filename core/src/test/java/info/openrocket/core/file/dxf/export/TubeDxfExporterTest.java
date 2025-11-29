package info.openrocket.core.file.dxf.export;

import info.openrocket.core.rocketcomponent.BodyTube;
import info.openrocket.core.rocketcomponent.LaunchLug;
import info.openrocket.core.util.BaseTestCase;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.io.File;
import java.nio.file.Files;
import java.util.List;

class TubeDxfExporterTest extends BaseTestCase {

	@Test
	void calculateBoundsForBodyTube() {
		BodyTube tube = new BodyTube();
		tube.setOuterRadius(0.05);
		tube.setLength(0.2);

		TubeDxfExporter.Bounds bounds = TubeDxfExporter.calculateBounds(tube, tube.getLength());

		Assertions.assertTrue(bounds.getWidth() > 0);
		Assertions.assertTrue(bounds.getHeight() > 0);
		// Should include side profile + spacing + back profile
		Assertions.assertTrue(bounds.getWidth() >= 0.2, "Width should include side profile length");
	}

	@Test
	void drawTubeProfileWritesSideAndBackProfiles() throws Exception {
		BodyTube tube = new BodyTube();
		tube.setOuterRadius(0.05);
		tube.setLength(0.2);

		DXFBuilder builder = new DXFBuilder();
		DXFExportOptions options = new DXFExportOptions(Color.BLACK, 0.1);

		TubeDxfExporter.drawTubeProfile(tube, tube.getLength(), builder, 0.0, 0.0, options);

		File dxfFile = File.createTempFile("tube", ".dxf");
		builder.writeToFile(dxfFile);

		String contents = Files.readString(dxfFile.toPath());
		// Should contain LWPOLYLINE for side profile (rectangle)
		Assertions.assertTrue(contents.contains("LWPOLYLINE"), "Should contain LWPOLYLINE for side profile");
		// Should contain CIRCLE for back profile
		Assertions.assertTrue(contents.contains("CIRCLE"), "Should contain CIRCLE for back profile");
	}

	@Test
	void drawTubeProfileWritesCrosshairWhenEnabled() throws Exception {
		BodyTube tube = new BodyTube();
		tube.setOuterRadius(0.05);
		tube.setLength(0.2);

		DXFBuilder builder = new DXFBuilder();
		DXFExportOptions options = new DXFExportOptions(Color.BLACK, 0.1, true);

		TubeDxfExporter.drawTubeProfile(tube, tube.getLength(), builder, 0.0, 0.0, options);

		File dxfFile = File.createTempFile("tube-crosshair", ".dxf");
		builder.writeToFile(dxfFile);

		String contents = Files.readString(dxfFile.toPath());
		// Should contain LINE entities for crosshair
		Assertions.assertTrue(contents.contains("LINE"), "Should contain LINE entities for crosshair");
		Assertions.assertTrue(contents.contains("CROSSHAIRS"), "Should contain CROSSHAIRS layer");
	}

	@Test
	void drawTubeProfileSkipsCrosshairWhenDisabled() throws Exception {
		BodyTube tube = new BodyTube();
		tube.setOuterRadius(0.05);
		tube.setLength(0.2);

		DXFBuilder builder = new DXFBuilder();
		DXFExportOptions options = new DXFExportOptions(Color.BLACK, 0.1, false);

		TubeDxfExporter.drawTubeProfile(tube, tube.getLength(), builder, 0.0, 0.0, options);

		File dxfFile = File.createTempFile("tube-no-crosshair", ".dxf");
		builder.writeToFile(dxfFile);

		List<String> lines = Files.readAllLines(dxfFile.toPath());
		long lineCount = DxfTestUtil.countEntities(lines, "LINE");
		Assertions.assertEquals(0, lineCount, "Should not contain LINE entities");
	}

	@Test
	void drawTubeProfileWritesInnerCircleForHollowTube() throws Exception {
		BodyTube tube = new BodyTube();
		tube.setOuterRadius(0.05);
		tube.setInnerRadius(0.04);
		tube.setLength(0.2);
		tube.setFilled(false);

		DXFBuilder builder = new DXFBuilder();
		DXFExportOptions options = new DXFExportOptions(Color.BLACK, 0.1);

		TubeDxfExporter.drawTubeProfile(tube, tube.getLength(), builder, 0.0, 0.0, options);

		File dxfFile = File.createTempFile("tube-hollow", ".dxf");
		builder.writeToFile(dxfFile);

		String contents = Files.readString(dxfFile.toPath());
		// Should contain 2 CIRCLE entities (outer and inner)
		long circleCount = contents.split("CIRCLE").length - 1;
		Assertions.assertEquals(2, circleCount, "Should contain outer and inner circles: " + contents);
		// Check for inner radius (0.04m = 40mm)
		Assertions.assertTrue(contents.contains("40.000000") || contents.contains("40.0"), 
			"Should contain inner radius 40mm: " + contents);
	}

	@Test
	void drawTubeProfileSkipsInnerCircleForFilledTube() throws Exception {
		BodyTube tube = new BodyTube();
		tube.setOuterRadius(0.05);
		tube.setInnerRadius(0.04);
		tube.setLength(0.2);
		tube.setFilled(true);

		DXFBuilder builder = new DXFBuilder();
		DXFExportOptions options = new DXFExportOptions(Color.BLACK, 0.1);

		TubeDxfExporter.drawTubeProfile(tube, tube.getLength(), builder, 0.0, 0.0, options);

		File dxfFile = File.createTempFile("tube-filled", ".dxf");
		builder.writeToFile(dxfFile);

		String contents = Files.readString(dxfFile.toPath());
		// Should contain only 1 CIRCLE entity (outer)
		long circleCount = contents.split("CIRCLE").length - 1;
		Assertions.assertEquals(1, circleCount, "Should contain only outer circle: " + contents);
	}

	@Test
	void drawTubeProfileForLaunchLug() throws Exception {
		LaunchLug lug = new LaunchLug();
		lug.setOuterRadius(0.01);
		lug.setLength(0.05);

		DXFBuilder builder = new DXFBuilder();
		DXFExportOptions options = new DXFExportOptions(Color.BLACK, 0.1);

		TubeDxfExporter.drawTubeProfile(lug, lug.getLength(), builder, 0.0, 0.0, options);

		File dxfFile = File.createTempFile("launchlug", ".dxf");
		builder.writeToFile(dxfFile);

		String contents = Files.readString(dxfFile.toPath());
		Assertions.assertTrue(contents.contains("LWPOLYLINE"), "Should contain LWPOLYLINE for side profile");
		Assertions.assertTrue(contents.contains("CIRCLE"), "Should contain CIRCLE for back profile");
	}

	@Test
	void drawTubeProfileHasCorrectDimensionsInMm() throws Exception {
		// Test with specific dimensions: 50mm radius, 200mm length
		BodyTube tube = new BodyTube();
		tube.setOuterRadius(0.05); // 50mm
		tube.setLength(0.2); // 200mm

		DXFBuilder builder = new DXFBuilder();
		DXFExportOptions options = new DXFExportOptions(Color.BLACK, 0.1);

		TubeDxfExporter.drawTubeProfile(tube, tube.getLength(), builder, 0.0, 0.0, options);

		File dxfFile = File.createTempFile("tube-dimensions", ".dxf");
		builder.writeToFile(dxfFile);

		List<String> lines = Files.readAllLines(dxfFile.toPath());
		List<String> polyline = DxfTestUtil.firstEntity(lines, "LWPOLYLINE");
		Assertions.assertFalse(polyline.isEmpty(), "Expected a LWPOLYLINE entity");

		List<double[]> points = DxfTestUtil.extractLwPolylineVertices(polyline);
		Assertions.assertFalse(points.isEmpty(), "Expected vertices in LWPOLYLINE: " + polyline);

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
		// Side profile should be 200mm long (length)
		double width = maxX - minX;
		Assertions.assertEquals(200.0, width, 0.1,
				String.format("Side profile width: expected 200.0mm, got %.3fmm", width));

		// Side profile should be 100mm tall (radius * 2)
		double height = maxY - minY;
		Assertions.assertEquals(100.0, height, 0.1,
				String.format("Side profile height: expected 100.0mm, got %.3fmm", height));
		
		// Verify back profile circle radius
		String contents = Files.readString(dxfFile.toPath());
		java.util.regex.Pattern radiusPattern = java.util.regex.Pattern.compile("40\\s+([\\d.\\-]+)");
		java.util.regex.Matcher radiusMatcher = radiusPattern.matcher(contents);
		
		boolean foundOuterRadius = false;
		while (radiusMatcher.find()) {
			double radius = Double.parseDouble(radiusMatcher.group(1));
			if (Math.abs(radius - 50.0) < 0.1) {
				foundOuterRadius = true;
				break;
			}
		}
		
		Assertions.assertTrue(foundOuterRadius, "Should contain outer radius 50mm in back profile");
	}

	@Test
	void drawTubeProfileUsesCorrectLayers() throws Exception {
		BodyTube tube = new BodyTube();
		tube.setOuterRadius(0.05);
		tube.setLength(0.2);

		DXFBuilder builder = new DXFBuilder();
		DXFExportOptions options = new DXFExportOptions(Color.BLACK, 0.1, true);

		TubeDxfExporter.drawTubeProfile(tube, tube.getLength(), builder, 0.0, 0.0, options);

		File dxfFile = File.createTempFile("tube-layers", ".dxf");
		builder.writeToFile(dxfFile);

		String contents = Files.readString(dxfFile.toPath());
		// Should contain PROFILES layer for side and back profiles
		Assertions.assertTrue(contents.contains("PROFILES"), 
			"Should contain PROFILES layer: " + contents);
		// Should contain CROSSHAIRS layer if crosshair is enabled
		Assertions.assertTrue(contents.contains("CROSSHAIRS"), 
			"Should contain CROSSHAIRS layer: " + contents);
	}
}
