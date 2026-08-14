package info.openrocket.swing.gui.choosers;

import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.core.file.step.STEPExportOptions;
import info.openrocket.core.file.wavefrontobj.DefaultCoordTransform;
import info.openrocket.core.file.wavefrontobj.ObjUtils;
import info.openrocket.core.l10n.Translator;
import info.openrocket.core.preferences.ApplicationPreferences;
import info.openrocket.core.rocketcomponent.ComponentAssembly;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.startup.Application;
import net.miginfocom.swing.MigLayout;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import java.awt.event.ItemEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Options panel used by the component STEP export dialog.
 */
public class STEPOptionChooser extends JPanel implements OptionChooser {
	private static final Translator trans = Application.getTranslator();

	private final JLabel componentsLabel;
	private final JCheckBox exportChildren;
	private final JCheckBox exportAllInstances;
	private final JCheckBox exportMotors;
	private final JCheckBox exportAsSeparateFiles;
	private final JCheckBox removeOffset;
	private final JComboBox<ObjUtils.LevelOfDetail> levelOfDetail;
	private final List<RocketComponent> selectedComponents;
	private final Rocket rocket;

	/**
	 * Creates a STEP option panel for the current component selection.
	 *
	 * @param initialOptions options loaded from application preferences
	 * @param selectedComponents selected rocket components
	 * @param rocket containing rocket
	 */
	public STEPOptionChooser(STEPExportOptions initialOptions, List<RocketComponent> selectedComponents, Rocket rocket) {
		super(new MigLayout("hidemode 3"));
		this.selectedComponents = selectedComponents == null ? List.of() : List.copyOf(selectedComponents);
		this.rocket = rocket;

		componentsLabel = new JLabel();
		updateComponentsLabel(this.selectedComponents);
		add(componentsLabel, "spanx, growx, wrap unrel");

		JLabel formatInformation = new JLabel(trans.get("STEPOptionChooser.lbl.formatInformation"));
		add(formatInformation, "spanx, growx, wrap para");
		add(new JSeparator(JSeparator.HORIZONTAL), "spanx, growx, wrap para");

		exportChildren = new JCheckBox(trans.get("STEPOptionChooser.checkbox.exportChildren"));
		exportChildren.setToolTipText(trans.get("STEPOptionChooser.checkbox.exportChildren.ttip"));
		exportChildren.addItemListener(event -> {
			if (event.getStateChange() == ItemEvent.SELECTED) {
				Set<RocketComponent> components = new HashSet<>(this.selectedComponents);
				for (RocketComponent component : this.selectedComponents) {
					components.addAll(component.getAllChildren());
				}
				updateComponentsLabel(new ArrayList<>(components));
			} else {
				updateComponentsLabel(this.selectedComponents);
			}
		});
		add(exportChildren, "spanx, wrap");

		exportAllInstances = new JCheckBox(trans.get("STEPOptionChooser.checkbox.exportAllInstances"));
		exportAllInstances.setToolTipText(trans.get("STEPOptionChooser.checkbox.exportAllInstances.ttip"));
		add(exportAllInstances, "spanx, wrap");

		exportMotors = new JCheckBox(trans.get("STEPOptionChooser.checkbox.exportMotors"));
		exportMotors.setToolTipText(trans.get("STEPOptionChooser.checkbox.exportMotors.ttip"));
		add(exportMotors, "spanx, wrap");

		removeOffset = new JCheckBox(trans.get("STEPOptionChooser.checkbox.removeOffset"));
		removeOffset.setToolTipText(trans.get("STEPOptionChooser.checkbox.removeOffset.ttip"));
		add(removeOffset, "spanx, wrap");

		exportAsSeparateFiles = new JCheckBox(trans.get("STEPOptionChooser.checkbox.exportAsSeparateFiles"));
		exportAsSeparateFiles.setToolTipText(trans.get("STEPOptionChooser.checkbox.exportAsSeparateFiles.ttip"));
		add(exportAsSeparateFiles, "spanx, wrap para");

		JLabel levelOfDetailLabel = new JLabel(trans.get("STEPOptionChooser.lbl.levelOfDetail"));
		levelOfDetailLabel.setToolTipText(trans.get("STEPOptionChooser.lbl.levelOfDetail.ttip"));
		add(levelOfDetailLabel, "split 2");
		levelOfDetail = new JComboBox<>(ObjUtils.LevelOfDetail.values());
		levelOfDetail.setToolTipText(trans.get("STEPOptionChooser.lbl.levelOfDetail.ttip"));
		add(levelOfDetail, "growx, wrap");

		loadOptions(initialOptions);
	}

	private void loadOptions(STEPExportOptions options) {
		boolean onlyAssemblies = containsOnlyAssemblies(selectedComponents);
		boolean hasChildren = hasChildren(selectedComponents);
		if (onlyAssemblies || !hasChildren) {
			exportChildren.setSelected(true);
			exportChildren.setEnabled(false);
		} else {
			exportChildren.setSelected(options.isExportChildren());
			exportChildren.setEnabled(true);
		}
		exportAllInstances.setSelected(options.isExportAllInstances());
		exportMotors.setSelected(options.isExportMotors());
		exportAsSeparateFiles.setSelected(options.isExportAsSeparateFiles());
		removeOffset.setSelected(options.isRemoveOffset());
		levelOfDetail.setSelectedItem(options.getLevelOfDetail());
	}

	private void storeOptions(STEPExportOptions options, boolean alwaysStoreExportChildren) {
		if (alwaysStoreExportChildren || exportChildren.isEnabled()) {
			options.setExportChildren(exportChildren.isSelected());
		}
		options.setExportAllInstances(exportAllInstances.isSelected());
		options.setExportMotors(exportMotors.isSelected());
		options.setExportAsSeparateFiles(exportAsSeparateFiles.isSelected());
		options.setRemoveOffset(removeOffset.isSelected());
		options.setLevelOfDetail((ObjUtils.LevelOfDetail) levelOfDetail.getSelectedItem());
		options.setTransformer(new DefaultCoordTransform(rocket.getLength()));
	}

	@Override
	public void storeOptions(OpenRocketDocument document, ApplicationPreferences preferences) {
		storeOptions(document.getDefaultSTEPOptions(), true);
		STEPExportOptions preferenceOptions = new STEPExportOptions(document.getRocket());
		storeOptions(preferenceOptions, false);
		preferences.saveSTEPExportOptions(preferenceOptions);
	}

	private void updateComponentsLabel(List<RocketComponent> components) {
		String componentName;
		if (components.size() == 1) {
			componentName = "<b>" + components.get(0).getName() + "</b>";
		} else {
			componentName = trans.get("STEPOptionChooser.lbl.multipleComponents");
		}
		componentsLabel.setText(String.format(trans.get("STEPOptionChooser.lbl.component"), componentName));
		componentsLabel.setToolTipText(createComponentsTooltip(components));
	}

	private static String createComponentsTooltip(List<RocketComponent> components) {
		if (components.size() <= 1) {
			return "";
		}
		StringBuilder tooltip = new StringBuilder("<html>");
		for (int i = 0; i < components.size(); i++) {
			if (i > 0) {
				tooltip.append(", ");
			}
			if (i > 0 && i % 4 == 0) {
				tooltip.append("<br>");
			}
			tooltip.append(components.get(i).getName());
		}
		return tooltip.append("</html>").toString();
	}

	private static boolean containsOnlyAssemblies(List<RocketComponent> components) {
		return !components.isEmpty() && components.stream().allMatch(ComponentAssembly.class::isInstance);
	}

	private static boolean hasChildren(List<RocketComponent> components) {
		return components.stream().anyMatch(component -> !component.getChildren().isEmpty());
	}
}
