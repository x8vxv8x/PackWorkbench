package link.infra.packwiz.installer.ui;

import link.infra.packwiz.installer.ui.data.ExceptionDetails;
import link.infra.packwiz.installer.ui.data.IOptionDetails;
import link.infra.packwiz.installer.ui.data.InstallProgress;

import java.util.List;

public interface IUserInterface {
    void show();
    void dispose();

    default void showErrorAndExit(String message) {
        showErrorAndExit(message, null);
    }
    void showErrorAndExit(String message, Exception e);

    void setTitle(String title);
    String getTitle();
    void submitProgress(InstallProgress progress);
    boolean showOptions(List<IOptionDetails> options);
    ExceptionListResult showExceptions(List<ExceptionDetails> exceptions, int numTotal, boolean allowsIgnore);
    default void disableOptionsButton(boolean hasOptions) {}
    default CancellationResult showCancellationDialog() { return CancellationResult.QUIT; }
    default UpdateConfirmationResult showUpdateConfirmationDialog(
        List<String[]> oldVersions, List<String[]> newVersions
    ) { return UpdateConfirmationResult.UPDATE; }
    void awaitOptionalButton(boolean showCancel, long timeout);

    enum ExceptionListResult { CONTINUE, CANCEL, IGNORE }
    enum CancellationResult { QUIT, CONTINUE }
    enum UpdateConfirmationResult { CANCELLED, CONTINUE, UPDATE }

    boolean isOptionsButtonPressed();
    void setOptionsButtonPressed(boolean pressed);
    boolean isCancelButtonPressed();
    void setCancelButtonPressed(boolean pressed);
    Runnable getCancelCallback();
    void setCancelCallback(Runnable callback);
    boolean isFirstInstall();
    void setFirstInstall(boolean firstInstall);

    /**
     * Utility: execute a function, catching exceptions and showing error.
     */
    default <T> T wrap(String message, java.util.function.Supplier<T> inner) {
        try {
            return inner.get();
        } catch (Exception e) {
            showErrorAndExit(message, e);
            return null; // unreachable
        }
    }
}
