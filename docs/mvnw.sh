#!/usr/bin/env bash
# ============================================================
# mvnw.sh — 在工具沙箱里可靠调用 Maven
#
# 【为什么要这个脚本】
#   工具沙箱的 shell 把 `dirname` 等基础命令屏蔽了，而 Maven 的
#   bin/mvn 启动脚本第一步就是靠 dirname 解析自身位置来拼 classpath，
#   于是直接跑 mvn 会报：
#     ClassNotFoundException: org.codehaus.plexus.classworlds.launcher.Launcher
#   这不是 Maven 装坏了，是启动脚本没能正确自定位。
#
# 【解法】跳过 mvn 脚本，用 java 直接拉起 Maven 的 classworlds Launcher，
#   把 -Dmaven.home / -Dclassworlds.conf 显式传给 JVM，不依赖任何 shell 推断。
#
# 【用法】在 mall-server 目录下：
#   docs/mvnw.sh clean test
#   docs/mvnw.sh test -Dtest=RbacIntegrationTest
#   docs/mvnw.sh -q compile
#
# 【为什么锁死 JDK 21】项目 java.version=21，若回落到 JDK 17 会编译失败。
# ============================================================
set -euo pipefail

MAVEN_HOME="${MAVEN_HOME:-C:/Users/22522/tools/apache-maven-3.9.16}"
JAVA_BIN="${JAVA_BIN:-C:/Users/22522/.jdks/ms-21.0.12.1/bin/java.exe}"
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

CLASSWORLDS_JAR="$(ls "$MAVEN_HOME"/boot/plexus-classworlds-*.jar | head -n 1)"

exec "$JAVA_BIN" \
  -classpath "$CLASSWORLDS_JAR" \
  "-Dclassworlds.conf=$MAVEN_HOME/bin/m2.conf" \
  "-Dmaven.home=$MAVEN_HOME" \
  "-Dmaven.multiModuleProjectDirectory=$PROJECT_DIR" \
  "-Dfile.encoding=UTF-8" \
  org.codehaus.plexus.classworlds.launcher.Launcher "$@"
