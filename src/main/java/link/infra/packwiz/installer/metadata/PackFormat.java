package link.infra.packwiz.installer.metadata;

public record PackFormat(int version) {
    public static final PackFormat DEFAULT = new PackFormat(1);
}
