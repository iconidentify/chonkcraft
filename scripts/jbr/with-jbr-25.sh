#!/usr/bin/env bash
set -euo pipefail

if [[ $# -eq 0 ]]; then
  echo "usage: $0 <command> [args...]" >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
java_home="$("$SCRIPT_DIR/install-jbr-25.sh")"

export JAVA_HOME="$java_home"
export PATH="$JAVA_HOME/bin:$PATH"

# Quiet two JDK 25 warnings that Maven 3.9.9 raises about its OWN dependencies,
# eight lines of them per invocation, none of it about this project:
#
#   jansi calls System.load for its terminal colours, which JEP 472 restricts;
#   guava's AbstractFuture calls sun.misc.Unsafe::objectFieldOffset, which
#   JEP 471 deprecated for removal.
#
# Worth silencing rather than living with, because a build that prints eight
# warnings nobody can act on is a build whose ninth warning nobody reads. Maven
# fixes both upstream in time and these flags become unnecessary rather than
# wrong. Only the Maven JVM sees them: MAVEN_OPTS is read by the mvn launcher
# and ignored by everything else this wrapper runs.
#
# Appended rather than assigned, so a caller who set MAVEN_OPTS keeps it.
export MAVEN_OPTS="${MAVEN_OPTS:-} --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow"

exec "$@"
