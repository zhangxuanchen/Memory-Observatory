# nginx 前端 + HTTPS 反代
# 注意：构建上下文为仓库根目录（见 docker-compose.yml 的 build.context），
#       静态资源统一从 mo-server/src/main/resources/static 拷贝（单一来源），
#       避免 mo-dashboard 下出现过期副本。
FROM docker.m.daocloud.io/library/nginx:alpine

# openssl 用于 entrypoint 首次启动时生成自签名证书
RUN apk add --no-cache openssl

# nginx 配置模板：官方镜像启动时自动 envsubst 替换其中的 ${MO_API_KEY}，
# 输出到 /etc/nginx/conf.d/default.conf（实现本机零配置鉴权注入）
COPY mo-dashboard/nginx.conf /etc/nginx/templates/default.conf.template
COPY mo-dashboard/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

# 前端静态资源（与 Spring Boot 内嵌静态页同源同版本）
COPY mo-server/src/main/resources/static/ /usr/share/nginx/html/
# 独立演示页（非必需，随包提供）
COPY mo-dashboard/flow-view-demo.html /usr/share/nginx/html/flow-view-demo.html

EXPOSE 443 80
ENTRYPOINT ["/entrypoint.sh"]
