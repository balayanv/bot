#!/bin/sh
# Build bot (JVM fat jar) into bin/. Works on mac and linux.
# Requires a JDK (17+): Gradle and Kotlin are downloaded by the wrapper.
set -eu
cd "$(dirname "$0")"

if ! command -v java >/dev/null 2>&1; then
  echo "error: java (JDK 17+) is not installed."
  echo "install it with one of:"
  echo "  brew install --cask temurin   (mac)"
  echo "  sudo apt install openjdk-21-jdk   (debian/ubuntu)"
  echo "  sudo dnf install java-21-openjdk-devel   (fedora)"
  echo "  or download: https://adoptium.net/"
  exit 1
fi

./gradlew -q jar
mkdir -p bin
rm -f bin/bot bin/bot.jar
cp build/libs/bot.jar bin/bot.jar
cat > bin/bot <<'SH'
#!/bin/sh
exec java -jar "$(dirname "$0")/bot.jar" "$@"
SH
chmod +x bin/bot
echo "built: $(pwd)/bin/bot"
