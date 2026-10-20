#!/bin/bash
# 构建并发布到线上 https://nocode.leorxx.xyz
#
# 线上目录 /srv/nocode：
#   app.jar                 后端（systemd: nocode-backend，只监听 127.0.0.1:8125）
#   application-prod.yaml   线上配置（含密钥，不进 git）
#   web/                    前端静态文件（Caddy 直接提供）
#   tmp/code_deploy/        AI 生成网站的部署目录（Caddy 映射到 /dist）
#   monitoring/             Prometheus + Grafana（docker compose，密钥在 monitoring/.env，不进 git）
#
# 用法：scripts/deploy-nocode.sh [backend|frontend|monitoring]，不带参数时发布前后端
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEPLOY_DIR=/srv/nocode
export JAVA_HOME="${JAVA_HOME:-$HOME/.local/opt/jdk-21}"
export PATH="$JAVA_HOME/bin:$HOME/.local/opt/node-24/bin:$PATH"
TARGET="${1:-all}"

deploy_backend() {
  echo "==> 构建后端"
  (cd "$ROOT" && sh mvnw -B -q -DskipTests package)
  cp "$ROOT/target/ai-gen-web-0.0.1-SNAPSHOT.jar" "$DEPLOY_DIR/app.jar"
  echo "==> 重启后端"
  sudo systemctl restart nocode-backend
  for _ in $(seq 60); do
    curl -sf http://127.0.0.1:8125/api/health/ >/dev/null && { echo "后端已启动"; return; }
    sleep 2
  done
  echo "后端启动超时，查看日志：sudo journalctl -u nocode-backend -n 100" >&2
  exit 1
}

deploy_frontend() {
  echo "==> 构建前端（关闭注册入口）"
  # 用 npm install 而不是 npm ci：依赖没变时不会删掉重装 node_modules，同一目录下开发环境的 Vite 缓存不会失效
  (cd "$ROOT/ai-gen-web-frontend" && npm install --no-audit --no-fund --silent && VITE_REGISTER_ENABLED=false npm run build)
  rm -rf "$DEPLOY_DIR/web.new" && cp -r "$ROOT/ai-gen-web-frontend/dist" "$DEPLOY_DIR/web.new"
  # 原地替换目录内容，Caddy 挂载的是 web 目录本身，不能整个换掉
  rsync -a --delete "$DEPLOY_DIR/web.new/" "$DEPLOY_DIR/web/" && rm -rf "$DEPLOY_DIR/web.new"
  echo "前端已发布"
}

deploy_monitoring() {
  local dir="$DEPLOY_DIR/monitoring"
  echo "==> 同步监控配置"
  mkdir -p "$dir"
  rsync -a --delete --exclude .env "$ROOT/monitoring/" "$dir/"
  if [ ! -f "$dir/.env" ]; then
    (umask 077 && echo "GRAFANA_ADMIN_PASSWORD=$(openssl rand -base64 24 | tr -dc 'A-Za-z0-9' | head -c 24)" > "$dir/.env")
    echo "已生成 Grafana 管理员密码：$dir/.env"
  fi
  # Grafana 容器以 472 用户运行，在这里创建 unix socket，Caddy 通过 /srv/nocode-run/grafana 访问
  sudo install -d -o 472 -g 472 -m 755 "$DEPLOY_DIR/run/grafana"
  echo "==> 启动 Prometheus + Grafana"
  (cd "$dir" && sudo docker compose up -d --remove-orphans)
  for _ in $(seq 30); do
    curl -sf http://127.0.0.1:9090/-/ready >/dev/null && sudo test -S "$DEPLOY_DIR/run/grafana/grafana.sock" && { echo "监控已启动"; return; }
    sleep 2
  done
  echo "监控启动超时，查看日志：cd $dir && sudo docker compose logs" >&2
  exit 1
}

case "$TARGET" in
  backend) deploy_backend ;;
  frontend) deploy_frontend ;;
  monitoring) deploy_monitoring ;;
  all) deploy_backend; deploy_frontend ;;
  *) echo "用法：$0 [backend|frontend|monitoring]" >&2; exit 1 ;;
esac

curl -s -o /dev/null -w "https://nocode.leorxx.xyz/ -> %{http_code}\n" https://nocode.leorxx.xyz/
