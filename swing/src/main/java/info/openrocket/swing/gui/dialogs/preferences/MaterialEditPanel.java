package info.openrocket.swing.gui.dialogs.preferences;

import java.awt.Color;
import java.awt.Component;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Iterator;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.table.DefaultTableCellRenderer;

import info.openrocket.core.document.OpenRocketDocument;
import net.miginfocom.swing.MigLayout;

import info.openrocket.core.database.Databases;
import info.openrocket.core.l10n.Translator;
import info.openrocket.core.material.Material;
import info.openrocket.core.startup.Application;
import info.openrocket.core.unit.UnitGroup;
import info.openrocket.core.unit.Value;

import info.openrocket.swing.gui.adaptors.Column;
import info.openrocket.swing.gui.adaptors.ColumnTable;
import info.openrocket.swing.gui.adaptors.ColumnTableModel;
import info.openrocket.swing.gui.components.StyledLabel;
import info.openrocket.swing.gui.components.StyledLabel.Style;
import info.openrocket.swing.gui.dialogs.CustomMaterialDialog;
import info.openrocket.swing.gui.widgets.SelectColorButton;

@SuppressWarnings("serial")
public class MaterialEditPanel extends JPanel {
	
	private final JTable table;
	
	private final JButton addButton;
	private final JButton editButton;
	private final JButton deleteButton;
	private final JButton revertButton;
	private static final Translator trans = Application.getTranslator();
	private final OpenRocketDocument document;
	
	
	public MaterialEditPanel(OpenRocketDocument document) {
		super(new MigLayout("fill"));

		this.document = document;
		

		// TODO: LOW: Create sorter that keeps material types always in order
		final ColumnTableModel model = new ColumnTableModel(
				//// Material
				new Column(trans.get("matedtpan.col.Material")) {
					@Override
					public Object getValueAt(int row) {
						return getMaterial(row).getName();
					}
				},
				//// Type
				new Column(trans.get("matedtpan.col.Type")) {
					@Override
					public Object getValueAt(int row) {
						return getMaterial(row).getType().toString();
					}
					
					@Override
					public int getDefaultWidth() {
						return 15;
					}
				},
				//// Density
				new Column(trans.get("matedtpan.col.Density")) {
					@Override
					public Object getValueAt(int row) {
						Material m = getMaterial(row);
						double d = m.getDensity();
						return switch (m.getType()) {
							case LINE -> UnitGroup.UNITS_DENSITY_LINE.toValue(d);
							case SURFACE -> UnitGroup.UNITS_DENSITY_SURFACE.toValue(d);
							case BULK -> UnitGroup.UNITS_DENSITY_BULK.toValue(d);
							default -> throw new IllegalStateException("Material type " + m.getType());
						};
					}
					
					@Override
					public int getDefaultWidth() {
						return 15;
					}
					
					@Override
					public Class<?> getColumnClass() {
						return Value.class;
					}
				},
				//// Group
				new Column(trans.get("matedtpan.col.Group")) {
					@Override
					public Object getValueAt(int row) {
						return getMaterial(row).getGroup().getName();
					}

					@Override
					public int getDefaultWidth() {
						return 20;
					}
				},
				//// Scope
				new Column(trans.get("matedtpan.col.Scope")) {
					@Override
					public Object getValueAt(int row) {
						boolean documentMaterial = getMaterial(row).isDocumentMaterial();
						return documentMaterial ? trans.get("matedtpan.col.Scope.Document") : trans.get("matedtpan.col.Scope.Application");
					}

					@Override
					public int getDefaultWidth() {
						return 15;
					}
				}
				) {
					@Override
					public int getRowCount() {
						return Databases.BULK_MATERIAL.size() + Databases.SURFACE_MATERIAL.size() +
								Databases.LINE_MATERIAL.size() + document.getDocumentPreferences().getTotalMaterialCount();
					}
				};
		
		table = new ColumnTable(model);
		model.setColumnWidths(table.getColumnModel());
		table.setAutoCreateRowSorter(true);
		table.setDefaultRenderer(Object.class, new MaterialCellRenderer());
		this.add(new JScrollPane(table), "spanx, grow 100, wrap");
		

		//// New button
		addButton = new SelectColorButton(trans.get("matedtpan.but.new"));
		//// Add a new material
		addButton.setToolTipText(trans.get("matedtpan.col.but.ttip.New"));
		addButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				CustomMaterialDialog dialog = new CustomMaterialDialog(
							SwingUtilities.getWindowAncestor(MaterialEditPanel.this),
							//// Add a custom material
						null, true, true, trans.get("matedtpan.title.Addcustmaterial"));
				dialog.setVisible(true);
				if (!dialog.getOkClicked()) {
					return;
				}
				Material mat = dialog.getMaterial();
				if (dialog.isAddSelected()) {
					Databases.getDatabase(mat.getType()).add(mat);
				} else {
					mat.setDocumentMaterial(true);
					document.getDocumentPreferences().addMaterial(mat);
				}
				model.fireTableDataChanged();
				setButtonStates();
			}
		});
		this.add(addButton, "gap rel rel para para, split 3, growx 1");
		
		//// Edit button
		editButton = new SelectColorButton(trans.get("matedtpan.but.edit"));
		//// Edit an existing material
		editButton.setToolTipText(trans.get("matedtpan.but.ttip.edit"));
		editButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				int sel = table.getSelectedRow();
				if (sel < 0)
					return;
				sel = table.convertRowIndexToModel(sel);
				Material m = getMaterial(sel);
				boolean isDocumentMaterialPrior = m.isDocumentMaterial();
				
				CustomMaterialDialog dialog;
				if (m.isUserDefined()) {
					dialog = new CustomMaterialDialog(
							SwingUtilities.getWindowAncestor(MaterialEditPanel.this),
							//// Edit material
							m, true, trans.get("matedtpan.title.Editmaterial"));
				} else {
					dialog = new CustomMaterialDialog(
							SwingUtilities.getWindowAncestor(MaterialEditPanel.this),
							//// Add a custom material
							m, true, trans.get("matedtpan.title.Addcustmaterial"),
							//// The built-in materials cannot be modified.
							trans.get("matedtpan.title2.Editmaterial"));
				}
				
				dialog.setVisible(true);
				
				if (!dialog.getOkClicked()) {
					return;
				}
				if (m.isUserDefined()) {
					if (isDocumentMaterialPrior) {
						document.getDocumentPreferences().removeMaterial(m);
					} else {
						Databases.getDatabase(m.getType()).remove(m);
					}
				}
				Material mat = dialog.getMaterial();
				if (dialog.isAddSelected()) {
					Databases.getDatabase(mat.getType()).add(mat);
				} else {
					mat.setDocumentMaterial(true);
					document.getDocumentPreferences().addMaterial(mat);
				}
				model.fireTableDataChanged();
				setButtonStates();
			}
		});
		this.add(editButton, "gap rel rel para para, growx 1");
		
		//// Delete button
		deleteButton = new SelectColorButton(trans.get("matedtpan.but.delete"));
		//// Delete a user-defined material
		deleteButton.setToolTipText(trans.get("matedtpan.but.ttip.delete"));
		deleteButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				int sel = table.getSelectedRow();
				if (sel < 0)
					return;
				sel = table.convertRowIndexToModel(sel);
				Material m = getMaterial(sel);
				if (!m.isUserDefined())
					return;
				if (m.isDocumentMaterial()) {
					document.getDocumentPreferences().removeMaterial(m);
				} else {
					Databases.getDatabase(m.getType()).remove(m);
				}
				model.fireTableDataChanged();
				setButtonStates();
			}
		});
		this.add(deleteButton, "gap rel rel para para, growx 1, wrap unrel");
		
		//// Revert all button
		revertButton = new SelectColorButton(trans.get("matedtpan.but.revertall"));
		//// Delete all user-defined materials
		revertButton.setToolTipText(trans.get("matedtpan.but.ttip.revertall"));
		revertButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				int sel = JOptionPane.showConfirmDialog(MaterialEditPanel.this,
						//// Delete all user-defined materials?
						trans.get("matedtpan.title.Deletealluser-defined"),
						//// Revert all?
						trans.get("matedtpan.title.Revertall"),
						JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
				if (sel == JOptionPane.YES_OPTION) {
					Iterator<Material> iterator;
					
					iterator = Databases.LINE_MATERIAL.iterator();
					while (iterator.hasNext()) {
						if (iterator.next().isUserDefined())
							iterator.remove();
					}
					
					iterator = Databases.SURFACE_MATERIAL.iterator();
					while (iterator.hasNext()) {
						if (iterator.next().isUserDefined())
							iterator.remove();
					}
					
					iterator = Databases.BULK_MATERIAL.iterator();
					while (iterator.hasNext()) {
						if (iterator.next().isUserDefined())
							iterator.remove();
					}

					iterator = document.getDocumentPreferences().getBulkMaterials().iterator();
					while (iterator.hasNext()) {
						if (iterator.next().isUserDefined())		// Not really necessary, but who knows what we end up doing in the future. Better safe than sorry.
							iterator.remove();
					}

					iterator = document.getDocumentPreferences().getSurfaceMaterials().iterator();
					while (iterator.hasNext()) {
						if (iterator.next().isUserDefined())		// Not really necessary, but who knows what we end up doing in the future. Better safe than sorry.
							iterator.remove();
					}

					iterator = document.getDocumentPreferences().getLineMaterials().iterator();
					while (iterator.hasNext()) {
						if (iterator.next().isUserDefined())		// Not really necessary, but who knows what we end up doing in the future. Better safe than sorry.
							iterator.remove();
					}

					model.fireTableDataChanged();
					setButtonStates();
				}
			}
		});
		this.add(revertButton, "gap rel rel para para, w 80lp, right, wrap unrel");
		
		setButtonStates();
		table.getSelectionModel().addListSelectionListener(new ListSelectionListener() {
			@Override
			public void valueChanged(ListSelectionEvent e) {
				setButtonStates();
			}
		});
		table.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				if (e.getClickCount() == 2) {
					editButton.doClick();
				}
			}
		});
		
		//// <html><i>Editing materials will not affect existing
		//// rocket designs.</i>
		this.add(new StyledLabel(trans.get("matedtpan.lbl.edtmaterials"), -2, Style.ITALIC), "span");
		

	}
	
	
	private void setButtonStates() {
		int sel = table.getSelectedRow();
		
		// Add button always enabled
		addButton.setEnabled(true);
		
		// Edit button enabled if a material is selected
		editButton.setEnabled(sel >= 0);
		
		// Delete button enabled if a user-defined material is selected
		if (sel >= 0) {
			int modelRow = table.convertRowIndexToModel(sel);
			deleteButton.setEnabled(getMaterial(modelRow).isUserDefined());
		} else {
			deleteButton.setEnabled(false);
		}
		
		// Revert button enabled if any user-defined material exists
		boolean found = false;
		
		for (Material m : Databases.BULK_MATERIAL) {
			if (m.isUserDefined()) {
				found = true;
				break;
			}
		}
		if (!found) {
			for (Material m : Databases.SURFACE_MATERIAL) {
				if (m.isUserDefined()) {
					found = true;
					break;
				}
			}
		}
		if (!found) {
			for (Material m : Databases.LINE_MATERIAL) {
				if (m.isUserDefined()) {
					found = true;
					break;
				}
			}
		}
		if (!found) {
			for (Material m : document.getDocumentPreferences().getAllMaterials()) {
				if (m.isUserDefined()) {
					found = true;
					break;
				}
			}
		}
		revertButton.setEnabled(found);
		
	}
	
	private Material getMaterial(int origRow) {
		int row = origRow;
		int n;
		
		n = Databases.BULK_MATERIAL.size();
		if (row < n) {
			return Databases.BULK_MATERIAL.get(row);
		}
		row -= n;

		n = document.getDocumentPreferences().getBulkMaterials().size();
		if (row < n) {
			return document.getDocumentPreferences().getBulkMaterials().get(row);
		}
		row -= n;
		
		n = Databases.SURFACE_MATERIAL.size();
		if (row < n) {
			return Databases.SURFACE_MATERIAL.get(row);
		}
		row -= n;

		n = document.getDocumentPreferences().getSurfaceMaterials().size();
		if (row < n) {
			return document.getDocumentPreferences().getSurfaceMaterials().get(row);
		}
		row -= n;

		n = Databases.LINE_MATERIAL.size();
		if (row < n) {
			return Databases.LINE_MATERIAL.get(row);
		}
		row -= n;

		n = document.getDocumentPreferences().getLineMaterials().size();
		if (row < n) {
			return document.getDocumentPreferences().getLineMaterials().get(row);
		}

		throw new IndexOutOfBoundsException("row=" + origRow + " while material count" +
				" bulk:" + Databases.BULK_MATERIAL.size() +
				" bulk document:" + document.getDocumentPreferences().getBulkMaterials().size() +
				" surface:" + Databases.SURFACE_MATERIAL.size() +
				" surface document:" + document.getDocumentPreferences().getSurfaceMaterials().size() +
				" line:" + Databases.LINE_MATERIAL.size() +
				" line document:" + document.getDocumentPreferences().getLineMaterials().size());
	}
	
	
	private class MaterialCellRenderer extends DefaultTableCellRenderer {
		
		/* (non-Javadoc)
		 * @see javax.swing.table.DefaultTableCellRenderer#getTableCellRendererComponent(javax.swing.JTable, java.lang.Object, boolean, boolean, int, int)
		 */
		@Override
		public Component getTableCellRendererComponent(JTable myTable, Object value,
				boolean isSelected, boolean hasFocus, int row, int column) {
			Component c = super.getTableCellRendererComponent(myTable, value, isSelected,
					hasFocus, row, column);
			if (c instanceof JLabel) {
				JLabel label = (JLabel) c;
				Material m = getMaterial(row);
				
				if (isSelected) {
					if (m.isUserDefined())
						label.setForeground(myTable.getSelectionForeground());
					else
						label.setForeground(Color.GRAY);
				} else {
					if (m.isUserDefined())
						label.setForeground(myTable.getForeground());
					else
						label.setForeground(Color.GRAY);
				}
			}
			return c;
		}
		
	}
	
}
