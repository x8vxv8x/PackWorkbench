package link.infra.packwiz.installer.ui.data;

public record InstallProgress(String text, int current, int total) {
    public InstallProgress(String text) {
        this(text, 0, 0);
    }
}
