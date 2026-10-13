# 正式版 Android 实现

正式版 2.0.0 / versionCode 13，包名 cn.tingjian.cet6。

使用及安装说明见根目录 README；构建步骤见 [构建说明](../docs/构建说明.md)，验证结果见 [2.0 验证记录](../docs/验证结果-v2.0.md)。

content 是正式界面，src 是 WebView 入口与原生桥接；tools/build-formal.py 在构建时组合共用引擎并固定识别 / 本机翻译为两线程。签名及工具缓存不在仓库中。
