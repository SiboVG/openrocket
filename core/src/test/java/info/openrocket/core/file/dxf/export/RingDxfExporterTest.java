package info.openrocket.core.file.dxf.export;

import info.openrocket.core.rocketcomponent.InnerTube;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.io.File;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;

class RingDxfExporterTest {

	@Test
	void renderRingWritesOuterAndInnerCircles() throws Exception {
		DXFBuilder builder = new DXFBuilder();
		DXFExportOptions options = new DXFExportOptions(Color.BLACK, 0.2, true);

		RingDxfExporter.renderRing(builder, 0, 0, 0.05, 0.02,
				Collections.emptyList(), options);

		File dxfFile = File.createTempFile("ring", ".dxf");
		builder.writeToFile(dxfFile);

		String contents = Files.readString(dxfFile.toPath());
		// Should contain CIRCLE entities
		Assertions.assertTrue(contents.contains("CIRCLE"), "Should contain CIRCLE entity");
		// Check for radius values (converted to mm: 0.05m = 50mm, 0.02m = 20mm)
		Assertions.assertTrue(contents.contains("40") && contents.contains("50.000000"), 
			"Should contain outer radius 50mm (group code 40): " + contents);
		Assertions.assertTrue(contents.contains("40") && contents.contains("20.000000"), 
			"Should contain inner radius 20mm (group code 40): " + contents);
	}

	@Test
	void renderRingWritesOnlyOuterCircleWhenInnerRadiusIsZero() throws Exception {
		DXFBuilder builder = new DXFBuilder();
		DXFExportOptions options = new DXFExportOptions(Color.BLACK, 0.2, true);

		RingDxfExporter.renderRing(builder, 0, 0, 0.05, 0.0,
				Collections.emptyList(), options);

		File dxfFile = File.createTempFile("ring-outer-only", ".dxf");
		builder.writeToFile(dxfFile);

		String contents = Files.readString(dxfFile.toPath());
		// Should contain only one CIRCLE entity (outer)
		long circleCount = contents.split("CIRCLE").length - 1;
		Assertions.assertEquals(1, circleCount, "Should contain only outer circle: " + contents);
	}

	@Test
	void renderRingWritesCrosshairWhenEnabled() throws Exception {
		DXFBuilder builder = new DXFBuilder();
		DXFExportOptions options = new DXFExportOptions(Color.BLACK, 0.2, true);

		RingDxfExporter.renderRing(builder, 0, 0, 0.05, 0.02,
				Collections.emptyList(), options);

		File dxfFile = File.createTempFile("ring-crosshair", ".dxf");
		builder.writeToFile(dxfFile);

		String contents = Files.readString(dxfFile.toPath());
		// Should contain LINE entities for crosshair
		Assertions.assertTrue(contents.contains("LINE"), "Should contain LINE entities for crosshair: " + contents);
		// Should be on CROSSHAIRS layer
		Assertions.assertTrue(contents.contains("CROSSHAIRS"), "Should contain CROSSHAIRS layer: " + contents);
	}

	@Test
	void renderRingSkipsCrosshairWhenDisabled() throws Exception {
		DXFBuilder builder = new DXFBuilder();
		DXFExportOptions options = new DXFExportOptions(Color.BLACK, 0.2, false);

		RingDxfExporter.renderRing(builder, 0, 0, 0.05, 0.02,
				Collections.emptyList(), options);

		File dxfFile = File.createTempFile("ring-no-crosshair", ".dxf");
		builder.writeToFile(dxfFile);

		String contents = Files.readString(dxfFile.toPath());
		// Should not contain LINE entities (only circles)
		long lineCount = contents.split("LINE").length - 1;
		Assertions.assertEquals(0, lineCount, "Should not contain LINE entities: " + contents);
	}

	@Test
	void renderRingWritesHoles() throws Exception {
		DXFBuilder builder = new DXFBuilder();
		DXFExportOptions options = new DXFExportOptions(Color.BLACK, 0.2, true);

		List<RingDxfExporter.Hole> holes = List.of(
			new RingDxfExporter.Hole(0.01, 0.0, 0.005),
			new RingDxfExporter.Hole(-0.01, 0.0, 0.003)
		);

		RingDxfExporter.renderRing(builder, 0, 0, 0.05, 0.02, holes, options);

		File dxfFile = File.createTempFile("ring-holes", ".dxf");
		builder.writeToFile(dxfFile);

		String contents = Files.readString(dxfFile.toPath());
		// Should contain 2 additional CIRCLE entities for holes (plus outer and inner)
		long circleCount = contents.split("CIRCLE").length - 1;
		Assertions.assertTrue(circleCount >= 4, "Should contain outer, inner, and 2 hole circles: " + contents);
		// Check for hole coordinates (converted to mm)
		Assertions.assertTrue(contents.contains("10.000000") || contents.contains("10.0"), 
			"Should contain hole offset coordinate: " + contents);
	}

	@Test
	void holesFromMotorMountsFallsBackToRadialShift() {
		InnerTube tube = new InnerTube();
		tube.setOuterRadius(0.01);
		tube.setRadialShift(0.02, -0.01);

		List<RingDxfExporter.Hole> holes = RingDxfExporter.holesFromMotorMounts(Collections.singletonList(tube));
		Assertions.assertEquals(1, holes.size());
		RingDxfExporter.Hole hole = holes.get(0);
		Assertions.assertEquals(0.02, hole.offsetY(), 1e-9);
		Assertions.assertEquals(-0.01, hole.offsetZ(), 1e-9);
		Assertions.assertEquals(0.01, hole.radius(), 1e-9);
	}

	@Test
	void renderRingUsesCorrectLayer() throws Exception {
		DXFBuilder builder = new DXFBuilder();
		DXFExportOptions options = new DXFExportOptions(Color.BLACK, 0.2, true);

		RingDxfExporter.renderRing(builder, 0, 0, 0.05, 0.02,
				Collections.emptyList(), options);

		File dxfFile = File.createTempFile("ring-layer", ".dxf");
		builder.writeToFile(dxfFile);

		String contents = Files.readString(dxfFile.toPath());
		// Should contain PROFILES layer for circles
		Assertions.assertTrue(contents.contains("PROFILES"), 
			"Should contain PROFILES layer: " + contents);
	}

	@Test
	void renderRingHasCorrectDimensionsInMm() throws Exception {
		// Test with specific dimensions: 50mm outer radius, 20mm inner radius
		DXFBuilder builder = new DXFBuilder();
		DXFExportOptions options = new DXFExportOptions(Color.BLACK, 0.2, true);

		RingDxfExporter.renderRing(builder, 0, 0, 0.05, 0.02,
				Collections.emptyList(), options);

		File dxfFile = File.createTempFile("ring-dimensions", ".dxf");
		builder.writeToFile(dxfFile);

		String contents = Files.readString(dxfFile.toPath());
		
		// Extract circle radius values (group code 40)
		java.util.regex.Pattern radiusPattern = java.util.regex.Pattern.compile("40\\s+([\\d.\\-]+)");
		java.util.regex.Matcher matcher = radiusPattern.matcher(contents);
		
		boolean foundOuter = false;
		boolean foundInner = false;
		
		while (matcher.find()) {
			double radius = Double.parseDouble(matcher.group(1));
			if (Math.abs(radius - 50.0) < 0.1) {
				foundOuter = true;
			}
			if (Math.abs(radius - 20.0) < 0.1) {
				foundInner = true;
			}
		}
		
		Assertions.assertTrue(foundOuter, "Should contain outer radius 50mm");
		Assertions.assertTrue(foundInner, "Should contain inner radius 20mm");
	}
}

