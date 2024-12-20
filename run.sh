#!/usr/bin/env bash
set -e

mvn package
java -jar "target/SCAIEV-0.0.1-SNAPSHOT-jar-with-dependencies.jar" "$@"

