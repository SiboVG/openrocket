package info.openrocket.swing.gui.main;

import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.core.document.OpenRocketDocumentFactory;
import info.openrocket.core.document.StorageOptions.FileType;
import info.openrocket.swing.gui.util.FileHelper;
import info.openrocket.swing.util.BaseTestCase;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Tests export-specific save chooser configuration.
 */
class DesignFileSaveAsFileChooserTest extends BaseTestCase {
	@Test
	void stepChooserContainsOnlyFileSelectionControls() throws Exception {
		OpenRocketDocument document = OpenRocketDocumentFactory.createNewRocket();

		SwingUtilities.invokeAndWait(() -> {
			DesignFileSaveAsFileChooser chooser = DesignFileSaveAsFileChooser.build(document, FileType.STEP);
			assertNull(chooser.getAccessory(), "STEP options belong in the preceding options dialog");
			assertSame(FileHelper.STEP_FILTER, chooser.getFileFilter());
		});
	}
}
