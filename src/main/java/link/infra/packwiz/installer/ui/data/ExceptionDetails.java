package link.infra.packwiz.installer.ui.data;

public record ExceptionDetails(String name, Exception exception, String url) {
    public ExceptionDetails(String name, Exception exception) {
        this(name, exception, null);
    }
}
