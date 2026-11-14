#!/usr/bin/env bash
# Hello Minecraft! Launcher (Qinglan fork) —— Linux 启动脚本
# 用法：./HMCL.sh <参数透传给 HMCL>
# 启动器本体只需任意 JRE/JDK 17+；游戏用的 Java 在 HMCL 设置里单独指定。
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

JAR=""
for candidate in "$DIR"/HMCL-*-ql2.jar; do
    if [ -f "$candidate" ]; then JAR="$candidate"; fi
done
if [ -z "$JAR" ]; then
    echo "未找到 $DIR/HMCL-*-ql2.jar" >&2
    exit 1
fi

JAVA=""
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
    JAVA="$(command -v java)"
else
    for candidate in /usr/lib/jvm/*/bin/java /opt/jdk*/bin/java /opt/java/bin/java /snap/bin/java; do
        if [ -x "$candidate" ]; then
            JAVA="$candidate"
            break
        fi
    done
fi
if [ -z "$JAVA" ]; then
    echo "未找到 Java，请安装 JRE 17+（例如 sudo apt install openjdk-21-jre）" >&2
    exit 1
fi

exec "$JAVA" -jar "$JAR" "$@"
