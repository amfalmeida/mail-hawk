#!/bin/sh
MVN_VERSION="3.9.9"
if [ ! -d "${HOME}/.m2/wrapper/dists/apache-maven-${MVN_VERSION}" ]; then
    mkdir -p "${HOME}/.m2/wrapper/dists"
    echo "Downloading Maven ${MVN_VERSION}..."
    curl -fsSL "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/${MVN_VERSION}/apache-maven-${MVN_VERSION}-bin.tar.gz" | tar xzf - -C "${HOME}/.m2/wrapper/dists"
fi
exec "${HOME}/.m2/wrapper/dists/apache-maven-${MVN_VERSION}/bin/mvn" -f "$(dirname "$0")/pom.xml" "$@"