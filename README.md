# packwiz-installer
An installer for launching packwiz modpacks with MultiMC.

## Usage

```
java -jar packwiz-installer.jar <pack.toml URL or path>
```

### Options

| Option | Description |
|---|---|
| `-s, --side <client/server>` | 安装模组的端 (默认为 client) |
| `--title <title>` | 安装窗口标题 |
| `--pack-folder <path>` | 安装整合包的文件夹（默认为当前目录） |
| `--multimc-folder <path>` | MultiMC 整合包文件夹（默认为整合包目录的上级） |
| `--meta-file <filename>` | 存储整合包元数据的 JSON 文件（默认为 packwiz.json） |
| `-t, --timeout <seconds>` | 询问可选模组时自动启动前等待的秒数（默认为 10） |
| `-g, --no-gui` | 不显示 GUI 界面 |
