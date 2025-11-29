package info.openrocket.core.file.dxf.export;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.regex.Pattern;

class DXFBuilderTest {
	private static final Pattern HEX_HANDLE = Pattern.compile("^[0-9A-F]+$");

	@Test
	void writeToFileIncludesVersionAndUnits() throws Exception {
		DXFBuilder builder = new DXFBuilder();
		builder.addLine(0, 0, 0.01, 0, Color.BLACK, "PROFILES");

		File dxfFile = File.createTempFile("dxfbuilder", ".dxf");
		builder.writeToFile(dxfFile);

		String contents = Files.readString(dxfFile.toPath());
		Assertions.assertTrue(contents.contains("$ACADVER"), contents);
		Assertions.assertTrue(contents.contains("AC1015"), contents);
		Assertions.assertTrue(contents.contains("$INSUNITS"), contents);
		Assertions.assertTrue(contents.contains("$MEASUREMENT"), contents);
	}

	@Test
	void entitiesUseSingleLayerCodeAndHexHandles() throws Exception {
		DXFBuilder builder = new DXFBuilder();
		builder.addLine(0, 0, 0.01, 0, Color.BLACK, "PROFILES");
		builder.addCircle(0, 0, 0.005, Color.BLACK, "PROFILES");
		builder.addText(0.005, 0.002, "Label", 3.0, Color.BLACK, "LABELS");

		File dxfFile = File.createTempFile("dxfentities", ".dxf");
		builder.writeToFile(dxfFile);

		List<String> lines = Files.readAllLines(dxfFile.toPath());
		List<List<String>> entities = DxfTestUtil.extractEntities(lines);
		Assertions.assertFalse(entities.isEmpty(), "Expected at least one entity");

		for (List<String> entityLines : entities) {
			int layerCodes = 0;
			for (int i = 0; i < entityLines.size(); i += 2) {
				if ("8".equals(entityLines.get(i))) {
					layerCodes++;
				}
			}
			Assertions.assertEquals(1, layerCodes, "Entity should contain exactly one layer code: " + entityLines);

			// group code 5 is the handle
			for (int i = 0; i + 1 < entityLines.size(); i += 2) {
				if ("5".equals(entityLines.get(i))) {
					String handle = entityLines.get(i + 1);
					Assertions.assertTrue(HEX_HANDLE.matcher(handle).matches(), "Handle should be hex: " + handle);
					break;
				}
			}
		}
	}

	@Test
	void textDefaultsToCenteredAnchor() throws Exception {
		DXFBuilder builder = new DXFBuilder();
		builder.addText(0.0, 0.0, "Centered", 3.0, Color.BLACK, "LABELS");

		File dxfFile = File.createTempFile("dxftext", ".dxf");
		builder.writeToFile(dxfFile);

		List<String> lines = Files.readAllLines(dxfFile.toPath());
		List<List<String>> entities = DxfTestUtil.extractEntities(lines);

		boolean foundCentered = false;
		for (List<String> entityLines : entities) {
			if (entityLines.size() > 1 && "TEXT".equals(entityLines.get(1))) {
				for (int i = 0; i + 1 < entityLines.size(); i += 2) {
					if ("72".equals(entityLines.get(i)) && "1".equals(entityLines.get(i + 1))) {
						foundCentered = true;
						break;
					}
				}
			}
		}
		Assertions.assertTrue(foundCentered, "Expected a TEXT entity with centered alignment (72=1)");
	}

}
