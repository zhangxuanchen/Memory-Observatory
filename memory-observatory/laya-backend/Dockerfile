# MemoryObservatory 自带的 laya 推理后端（CPU 推理）
#
# server.py 是 laya 项目（Apache-2.0，Copyright Convai Innovations）的衍生作品，
# 归属与修改声明见同目录 NOTICE，许可证全文见同目录 LICENSE。
# 本镜像分发时时一并携带这两个文件（Apache-2.0 §4）。
#
# laya 本体只从 PyPI 安装，不依赖 laya 源码仓库。
# 权重不烘焙进镜像：首次启动由 server.py 预检并补下到挂载的 volume（~1.5GB），
# 之后重建容器也不用重下。
FROM docker.m.daocloud.io/library/python:3.12-slim

# PyPI 直连在国内可能很慢，需要时用 --build-arg 覆盖
ARG PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple
# CPU 版 torch 源：PyPI 默认的 linux torch 会拖入数 GB 的 CUDA 运行库，本后端用不上
ARG TORCH_INDEX_URL=https://download.pytorch.org/whl/cpu

WORKDIR /app

RUN pip install --no-cache-dir --index-url ${TORCH_INDEX_URL} torch

COPY requirements.txt .
RUN pip install --no-cache-dir -i ${PIP_INDEX_URL} -r requirements.txt

COPY server.py .

# 衍生作品的归属与许可：必须随分发物一同携带（Apache-2.0 §4(a)(b)）
COPY LICENSE NOTICE /licenses/

LABEL org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.vendor="Memory Observatory" \
      org.opencontainers.image.description="laya 语义过滤后端（衍生自 laya java/backend/server.py，Apache-2.0；详见 /licenses/NOTICE）"

# 权重缓存目录：由 compose 挂 volume 持久化
ENV HF_HOME=/data/hf \
    HF_ENDPOINT=https://hf-mirror.com \
    PYTHONUNBUFFERED=1
VOLUME ["/data/hf"]

EXPOSE 8770
CMD ["python", "server.py", "--host", "0.0.0.0", "--port", "8770", \
     "--preload", "english,multilingual"]
