package net.sf.openrocket.gui.widgets;

import javax.swing.AbstractButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTable;
import javax.swing.MenuElement;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;
import java.awt.Component;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EventObject;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * A popup menu for a JTableHeader that allows the user to show/hide columns.
 */
public class TableHeaderPopupMenu extends JPopupMenu {
    private List<TableHeaderChangeListener> listeners = new ArrayList<>();

    public TableHeaderPopupMenu(JTable table) {
        super();
        TableColumnModel columnModel = table.getColumnModel();
        List<TableColumn> list = Collections.list(columnModel.getColumns());
        list.forEach(tableColumn -> {
            String name = Objects.toString(tableColumn.getHeaderValue());
            JCheckBoxMenuItem item = new JCheckBoxMenuItem(name, true);
            item.addItemListener(e -> {
                if (((AbstractButton) e.getItemSelectable()).isSelected()) {
                    // Get the correct index to insert the column at
                    int indexToAddAt = getIndexToAddAt(this, tableColumn, columnModel);
                    columnModel.addColumn(tableColumn);
                    columnModel.moveColumn(columnModel.getColumnCount() - 1, indexToAddAt);
                } else {
                    columnModel.removeColumn(tableColumn);
                }
                updateMenuItems(columnModel);
            });
            add(item);
        });
    }

    public void addTableHeaderChangeListener(TableHeaderChangeListener listener) {
        listeners.add(listener);
    }

    public void removeTableHeaderChangeListener(TableHeaderChangeListener listener) {
        listeners.remove(listener);
    }

    @Override
    public void show(Component c, int x, int y) {
        if (!(c instanceof JTableHeader header)) {
            return;
        }

        JTable table = header.getTable();
        header.setDraggedColumn(null);
        header.repaint();
        table.repaint();
        updateMenuItems(header.getColumnModel());
        super.show(c, x, y);
    }

    private void updateMenuItems(TableColumnModel columnModel) {
        boolean isOnlyOneMenu = columnModel.getColumnCount() == 1;
        if (isOnlyOneMenu) {
            stream(this).map(MenuElement::getComponent).forEach(mi ->
                    mi.setEnabled(!(mi instanceof AbstractButton)
                            || !((AbstractButton) mi).isSelected()));
        } else {
            stream(this).forEach(me -> me.getComponent().setEnabled(true));
        }
        listeners.forEach(listener -> listener.tableHeaderChanged(new EventObject(this)));
    }

    private int getIndexToAddAt(JPopupMenu menu, TableColumn column, TableColumnModel columnModel) {
        int index = 0;
        for (int i = 0; i < menu.getComponentCount(); i++) {
            Component component = menu.getComponent(i);
            if (component instanceof JCheckBoxMenuItem menuItem) {
                TableColumn currentColumn = columnModel.getColumn(index);
                if (Objects.equals(menuItem.getText(), column.getHeaderValue())) {
                    return index;
                }
                if (Objects.equals(menuItem.getText(), currentColumn.getHeaderValue())) {
                    index++;
                }
            }
        }
        return index;
    }

    private static Stream<MenuElement> stream(MenuElement me) {
        return Stream.of(me.getSubElements())
                .flatMap(m -> Stream.concat(Stream.of(m), stream(m)));
    }

    public interface TableHeaderChangeListener {
        void tableHeaderChanged(EventObject event);
    }
}

