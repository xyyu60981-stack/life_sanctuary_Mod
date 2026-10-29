# 可编辑模型资源备份

这些文件是已接入资源的建模源文件和必要图集，便于在其他电脑或由其他作者继续制作；游戏实际使用的资源仍在 `src/main/resources`。这里不是额外的资源包，不会自动打入模组。

| 目录 | 内容 |
| --- | --- |
| horsetail-v8 | 问荆V8模型、图集、原始导出资源和说明 |
| luminous-moss | 光藓幼苗、成年、孢蒴源模型及已选定的外置图集 |
| marchantia | 地钱两种基底、雌雄托源模型／导出文件、已批准的无缝图和生殖托图集 |
| attached-moss | 附着藓四变体平面模型、图集、绑定示例和原说明 |
| star-moss | 星苔本体、三种生殖托源模型／导出文件及已批准的外置图集 |

原始目录保持不变。未备份星苔旧版废稿、大幅参考照片、模组依赖JAR或构建缓存。部分源文件自带内嵌贴图，而游戏采用用户确认的外置图；不要仅凭bbmodel内嵌图覆盖已选定图集。每个源文件的字节数和SHA-256见 `manifest.json`。

重建工具（仓库根目录运行）：

```powershell
python tools/import_marchantia_assets.py --source model-sources/marchantia
python tools/import_star_moss_assets.py --source model-sources/star-moss
python tools/update_luminous_moss_overlays.py
python tools/validate_bryophyte_assets.py
```

地钱缩图工具需要Pillow；其余导入／验证工具仅使用Python标准库。当前行为和美术取舍以 `docs` 中对应物种接入说明及用户最新确认规则为准，原作者README中未确认的建议不作为新的行为指令。
