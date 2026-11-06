#!/bin/sh
# 精简版 Gradle wrapper 启动脚本：直接调用 wrapper jar
DIR=$(cd "$(dirname "$0")" && pwd)
if [ -n "$JAVA_HOME" ]; then
  JAVACMD="$JAVA_HOME/bin/java"
else
  JAVACMD="java"
fi
exec "$JAVACMD" -Dorg.gradle.appname=gradlew -classpath "$DIR/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
