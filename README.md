# PackWorkbench

一个基于 Java/FlatLaf 的 packwiz + CurseForge 可视化工作台。

## 目标

- 用 packwiz 管理 mods、resourcepacks、shaderpacks 的联网下载和索引。
- 用外部 Git 工具管理配置、脚本和零碎文件。
- 以 CurseForge 导出 zip 作为主要打包格式。
- 不做 Modrinth 搜索、安装、更新或 `.mrpack` 导出。

## 当前能力

- 打开本地 packwiz 项目。
- 查看 `index.toml` 与 `.pw.toml` 资产表。
- 创建 URL 下载元数据。
- 创建 CurseForge 元数据。
- 刷新 `index.toml` 并更新 `pack.toml` 的 index hash。
- 执行下载同步。
- 导出 CurseForge zip，包含 `manifest.json`、`modlist.html` 和 `overrides/`。

## 运行

```powershell
.\gradlew.bat shadowJar
java -jar (Get-ChildItem build\libs\*-all.jar | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
```
