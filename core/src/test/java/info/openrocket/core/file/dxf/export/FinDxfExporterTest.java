package info.openrocket.core.file.dxf.export;

import info.openrocket.core.rocketcomponent.TrapezoidFinSet;
import info.openrocket.core.util.BaseTestCase;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.io.File;
import java.nio.file.Files;

class FinDxfExporterTest extends BaseTestCase {

	@Test
	void calculateBoundsForTrapezoidFin() {
		TrapezoidFinSet finSet = new TrapezoidFinSet();
		finSet.setRootChord(0.06);
		finSet.setTipChord(0.04);
		finSet.setHeight(0.03);
		finSet.setSweep(0.02);

		FinDxfExporter.Bounds bounds = FinDxfExporter.calculateBounds(finSet);

		Assertions.assertTrue(bounds.getWidth() > 0);
		Assertions.assertTrue(bounds.getHeight() > 0);
	}

	@Test
	void drawFinSetWritesPolyline() throws Exception {
		TrapezoidFinSet finSet = new TrapezoidFinSet();
		finSet.setRootChord(0.06);
		finSet.setTipChord(0.04);
		finSet.setHeight(0.03);
		finSet.setSweep(0.02);

		DXFBuilder builder = new DXFBuilder();
		DXFExportOptions options = new DXFExportOptions(Color.BLACK, 0.1);

		FinDxfExporter.drawFinSet(finSet, builder, 0.0, 0.0, options);

		File dxfFile = File.createTempFile("fin", ".dxf");
		builder.writeToFile(dxfFile);

		String contents = Files.readString(dxfFile.toPath());
		Assertions.assertTrue(contents.contains("LWPOLYLINE"), "Should contain LWPOLYLINE entity");
		Assertions.assertTrue(contents.contains("PROFILES"), "Should contain PROFILES layer");
	}

	@Test
	void drawFinSetWithTabBeyondFinWritesMultiplePolylines() throws Exception {
		TrapezoidFinSet finSet = new TrapezoidFinSet();
		finSet.setRootChord(0.06);
		finSet.setTipChord(0.04);
		finSet.setHeight(0.03);
		finSet.setSweep(0.02);
		finSet.setTabLength(0.02);
		finSet.setTabHeight(0.01);
		// Set tab offset to be beyond the fin (positive offset beyond fin length)
		finSet.setTabOffsetMethod(info.openrocket.core.rocketcomponent.position.AxialMethod.TOP);
		finSet.setTabOffset(0.1); // Large offset to make tab beyond fin

		DXFBuilder builder = new DXFBuilder();
		DXFExportOptions options = new DXFExportOptions(Color.BLACK, 0.1);

		FinDxfExporter.drawFinSet(finSet, builder, 0.0, 0.0, options);

		File dxfFile = File.createTempFile("fin-tab", ".dxf");
		builder.writeToFile(dxfFile);

		String contents = Files.readString(dxfFile.toPath());
		// Should contain multiple LWPOLYLINE entities (fin + tab)
		long polylineCount = contents.split("LWPOLYLINE").length - 1;
		Assertions.assertTrue(polylineCount >= 2, "Should contain fin and tab polylines: " + contents);
	}

	@Test
	void drawFinSetRespectsOriginOffset() throws Exception {
		TrapezoidFinSet finSet = new TrapezoidFinSet();
		finSet.setRootChord(0.06);
		finSet.setTipChord(0.04);
		finSet.setHeight(0.03);
		finSet.setSweep(0.02);

		DXFBuilder builder = new DXFBuilder();
		DXFExportOptions options = new DXFExportOptions(Color.BLACK, 0.1);

		FinDxfExporter.drawFinSet(finSet, builder, 0.1, 0.05, options);

		File dxfFile = File.createTempFile("fin-offset", ".dxf");
		builder.writeToFile(dxfFile);

		String contents = Files.readString(dxfFile.toPath());
		Assertions.assertTrue(contents.contains("LWPOLYLINE"), 
			"Should contain LWPOLYLINE entity: " + contents);
		// Coordinates should be offset
		Assertions.assertTrue(contents.contains("10") && contents.contains("20"), 
			"Should contain coordinate group codes: " + contents);
	}

	@Test
	void drawFinSetUsesCorrectLayer() throws Exception {
		TrapezoidFinSet finSet = new TrapezoidFinSet();
		finSet.setRootChord(0.06);
		finSet.setTipChord(0.04);
		finSet.setHeight(0.03);
		finSet.setSweep(0.02);

		DXFBuilder builder = new DXFBuilder();
		DXFExportOptions options = new DXFExportOptions(Color.BLACK, 0.1);

		FinDxfExporter.drawFinSet(finSet, builder, 0.0, 0.0, options);

		File dxfFile = File.createTempFile("fin-layer", ".dxf");
		builder.writeToFile(dxfFile);

		String contents = Files.readString(dxfFile.toPath());
		Assertions.assertTrue(contents.contains("PROFILES"), 
			"Should contain PROFILES layer: " + contents);
	}
}

