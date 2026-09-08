#!/bin/sh
# Gradle wrapper launcher. The matching gradle-wrapper.jar must be supplied by
# the controlled build environment; it is intentionally not generated here.
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec java ${JAVA_OPTS:-} -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
