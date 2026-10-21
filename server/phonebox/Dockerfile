FROM python:3.12-slim
# 需要代理时用 --build-arg HTTPS_PROXY=... 传入（Docker 预定义参数，不写进镜像层）
# 版本固定为本地验证过的组合；两者都有 x86_64 与 aarch64 的 manylinux 轮子
RUN pip install --no-cache-dir numpy==2.5.4 ai-edge-litert==2.3.0 cryptography==50.0.2
WORKDIR /app
COPY server.py smsrules.py push.py index.html manifest.json sw-v3.js *.png /app/
EXPOSE 8080
CMD ["python3", "/app/server.py"]
