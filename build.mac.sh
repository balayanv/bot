#!/bin/sh
# Build bot (native mac binary) into bin/.
# Requires a JDK (17+) and Xcode command line tools:
# the Kotlin/Native compiler itself is downloaded by Gradle, no kotlinc install needed.
set -eu
cd "$(dirname "$0")"

if ! command -v java >/dev/null 2>&1; then
  echo "error: java (JDK 17+) is not installed — Gradle needs it."
  echo "install it for mac with one of:"
  echo "  brew install --cask temurin"
  echo "  brew install openjdk"
  echo "  or download: https://adoptium.net/"
  exit 1
fi

if ! xcode-select -p >/dev/null 2>&1; then
  echo "error: Xcode command line tools are not installed — Kotlin/Native needs them to link."
  echo "install them with:"
  echo "  xcode-select --install"
  exit 1
fi

case "$(uname -m)" in
  arm64) TARGET="macosArm64" ;;
  x86_64) TARGET="macosX64" ;;
  *) echo "error: unsupported mac arch $(uname -m)"; exit 1 ;;
esac

./gradlew -q "linkReleaseExecutable$TARGET"
mkdir -p bin
rm -f bin/bot
cp "build/bin/$TARGET/releaseExecutable/bot.kexe" bin/bot
echo "built: $(pwd)/bin/bot"
