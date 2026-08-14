package info.openrocket.swing.gui.export;

import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.core.file.step.STEPExportOptions;
import info.openrocket.core.l10n.Translator;
import info.openrocket.core.preferences.ApplicationPreferences;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.startup.Application;
import info.openrocket.swing.gui.choosers.STEPOptionChooser;
import info.openrocket.swing.gui.util.GUIUtil;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.util.List;

/**
 * Modal dialog for choosing STEP export settings before selecting a file.
 */
public final class STEPOptionsDialog extends JDialog {
	private static final Translator trans = Application.getTranslator();

	private final STEPOptionChooser optionChooser;
	private boolean confirmed;

	/**
	 * Creates a STEP options dialog.
	 *
	 * @param owner parent application frame
	 * @param initialOptions options loaded from application preferences
	 * @param selectedComponents components selected for export
	 * @param rocket containing rocket
	 */
	public STEPOptionsDialog(Frame owner, STEPExportOptions initialOptions,
			List<RocketComponent> selectedComponents, Rocket rocket) {
		super(owner, trans.get("STEPOptionsDialog.title"), true);
		optionChooser = new STEPOptionChooser(initialOptions, selectedComponents, rocket);
		initialize();
	}

	private void initialize() {
		JPanel content = new JPanel(new BorderLayout(0, 12));
		content.setBorder(new EmptyBorder(12, 12, 12, 12));
		content.add(optionChooser, BorderLayout.CENTER);

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
		JButton cancelButton = new JButton(trans.get("button.cancel"));
		cancelButton.addActionListener(event -> {
			confirmed = false;
			dispose();
		});
		JButton okButton = new JButton(trans.get("button.ok"));
		okButton.addActionListener(event -> {
			confirmed = true;
			dispose();
		});
		buttons.add(cancelButton);
		buttons.add(okButton);
		content.add(buttons, BorderLayout.SOUTH);

		setContentPane(content);
		setResizable(false);
		GUIUtil.setDisposableDialogOptions(this, okButton);
	}

	/**
	 * Displays the dialog and reports whether the user confirmed the settings.
	 */
	public boolean showDialog() {
		confirmed = false;
		setLocationRelativeTo(getOwner());
		setVisible(true);
		return confirmed;
	}

	/**
	 * Stores confirmed settings in the document and user preferences.
	 */
	public void storeOptions(OpenRocketDocument document, ApplicationPreferences preferences) {
		optionChooser.storeOptions(document, preferences);
	}
}
