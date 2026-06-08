package link.infra.packwiz.installer.ui.gui;

import com.formdev.flatlaf.FlatClientProperties;
import link.infra.packwiz.installer.project.CurseForgeProjectService;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class UpdateSelectionWindow extends JDialog {
    private static final int CHANGELOG_COLUMN = 0;
    private static final int SELECT_COLUMN = 1;
    private static final String COLLAPSED_MARK = "\u25b6";
    private static final String EXPANDED_MARK = "\u25bc";
    private static final String EMPTY_CHANGELOG = "<html><body><p>点击更新行左侧三角查看更新日志。</p></body></html>";

    private final CurseForgeProjectService service;
    private final UpdateTableModel model;
    private final Map<String, String> changelogCache = new HashMap<>();
    private final Set<String> loadingKeys = new HashSet<>();
    private JTable table;
    private JLabel changelogTitle;
    private JEditorPane changelogPane;
    private int expandedRow = -1;
    private boolean confirmed = false;

    public UpdateSelectionWindow(Window owner, List<CurseForgeProjectService.UpdateResult> results,
                                 CurseForgeProjectService service) {
        super(owner, "批量更新", ModalityType.APPLICATION_MODAL);
        this.service = service;
        this.model = new UpdateTableModel(results);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        buildUI();
        setMinimumSize(new Dimension(980, 520));
        setSize(1180, 720);
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

        table = new JTable(model) {
            @Override
            public String getToolTipText(MouseEvent event) {
                int viewRow = rowAtPoint(event.getPoint());
                if (viewRow < 0) return null;
                return model.tooltip(convertRowIndexToModel(viewRow));
            }
        };
        table.setAutoCreateRowSorter(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
        table.setRowHeight(28);
        table.setFillsViewportHeight(true);
        table.getTableHeader().setReorderingAllowed(false);
        table.putClientProperty(FlatClientProperties.STYLE, "showHorizontalLines: true; showVerticalLines: false");
        table.setDefaultRenderer(String.class, new CompactCellRenderer());
        table.getColumnModel().getColumn(CHANGELOG_COLUMN).setMinWidth(42);
        table.getColumnModel().getColumn(CHANGELOG_COLUMN).setPreferredWidth(46);
        table.getColumnModel().getColumn(CHANGELOG_COLUMN).setMaxWidth(54);
        table.getColumnModel().getColumn(SELECT_COLUMN).setMinWidth(44);
        table.getColumnModel().getColumn(SELECT_COLUMN).setPreferredWidth(48);
        table.getColumnModel().getColumn(SELECT_COLUMN).setMaxWidth(56);
        table.getColumnModel().getColumn(2).setPreferredWidth(220);
        table.getColumnModel().getColumn(3).setPreferredWidth(380);
        table.getColumnModel().getColumn(4).setPreferredWidth(380);
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                handleTableClick(event);
            }
        });

        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
        tableScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);

        JPanel changelogPanel = buildChangelogPanel();
        var split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableScroll, changelogPanel);
        split.setResizeWeight(0.60);
        split.setBorder(BorderFactory.createEmptyBorder());
        root.add(split, BorderLayout.CENTER);

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

    private JPanel buildChangelogPanel() {
        changelogTitle = new JLabel("更新日志");
        changelogTitle.putClientProperty(FlatClientProperties.STYLE_CLASS, "h3");

        changelogPane = new JEditorPane();
        changelogPane.setEditable(false);
        changelogPane.setContentType("text/html");
        changelogPane.setEditorKit(htmlKit());
        changelogPane.setText(EMPTY_CHANGELOG);
        changelogPane.setCaretPosition(0);

        var panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, borderColor()),
            BorderFactory.createEmptyBorder(10, 0, 0, 0)
        ));
        panel.add(changelogTitle, BorderLayout.NORTH);
        panel.add(new JScrollPane(changelogPane), BorderLayout.CENTER);
        return panel;
    }

    private Color borderColor() {
        Color color = UIManager.getColor("Component.borderColor");
        return color != null ? color : UIManager.getColor("Separator.foreground");
    }

    private HTMLEditorKit htmlKit() {
        var kit = new HTMLEditorKit();
        StyleSheet styles = kit.getStyleSheet();
        Font font = UIManager.getFont("Label.font");
        if (font != null) {
            styles.addRule("body { font-family: " + font.getFamily() + "; font-size: " + font.getSize() + "pt; padding: 8px; }");
        } else {
            styles.addRule("body { padding: 8px; }");
        }
        styles.addRule("p, li { margin-bottom: 6px; }");
        styles.addRule("code, pre { font-family: Monospaced; }");
        return kit;
    }

    private void handleTableClick(MouseEvent event) {
        int viewRow = table.rowAtPoint(event.getPoint());
        int viewColumn = table.columnAtPoint(event.getPoint());
        if (viewRow < 0 || viewColumn != CHANGELOG_COLUMN) return;
        int modelRow = table.convertRowIndexToModel(viewRow);
        toggleChangelog(modelRow);
    }

    private void toggleChangelog(int modelRow) {
        if (modelRow < 0 || modelRow >= model.getRowCount()) return;
        if (expandedRow == modelRow) {
            expandedRow = -1;
            model.setExpandedRow(expandedRow);
            changelogTitle.setText("更新日志");
            showHtml(EMPTY_CHANGELOG);
            model.fireTableRowsUpdated(modelRow, modelRow);
            return;
        }

        int oldExpanded = expandedRow;
        expandedRow = modelRow;
        model.setExpandedRow(expandedRow);
        if (oldExpanded >= 0) model.fireTableRowsUpdated(oldExpanded, oldExpanded);
        model.fireTableRowsUpdated(modelRow, modelRow);

        var result = model.resultAt(modelRow);
        changelogTitle.setText("更新日志 - " + result.name() + " / " + result.newFilename());
        String key = cacheKey(result);
        if (changelogCache.containsKey(key)) {
            showChangelog(changelogCache.get(key));
            return;
        }
        if (loadingKeys.contains(key)) {
            showLoading();
            return;
        }
        loadChangelog(modelRow, result, key);
    }

    private void loadChangelog(int modelRow, CurseForgeProjectService.UpdateResult result, String key) {
        loadingKeys.add(key);
        showLoading();
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                return service.loadUpdateChangelog(result);
            }

            @Override
            protected void done() {
                loadingKeys.remove(key);
                try {
                    String html = get();
                    changelogCache.put(key, html);
                    if (expandedRow == modelRow) showChangelog(html);
                } catch (Exception e) {
                    String message = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                    String html = "<p>加载更新日志失败：" + escapeHtml(message != null ? message : e.getClass().getSimpleName()) + "</p>";
                    changelogCache.put(key, html);
                    if (expandedRow == modelRow) showHtml(wrapHtml(html));
                }
            }
        }.execute();
    }

    private void showLoading() {
        showHtml("<html><body><p>正在加载更新日志...</p></body></html>");
    }

    private void showChangelog(String html) {
        showHtml(wrapHtml(html == null || html.isBlank() ? "<p>该文件没有提供更新日志。</p>" : html));
    }

    private void showHtml(String html) {
        changelogPane.setText(html);
        changelogPane.setCaretPosition(0);
    }

    private String wrapHtml(String html) {
        String trimmed = html == null ? "" : html.trim();
        if (trimmed.regionMatches(true, 0, "<html", 0, 5)) return trimmed;
        return "<html><body>" + trimmed + "</body></html>";
    }

    private String cacheKey(CurseForgeProjectService.UpdateResult result) {
        return result.projectId() + ":" + result.newFileId();
    }

    private static String escapeHtml(String value) {
        return value == null ? "" : value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;");
    }

    private static class UpdateTableModel extends AbstractTableModel {
        private final String[] columns = {"日志", "选择", "Mod", "当前", "即将更新"};
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

        CurseForgeProjectService.UpdateResult resultAt(int rowIndex) {
            return rows.get(rowIndex).result;
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
            return columnIndex == SELECT_COLUMN ? Boolean.class : String.class;
        }
        @Override public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex == SELECT_COLUMN && rows.get(rowIndex).result.updateAvailable();
        }
        @Override public Object getValueAt(int rowIndex, int columnIndex) {
            Row row = rows.get(rowIndex);
            var result = row.result;
            return switch (columnIndex) {
                case CHANGELOG_COLUMN -> rowIndex == expandedRowHolder ? EXPANDED_MARK : COLLAPSED_MARK;
                case SELECT_COLUMN -> row.selected;
                case 2 -> result.name();
                case 3 -> result.oldFilename();
                case 4 -> result.updateAvailable() ? result.newFilename() : "";
                default -> "";
            };
        }

        private int expandedRowHolder = -1;

        void setExpandedRow(int expandedRow) {
            this.expandedRowHolder = expandedRow;
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
            if (columnIndex != SELECT_COLUMN) return;
            Row row = rows.get(rowIndex);
            row.selected = row.result.updateAvailable() && Boolean.TRUE.equals(value);
            fireTableCellUpdated(rowIndex, columnIndex);
        }
    }

    private class CompactCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus,
                                                       int row, int column) {
            JLabel label = (JLabel) super.getTableCellRendererComponent(table, value, selected, focus, row, column);
            label.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
            label.setToolTipText(value != null ? value.toString() : null);
            label.setHorizontalAlignment(column == CHANGELOG_COLUMN ? SwingConstants.CENTER : SwingConstants.LEFT);
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
