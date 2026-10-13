# 参与贡献

欢迎提 issue 和 PR。几点约定：

- 文档和注释用简体中文，代码标识符用英文。
- 提交前在 `android/` 下跑 `./gradlew assembleDebug testDebugUnitTest`；改了服务端就跑 `python3 -m unittest -v server/phonebox/test/test_server.py`（需要 Docker 或 Podman）。
- **不要提交任何真实信息**：真实手机号、短信、域名、IP、口令、证书指纹、签名密钥。测试数据用 `13800000000` 这类虚构号码和 `example.com`。
- 新依赖请在 PR 里说明理由；SIP 等协议请用成熟的库，不要自己实现。
- 提交即表示同意以 GPL-3.0 发布你的贡献。
