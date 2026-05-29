package link.infra.packwiz.installer.ui.gui;

import link.infra.packwiz.installer.ui.data.IOptionDetails;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class OptionsSelectWindow extends JDialog {
    private boolean cancelled = true;
    private final List<OptionTempHandler> handlers;

    public OptionsSelectWindow(JFrame parent, List<IOptionDetails> options) {
        super(parent, "选择可选模组", true);
        handlers = new ArrayList<>();
        for (var opt : options) {
            handlers.add(new OptionTempHandler(opt));
        }

        setSize(400, 300);
        setLocationRelativeTo(parent);
        setLayout(new BorderLayout(8, 8));

        var panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        var scrollPane = new JScrollPane(panel);

        for (var handler : handlers) {
            var checkbox = new JCheckBox(handler.name() + " - " + handler.optionDescription(), handler.optionValue());
            checkbox.addActionListener(e -> handler.setOptionValue(checkbox.isSelected()));
            panel.add(checkbox);
        }

        add(scrollPane, BorderLayout.CENTER);

        var buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        var okButton = new JButton("确定");
        var cancelButton = new JButton("取消");
        okButton.addActionListener(e -> {
            cancelled = false;
            for (var h : handlers) h.applyToOriginal();
            dispose();
        });
        cancelButton.addActionListener(e -> {
            cancelled = true;
            dispose();
        });
        buttonPanel.add(okButton);
        buttonPanel.add(cancelButton);
        add(buttonPanel, BorderLayout.SOUTH);
    }

    public boolean isCancelled() { return cancelled; }
}
