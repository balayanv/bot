#!/bin/sh
# Build bot (native linux binary) into bin/.
# Requires a JDK (17+) and a C toolchain with glibc headers:
# the Kotlin/Native compiler itself is downloaded by Gradle, no kotlinc install needed.
set -eu
cd "$(dirname "$0")"

if ! command -v java >/dev/null 2>&1; then
  echo "error: java (JDK 17+) is not installed — Gradle needs it."
  echo "install it for linux with one of:"
  echo "  sudo apt install openjdk-21-jdk   (debian/ubuntu)"
  echo "  sudo dnf install java-21-openjdk-devel   (fedora)"
  echo "  or download: https://adoptium.net/"
  exit 1
fi

if ! command -v gcc >/dev/null 2>&1 || ! command -v ld >/dev/null 2>&1; then
  echo "error: C toolchain (gcc/ld with glibc headers) is not installed — Kotlin/Native needs it to link."
  echo "install it for linux with one of:"
  echo "  sudo apt install build-essential   (debian/ubuntu)"
  echo "  sudo dnf install gcc binutils glibc-devel   (fedora)"
  exit 1
fi

case "$(uname -m)" in
  x86_64) TARGET="linuxX64" ;;
  aarch64) TARGET="linuxArm64" ;;
  *) echo "error: unsupported linux arch $(uname -m)"; exit 1 ;;
esac

./gradlew -q "linkReleaseExecutable$TARGET"
mkdir -p bin
rm -f bin/bot
cp "build/bin/$TARGET/releaseExecutable/bot.kexe" bin/bot
echo "built: $(pwd)/bin/bot"
