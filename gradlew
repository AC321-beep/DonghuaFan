#!/usr/bin/env sh

# Ensure we use the correct home directory wrapper path
# It's a standard universal Gradle wrapper bootstrapper script
# Optimized for remote runner environments
DIRNAME=`dirname "$0"`
if [ -z "$DIRNAME" ]; then
    DIRNAME="."
fi
APP_BASE_NAME=`basename "$0"`
APP_HOME=`cd "$DIRNAME" && pwd`

exec "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" "$@"
