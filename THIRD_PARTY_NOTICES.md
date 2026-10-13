# 第三方来源与许可

## 真题录音、英文与参考译文

- 原始来源：[Ysoseri1224/CET-6-Listening-10-Years-Resources-and-Analysis](https://github.com/Ysoseri1224/CET-6-Listening-10-Years-Resources-and-Analysis)，来源固定提交 `42fc6658e0b4a3dbae06755ba105d79169820576`。
- 资源文件按来源声明采用 [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)。来源 README 分析文章另有许可，未收入本软件源码仓库。
- 整理版本：[buting-listening-resources](https://github.com/happypotato2024/buting-listening-resources)，2.0 种子目录绑定提交 `738ab7b66999933761a1077870d56a5a46ad5433`。
- 整理包括套号核对、声学时间对齐、音频帧索引、单句拆分和参考译文制作；原录音保留来源字节。译文含 Google Translate 生成内容，未全面人工审校，非官方答案。
- 本仓库中的资料目录与安装包内元数据保留来源署名，完整资料及来源声明在资料仓库。

## 识别与翻译引擎

| 组件 | 用途 | 许可 / 来源 |
| --- | --- | --- |
| whisper.cpp 1.8.2 | 本机语音识别与 DTW | MIT；[源码](https://github.com/ggml-org/whisper.cpp) |
| OpenAI Whisper base.en | 英语识别模型，按需下载 | MIT；[模型](https://huggingface.co/ggerganov/whisper.cpp) |
| llama.cpp 0.6.0 | 本机翻译推理 | MIT；[源码](https://github.com/ggml-org/llama.cpp) |
| Qwen2.5-1.5B-Instruct Q4_K_M | 可选本机翻译模型，按需下载 | Apache-2.0；[模型](https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF) |

完整许可文本在 `licenses/`，APK 内亦提供开源许可页面。模型权重不纳入软件仓库或 APK。

Google 联网翻译是外部服务，不包含其服务端软件；需要网络，受地区可达性、限流及服务规则影响。

## 项目自身内容

自有代码和图标目前未单独指定开源许可；公开源码不自动授予任意再分发或商用授权。第三方材料仍遵循各自许可。本项目与「不背单词」及上述第三方不存在官方隶属或背书关系。
