package link.infra.packwiz.installer.ui.gui;

import link.infra.packwiz.installer.ui.IUserInterface;
import link.infra.packwiz.installer.ui.data.ExceptionDetails;
import link.infra.packwiz.installer.util.Log;

import javax.swing.*;
import java.awt.*;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

public class ExceptionListWindow extends JDialog {
    private IUserInterface.ExceptionListResult result = IUserInterface.ExceptionListResult.CANCEL;

    public ExceptionListWindow(JFrame parent, List<ExceptionDetails> exceptions, int numTotal, boolean allowsIgnore) {
        super(parent, "错误列表", true);
        setSize(500, 350);
        setLocationRelativeTo(parent);
        setLayout(new BorderLayout(8, 8));

        var textArea = new JTextArea();
        textArea.setEditable(false);
        textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        var sb = new StringBuilder();
        sb.append(exceptions.size()).append(" / ").append(numTotal).append(" 个文件出错：\n\n");
        for (var ex : exceptions) {
            sb.append("[").append(ex.name()).append("] ").append(ex.exception().getMessage()).append("\n");
            if (ex.url() != null) {
                sb.append("  URL: ").append(ex.url()).append("\n");
            }
            var sw = new StringWriter();
            ex.exception().printStackTrace(new PrintWriter(sw));
            sb.append(sw).append("\n");
        }
        textArea.setText(sb.toString());
        textArea.setCaretPosition(0);

        add(new JScrollPane(textArea), BorderLayout.CENTER);

        var buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        var continueButton = new JButton("继续");
        var cancelButton = new JButton("取消");
        continueButton.addActionListener(e -> {
            result = IUserInterface.ExceptionListResult.CONTINUE;
            dispose();
        });
        cancelButton.addActionListener(e -> {
            result = IUserInterface.ExceptionListResult.CANCEL;
            dispose();
        });
        buttonPanel.add(continueButton);
        buttonPanel.add(cancelButton);

        if (allowsIgnore) {
            var ignoreButton = new JButton("忽略并继续");
            ignoreButton.addActionListener(e -> {
                result = IUserInterface.ExceptionListResult.IGNORE;
                dispose();
            });
            buttonPanel.add(ignoreButton);
        }

        add(buttonPanel, BorderLayout.SOUTH);
    }

    public IUserInterface.ExceptionListResult getResult() { return result; }
}
