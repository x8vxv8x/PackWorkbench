package link.infra.packwiz.installer.metadata.curseforge;

public record CurseForgeUpdateData(int fileId, int projectId) implements UpdateData {
}
