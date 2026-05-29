package link.infra.packwiz.installer.ui.gui;

import link.infra.packwiz.installer.ui.IUserInterface;
import link.infra.packwiz.installer.ui.data.ExceptionDetails;
import link.infra.packwiz.installer.ui.data.IOptionDetails;
import link.infra.packwiz.installer.ui.data.InstallProgress;
import link.infra.packwiz.installer.util.Log;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public class GUIHandler implements IUserInterface {
    private InstallWindow installWindow;
    private String title = "packwiz 安装器";
    private boolean optionsButtonPressed = false;
    private boolean cancelButtonPressed = false;
    private Runnable cancelCallback = null;
    private boolean firstInstall = false;

    @Override
    public void show() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}
        installWindow = new InstallWindow(title);
        installWindow.setVisible(true);
    }

    @Override
    public void dispose() {
        if (installWindow != null) {
            SwingUtilities.invokeLater(installWindow::dispose);
        }
    }

    @Override
    public void showErrorAndExit(String message, Exception e) {
        String msg = e != null ? message + ": " + e.getMessage() : message;
        try {
            EventQueue.invokeAndWait(() -> {
                JOptionPane.showMessageDialog(null, msg, title, JOptionPane.ERROR_MESSAGE);
            });
        } catch (Exception ignored) {}
        System.exit(1);
    }

    @Override public void setTitle(String title) { this.title = title; }
    @Override public String getTitle() { return title; }

    @Override
    public void submitProgress(InstallProgress progress) {
        if (installWindow != null) {
            installWindow.updateProgress(progress);
        }
    }

    @Override
    public boolean showOptions(List<IOptionDetails> options) {
        var future = new CompletableFuture<Boolean>();
        try {
            EventQueue.invokeAndWait(() -> {
                var dialog = new OptionsSelectWindow(installWindow, options);
                dialog.setVisible(true);
                future.complete(dialog.isCancelled());
            });
        } catch (Exception e) {
            future.complete(true);
        }
        try {
            return future.get();
        } catch (Exception e) {
            return true;
        }
    }

    @Override
    public ExceptionListResult showExceptions(List<ExceptionDetails> exceptions, int numTotal, boolean allowsIgnore) {
        var future = new CompletableFuture<ExceptionListResult>();
        try {
            EventQueue.invokeAndWait(() -> {
                var dialog = new ExceptionListWindow(installWindow, exceptions, numTotal, allowsIgnore);
                dialog.setVisible(true);
                future.complete(dialog.getResult());
            });
        } catch (Exception e) {
            future.complete(ExceptionListResult.CANCEL);
        }
        try {
            return future.get();
        } catch (Exception e) {
            return ExceptionListResult.CANCEL;
        }
    }

    @Override
    public void awaitOptionalButton(boolean showCancel, long timeout) {
        // TODO: implement optional button waiting in GUI
    }

    @Override public boolean isOptionsButtonPressed() { return optionsButtonPressed; }
    @Override public void setOptionsButtonPressed(boolean pressed) { this.optionsButtonPressed = pressed; }
    @Override public boolean isCancelButtonPressed() { return cancelButtonPressed; }
    @Override public void setCancelButtonPressed(boolean pressed) { this.cancelButtonPressed = pressed; }
    @Override public Runnable getCancelCallback() { return cancelCallback; }
    @Override public void setCancelCallback(Runnable callback) { this.cancelCallback = callback; }
    @Override public boolean isFirstInstall() { return firstInstall; }
    @Override public void setFirstInstall(boolean firstInstall) { this.firstInstall = firstInstall; }
}
