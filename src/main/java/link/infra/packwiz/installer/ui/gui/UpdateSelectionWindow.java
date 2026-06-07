package link.infra.packwiz.installer.ui.gui;

import com.formdev.flatlaf.FlatClientProperties;
import link.infra.packwiz.installer.project.CurseForgeProjectService;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class UpdateSelectionWindow extends JDialog {
    private final UpdateTableModel model;
    private boolean confirmed = false;

    public UpdateSelectionWindow(Window owner, List<CurseForgeProjectService.UpdateResult> results) {
        super(owner, "批量更新", ModalityType.APPLICATION_MODAL);
        this.model = new UpdateTableModel(results);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        buildUI();
        setMinimumSize(new Dimension(980, 420));
        setSize(1180, 580);
        setLocationRelativeTo(owner);
    }

    public List<CurseForgeProjectService.UpdateResult> selectedUpdates() {
        return confirmed ? model.selectedUpdates() : List.of();
    }

    private void buildUI() {
        var root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JLabel title = new JLabel("可更新 " + model.updateCount() + " 个");
        title.putClientProperty(FlatClientProperties.STYLE_CLASS, "h2");
        root.add(title, BorderLayout.NORTH);

        JTable table = new JTable(model) {
            @Override
            public String getToolTipText(java.awt.event.MouseEvent event) {
                int viewRow = rowAtPoint(event.getPoint());
                if (viewRow < 0) return null;
                return model.tooltip(convertRowIndexToModel(viewRow));
            }
        };
        table.setAutoCreateRowSorter(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
        table.setRowHeight(28);
        table.setFillsViewportHeight(true);
        table.putClientProperty(FlatClientProperties.STYLE, "showHorizontalLines: true; showVerticalLines: false");
        table.getColumnModel().getColumn(0).setMinWidth(44);
        table.getColumnModel().getColumn(0).setPreferredWidth(48);
        table.getColumnModel().getColumn(0).setMaxWidth(56);
        table.getColumnModel().getColumn(1).setPreferredWidth(240);
        table.getColumnModel().getColumn(2).setPreferredWidth(430);
        table.getColumnModel().getColumn(3).setPreferredWidth(430);
        table.setDefaultRenderer(String.class, new CompactCellRenderer());

        JScrollPane scroll = new JScrollPane(table);
        scroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        root.add(scroll, BorderLayout.CENTER);

        JButton all = new JButton("全选可更新");
        all.addActionListener(e -> model.selectAllAvailable());
        JButton none = new JButton("取消全选");
        none.addActionListener(e -> model.clearSelection());
        JButton apply = new JButton("应用所选");
        apply.addActionListener(e -> {
            if (model.selectedUpdates().isEmpty()) {
                JOptionPane.showMessageDialog(this, "没有选择可更新条目。", "批量更新", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            confirmed = true;
            dispose();
        });
        JButton close = new JButton("关闭");
        close.addActionListener(e -> dispose());

        var buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.add(all);
        buttons.add(none);
        buttons.add(close);
        buttons.add(apply);
        root.add(buttons, BorderLayout.SOUTH);
        setContentPane(root);
    }

    private static class UpdateTableModel extends AbstractTableModel {
        private final String[] columns = {"", "Mod", "当前", "即将更新"};
        private final List<Row> rows;

        UpdateTableModel(List<CurseForgeProjectService.UpdateResult> results) {
            this.rows = new ArrayList<>();
            for (var result : results) {
                if (!result.updateAvailable()) continue;
                rows.add(new Row(result.updateAvailable(), result));
            }
        }

        int updateCount() {
            int count = 0;
            for (var row : rows) if (row.result.updateAvailable()) count++;
            return count;
        }

        List<CurseForgeProjectService.UpdateResult> selectedUpdates() {
            var selected = new ArrayList<CurseForgeProjectService.UpdateResult>();
            for (var row : rows) {
                if (row.selected && row.result.updateAvailable()) selected.add(row.result);
            }
            return selected;
        }

        void selectAllAvailable() {
            for (var row : rows) row.selected = row.result.updateAvailable();
            fireTableDataChanged();
        }

        void clearSelection() {
            for (var row : rows) row.selected = false;
            fireTableDataChanged();
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }
        @Override public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == 0 ? Boolean.class : String.class;
        }
        @Override public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex == 0 && rows.get(rowIndex).result.updateAvailable();
        }
        @Override public Object getValueAt(int rowIndex, int columnIndex) {
            Row row = rows.get(rowIndex);
            var result = row.result;
            return switch (columnIndex) {
                case 0 -> row.selected;
                case 1 -> result.name();
                case 2 -> result.oldFilename();
                case 3 -> result.updateAvailable() ? result.newFilename() : "";
                default -> "";
            };
        }

        String tooltip(int rowIndex) {
            if (rowIndex < 0 || rowIndex >= rows.size()) return null;
            var result = rows.get(rowIndex).result;
            if (result.updateAvailable()) {
                return result.oldFilename() + " -> " + result.newFilename();
            }
            return result.message();
        }

        @Override public void setValueAt(Object value, int rowIndex, int columnIndex) {
            if (columnIndex != 0) return;
            Row row = rows.get(rowIndex);
            row.selected = row.result.updateAvailable() && Boolean.TRUE.equals(value);
            fireTableCellUpdated(rowIndex, columnIndex);
        }
    }

    private static class CompactCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus,
                                                       int row, int column) {
            JLabel label = (JLabel) super.getTableCellRendererComponent(table, value, selected, focus, row, column);
            label.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
            label.setToolTipText(value != null ? value.toString() : null);
            label.setHorizontalAlignment(SwingConstants.LEFT);
            return label;
        }
    }

    private static class Row {
        boolean selected;
        final CurseForgeProjectService.UpdateResult result;

        Row(boolean selected, CurseForgeProjectService.UpdateResult result) {
            this.selected = selected;
            this.result = result;
        }
    }
}
