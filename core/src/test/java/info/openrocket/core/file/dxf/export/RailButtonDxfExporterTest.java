package info.openrocket.core.file.dxf.export;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import info.openrocket.core.ServicesForTesting;
import info.openrocket.core.plugin.PluginModule;
import info.openrocket.core.rocketcomponent.RailButton;
import info.openrocket.core.startup.Application;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.io.File;
import java.nio.file.Files;

class RailButtonDxfExporterTest {

	@BeforeAll
	static void setup() {
		Module applicationModule = new ServicesForTesting();
		Module pluginModule = new PluginModule();
		Injector injector = Guice.createInjector(applicationModule, pluginModule);
		Application.setInjector(injector);
	}

	@Test
	void calculateBoundsForNominalRailButton() {
		RailButton railButton = createRailButton(0.02, 0.01, 0.005, 0.002, 0.0);

		RailButtonDxfExporter.Bounds bounds = RailButtonDxfExporter.calculateBounds(railButton);

		Assertions.assertEquals(0.02, bounds.getWidth(), 1e-9); // outerDiameter
		Assertions.assertEquals(railButton.getTotalHeight(), bounds.getHeight(), 1e-9); // totalHeight
		Assertions.assertEquals(-0.01, bounds.getMinX(), 1e-9); // -outerRadius
		Assertions.assertEquals(0.0, bounds.getMinY(), 1e-9); // bottom at y=0
	}

	@Test
	void calculateBoundsForRailButtonWithZeroBaseHeight() {
		RailButton railButton = createRailButton(0.02, 0.01, 0.0, 0.002, 0.0);

		RailButtonDxfExporter.Bounds bounds = RailButtonDxfExporter.calculateBounds(railButton);

		Assertions.assertEquals(0.02, bounds.getWidth(), 1e-9);
		double expectedHeight = railButton.getInnerHeight() + railButton.getFlangeHeight();
		Assertions.assertEquals(expectedHeight, bounds.getHeight(), 1e-9);
	}

	@Test
	void drawRailButtonProfileWritesThreePolylines() throws Exception {
		RailButton railButton = createRailButton(0.02, 0.01, 0.005, 0.002, 0.0);

		DXFBuilder builder = new DXFBuilder();
		DXFExportOptions options = new DXFExportOptions(Color.BLACK, 0.1);

		RailButtonDxfExporter.drawRailButtonProfile(railButton, builder, 0.0, 0.0, options);

		File dxfFile = File.createTempFile("railbutton", ".dxf");
		builder.writeToFile(dxfFile);

		String contents = Files.readString(dxfFile.toPath());
		// Should contain 3 LWPOLYLINE entities (base, inner, flange)
		long polylineCount = contents.split("LWPOLYLINE").length - 1;
		Assertions.assertEquals(3, polylineCount, "Should contain 3 rectangle polylines: " + contents);
	}

	@Test
	void drawRailButtonProfileSkipsZeroHeightBase() throws Exception {
		RailButton railButton = createRailButton(0.02, 0.01, 0.0, 0.002, 0.0);

		DXFBuilder builder = new DXFBuilder();
		DXFExportOptions options = new DXFExportOptions(Color.BLACK, 0.1);

		RailButtonDxfExporter.drawRailButtonProfile(railButton, builder, 0.0, 0.0, options);

		File dxfFile = File.createTempFile("railbutton-nobase", ".dxf");
		builder.writeToFile(dxfFile);

		String contents = Files.readString(dxfFile.toPath());
		// Should contain only 2 LWPOLYLINE entities (inner, flange)
		long polylineCount = contents.split("LWPOLYLINE").length - 1;
		Assertions.assertEquals(2, polylineCount, "Should contain only inner and flange polylines: " + contents);
	}

	@Test
	void drawRailButtonProfileSkipsZeroHeightFlange() throws Exception {
		RailButton railButton = createRailButton(0.02, 0.01, 0.005, 0.0, 0.0);

		DXFBuilder builder = new DXFBuilder();
		DXFExportOptions options = new DXFExportOptions(Color.BLACK, 0.1);

		RailButtonDxfExporter.drawRailButtonProfile(railButton, builder, 0.0, 0.0, options);

		File dxfFile = File.createTempFile("railbutton-noflange", ".dxf");
		builder.writeToFile(dxfFile);

		String contents = Files.readString(dxfFile.toPath());
		// Should contain only 2 LWPOLYLINE entities (base, inner)
		long polylineCount = contents.split("LWPOLYLINE").length - 1;
		Assertions.assertEquals(2, polylineCount, "Should contain only base and inner polylines: " + contents);
	}

	@Test
	void drawRailButtonProfileBaseRectangleHasCorrectDimensions() throws Exception {
		RailButton railButton = createRailButton(0.02, 0.01, 0.005, 0.002, 0.0);

		DXFBuilder builder = new DXFBuilder();
		DXFExportOptions options = new DXFExportOptions(Color.BLACK, 0.1);

		RailButtonDxfExporter.drawRailButtonProfile(railButton, builder, 0.0, 0.0, options);

		File dxfFile = File.createTempFile("railbutton-base", ".dxf");
		builder.writeToFile(dxfFile);

		String contents = Files.readString(dxfFile.toPath());
		// Base rectangle: outerDiameter wide (20mm), baseHeight tall (5mm)
		// Should contain coordinates: -10mm to +10mm (outerRadius), 0 to 5mm (baseHeight)
		Assertions.assertTrue(contents.contains("-10.000000") || contents.contains("-10.0"), 
			"Should contain negative outer radius: " + contents);
		Assertions.assertTrue(contents.contains("10.000000") || contents.contains("10.0"), 
			"Should contain positive outer radius: " + contents);
		Assertions.assertTrue(contents.contains("5.000000") || contents.contains("5.0"), 
			"Should contain base height coordinate: " + contents);
	}

	@Test
	void drawRailButtonProfileUsesCorrectLayer() throws Exception {
		RailButton railButton = createRailButton(0.02, 0.01, 0.005, 0.002, 0.0);

		DXFBuilder builder = new DXFBuilder();
		DXFExportOptions options = new DXFExportOptions(Color.BLACK, 0.1);

		RailButtonDxfExporter.drawRailButtonProfile(railButton, builder, 0.0, 0.0, options);

		File dxfFile = File.createTempFile("railbutton-layer", ".dxf");
		builder.writeToFile(dxfFile);

		String contents = Files.readString(dxfFile.toPath());
		// Should contain PROFILES layer
		Assertions.assertTrue(contents.contains("PROFILES"), 
			"Should contain PROFILES layer: " + contents);
	}

	@Test
	void drawRailButtonProfileHasCorrectDimensionsInMm() throws Exception {
		// Test with specific dimensions: 20mm outer diameter, 10mm inner diameter
		// Base: 5mm, Inner: 2mm, Flange: 2mm
		RailButton railButton = createRailButton(0.02, 0.01, 0.005, 0.002, 0.0);

		DXFBuilder builder = new DXFBuilder();
		DXFExportOptions options = new DXFExportOptions(Color.BLACK, 0.1);

		RailButtonDxfExporter.drawRailButtonProfile(railButton, builder, 0.0, 0.0, options);

		File dxfFile = File.createTempFile("railbutton-dimensions", ".dxf");
		builder.writeToFile(dxfFile);

		String contents = Files.readString(dxfFile.toPath());
		
		// Extract all LWPOLYLINE entities and verify dimensions
		java.util.regex.Pattern polylinePattern = java.util.regex.Pattern.compile("LWPOLYLINE[\\s\\S]*?(?=LWPOLYLINE|EOF|ENDSEC)");
		java.util.regex.Matcher polylineMatcher = polylinePattern.matcher(contents);
		
		int polylineIndex = 0;
		while (polylineMatcher.find()) {
			String polylineData = polylineMatcher.group(0);
			
			// Extract coordinates (group codes 10 and 20)
			java.util.regex.Pattern coordPattern = java.util.regex.Pattern.compile("10\\s+([\\d.\\-]+)\\s+20\\s+([\\d.\\-]+)");
			java.util.regex.Matcher coordMatcher = coordPattern.matcher(polylineData);
			
			double minX = Double.MAX_VALUE;
			double maxX = -Double.MAX_VALUE;
			double minY = Double.MAX_VALUE;
			double maxY = -Double.MAX_VALUE;
			
			while (coordMatcher.find()) {
				double x = Double.parseDouble(coordMatcher.group(1));
				double y = Double.parseDouble(coordMatcher.group(2));
				minX = Math.min(minX, x);
				maxX = Math.max(maxX, x);
				minY = Math.min(minY, y);
				maxY = Math.max(maxY, y);
			}
			
			double width = maxX - minX;
			double height = maxY - minY;
			
			if (polylineIndex == 0) {
				// Base rectangle: 20mm wide (outerDiameter), 5mm tall (baseHeight)
				Assertions.assertEquals(20.0, width, 0.1,
					String.format("Base rectangle width: expected 20.0mm, got %.3fmm", width));
				Assertions.assertEquals(5.0, height, 0.1,
					String.format("Base rectangle height: expected 5.0mm, got %.3fmm", height));
				Assertions.assertEquals(0.0, minY, 0.1,
					"Base rectangle should start at y=0");
			} else if (polylineIndex == 1) {
				// Inner rectangle: 10mm wide (innerDiameter), 2mm tall (innerHeight)
				Assertions.assertEquals(10.0, width, 0.1,
					String.format("Inner rectangle width: expected 10.0mm, got %.3fmm", width));
				Assertions.assertEquals(2.0, height, 0.1,
					String.format("Inner rectangle height: expected 2.0mm, got %.3fmm", height));
				Assertions.assertEquals(5.0, minY, 0.1,
					"Inner rectangle should start at y=5mm (after base)");
			} else if (polylineIndex == 2) {
				// Flange rectangle: 20mm wide (outerDiameter), 2mm tall (flangeHeight)
				Assertions.assertEquals(20.0, width, 0.1,
					String.format("Flange rectangle width: expected 20.0mm, got %.3fmm", width));
				Assertions.assertEquals(2.0, height, 0.1,
					String.format("Flange rectangle height: expected 2.0mm, got %.3fmm", height));
				Assertions.assertEquals(7.0, minY, 0.1,
					"Flange rectangle should start at y=7mm (after base + inner)");
				Assertions.assertEquals(9.0, maxY, 0.1,
					"Flange rectangle should end at y=9mm (total height)");
			}
			
			polylineIndex++;
		}
		
		Assertions.assertEquals(3, polylineIndex, "Should have exactly 3 rectangles");
	}

	@Test
	void drawRailButtonProfileConvertsMetersToMillimetersCorrectly() throws Exception {
		// Test the conversion factor: 1 meter = 1000 millimeters
		// Use a simple case: 10mm outer diameter, 5mm base height
		RailButton railButton = new RailButton();
		railButton.setOuterDiameter(0.01); // 10mm
		railButton.setInnerDiameter(0.008); // 8mm
		railButton.setBaseHeight(0.005); // 5mm
		railButton.setFlangeHeight(0.002); // 2mm
		railButton.setTotalHeight(0.009); // 9mm total

		DXFBuilder builder = new DXFBuilder();
		DXFExportOptions options = new DXFExportOptions(Color.BLACK, 0.1);

		RailButtonDxfExporter.drawRailButtonProfile(railButton, builder, 0.0, 0.0, options);

		File dxfFile = File.createTempFile("railbutton-conversion", ".dxf");
		builder.writeToFile(dxfFile);

		String contents = Files.readString(dxfFile.toPath());
		
		// Extract first LWPOLYLINE (base rectangle) and verify dimensions
		java.util.regex.Pattern polylinePattern = java.util.regex.Pattern.compile("LWPOLYLINE[\\s\\S]*?(?=LWPOLYLINE|EOF|ENDSEC)");
		java.util.regex.Matcher polylineMatcher = polylinePattern.matcher(contents);
		
		Assertions.assertTrue(polylineMatcher.find(), "Should contain at least one LWPOLYLINE");
		String polylineData = polylineMatcher.group(0);
		
		// Parse coordinates
		java.util.regex.Pattern coordPattern = java.util.regex.Pattern.compile("10\\s+([\\d.\\-]+)\\s+20\\s+([\\d.\\-]+)");
		java.util.regex.Matcher coordMatcher = coordPattern.matcher(polylineData);
		
		double minX = Double.MAX_VALUE;
		double maxX = -Double.MAX_VALUE;
		double minY = Double.MAX_VALUE;
		double maxY = -Double.MAX_VALUE;
		
		while (coordMatcher.find()) {
			double x = Double.parseDouble(coordMatcher.group(1));
			double y = Double.parseDouble(coordMatcher.group(2));
			minX = Math.min(minX, x);
			maxX = Math.max(maxX, x);
			minY = Math.min(minY, y);
			maxY = Math.max(maxY, y);
		}
		
		// Verify conversion: 0.01m outer diameter = 10mm in DXF
		double width = maxX - minX;
		Assertions.assertEquals(10.0, width, 0.01,
			String.format("Outer diameter conversion: 0.01m should be 10.0mm, got %.3fmm", width));
		
		// Verify conversion: 0.005m base height = 5mm in DXF
		double height = maxY - minY;
		Assertions.assertEquals(5.0, height, 0.01,
			String.format("Base height conversion: 0.005m should be 5.0mm, got %.3fmm", height));
	}

	private static RailButton createRailButton(double outerDiameter, double innerDiameter, 
	                                           double baseHeight, double flangeHeight, double screwHeight) {
		RailButton railButton = new RailButton();
		railButton.setOuterDiameter(outerDiameter);
		railButton.setInnerDiameter(innerDiameter);
		railButton.setBaseHeight(baseHeight);
		railButton.setFlangeHeight(flangeHeight);
		double innerHeight = 0.002;
		railButton.setTotalHeight(baseHeight + innerHeight + flangeHeight);
		railButton.setScrewHeight(screwHeight);
		return railButton;
	}
}

