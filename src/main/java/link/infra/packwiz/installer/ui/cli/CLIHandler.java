package link.infra.packwiz.installer.ui.cli;

import link.infra.packwiz.installer.ui.IUserInterface;
import link.infra.packwiz.installer.ui.data.ExceptionDetails;
import link.infra.packwiz.installer.ui.data.IOptionDetails;
import link.infra.packwiz.installer.ui.data.InstallProgress;
import link.infra.packwiz.installer.util.Log;

import java.util.List;

public class CLIHandler implements IUserInterface {
    private String title = "packwiz 安装器";
    private boolean optionsButtonPressed = false;
    private boolean cancelButtonPressed = false;
    private Runnable cancelCallback = null;
    private boolean firstInstall = false;

    @Override public void show() {}
    @Override public void dispose() {}

    @Override
    public void showErrorAndExit(String message, Exception e) {
        if (e != null) {
            System.err.println(message + ": " + e.getMessage());
        } else {
            System.err.println(message);
        }
        System.exit(1);
    }

    @Override public void setTitle(String title) { this.title = title; }
    @Override public String getTitle() { return title; }

    @Override
    public void submitProgress(InstallProgress progress) {
        if (progress.total() > 0) {
            System.out.println("[" + progress.current() + "/" + progress.total() + "] " + progress.text());
        } else {
            System.out.println(progress.text());
        }
    }

    @Override
    public boolean showOptions(List<IOptionDetails> options) {
        // TODO: implement option choice in the CLI?
        Log.info("CLI: auto-accepting all optional mods");
        for (var opt : options) {
            opt.setOptionValue(true);
        }
        return false;
    }

    @Override
    public ExceptionListResult showExceptions(List<ExceptionDetails> exceptions, int numTotal, boolean allowsIgnore) {
        for (var ex : exceptions) {
            System.err.println("Error in " + ex.name() + ": " + ex.exception().getMessage());
        }
        return ExceptionListResult.CONTINUE;
    }

    @Override
    public void awaitOptionalButton(boolean showCancel, long timeout) {
        // CLI doesn't have a button to wait for
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
