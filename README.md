
# Life Sanctuary

Minecraft 1.21.1 / NeoForge 21.1.228，Java 21。

当前开发状态与后续交接请先阅读：

- [2026-10-27 进度汇总](docs/progress-2026-10-27.md)：本次累计变更、验证与下一步。
- [架构交接](docs/architecture-handoff.md)：共享规则、约束和历次接入记录。
- [39项修订状态](docs/revisions-2026-10-27.md)及[追加需求](docs/revisions-2026-10-27-additions.md)：区分已接入的基础逻辑与仍待制作的具体内容。
- [异孢性接入](docs/heterospory-integration.md)：目前正在推进的第39条。

本地编译检查：

```powershell
.\gradlew.bat compileJava --no-daemon --stacktrace
```

当前为开发中的基础架构，部分方块使用占位美术；尚未注册的物种、群系结构、培育方案及小游戏关卡不会凭空启用。编译通过不代表游戏联调已完成。

以下保留原MDK模板说明。

## Installation information

This template repository can be directly cloned to get you started with a new
mod. Simply create a new repository cloned from this one, by following the
instructions provided by [GitHub](https://docs.github.com/en/repositories/creating-and-managing-repositories/creating-a-repository-from-a-template).

Once you have your clone, simply open the repository in the IDE of your choice. The usual recommendation for an IDE is either IntelliJ IDEA or Eclipse.

If at any point you are missing libraries in your IDE, or you've run into problems you can
run `gradlew --refresh-dependencies` to refresh the local cache. `gradlew clean` to reset everything
{this does not affect your code} and then start the process again.

## Mapping Names:
By default, the MDK is configured to use the official mapping names from Mojang for methods and fields
in the Minecraft codebase. These names are covered by a specific license. All modders should be aware of this
license. For the latest license text, refer to the mapping file itself, or the reference copy here:
https://github.com/NeoForged/NeoForm/blob/main/Mojang.md

## Additional Resources:
Community Documentation: https://docs.neoforged.net/
NeoForged Discord: https://discord.neoforged.net/
