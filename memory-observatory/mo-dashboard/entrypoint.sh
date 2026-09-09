#!/bin/sh
# mo-dashboard 容器入口：
# 1. /etc/nginx/certs 下没有证书时，自动生成自签名证书（10 年有效，CN=localhost）；
#    生产环境可把真实证书挂载到该目录（tls.crt / tls.key）覆盖。
# 2. 前台启动 nginx。
set -e

CERT_DIR=/etc/nginx/certs
mkdir -p "$CERT_DIR"

if [ ! -f "$CERT_DIR/tls.crt" ] || [ ! -f "$CERT_DIR/tls.key" ]; then
  echo "[entrypoint] 未发现 TLS 证书，生成自签名证书（CN=localhost，3650 天）。"
  echo "[entrypoint] 生产环境请将正式证书挂载为 $CERT_DIR/tls.crt 与 tls.key。"
  openssl req -x509 -newkey rsa:2048 -nodes \
    -keyout "$CERT_DIR/tls.key" \
    -out "$CERT_DIR/tls.crt" \
    -days 3650 \
    -subj "/CN=localhost" \
    -addext "subjectAltName=DNS:localhost,IP:127.0.0.1"
fi

exec nginx -g 'daemon off;'
