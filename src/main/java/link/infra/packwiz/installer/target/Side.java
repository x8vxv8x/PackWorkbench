package link.infra.packwiz.installer.target;

import com.google.gson.annotations.SerializedName;

public enum Side {
    @SerializedName("client") CLIENT,
    @SerializedName("server") SERVER,
    @SerializedName("both") BOTH;

    public static Side from(String name) {
        return switch (name.toLowerCase()) {
            case "client" -> CLIENT;
            case "server" -> SERVER;
            case "both" -> BOTH;
            default -> null;
        };
    }

    public boolean hasSide(Side other) {
        return this == BOTH || other == BOTH || this == other;
    }
}
