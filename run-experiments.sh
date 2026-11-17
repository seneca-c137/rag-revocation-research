#!/usr/bin/env bash
# ==============================================================================
# Dynamic Access Control for RAG - Experiment Runner Script
# ==============================================================================
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# 1. Compile project classes
mvn -q test-compile -o

# 2. Construct runtime classpath
M2_REPO="${HOME}/.m2/repository"
CLASSPATH="target/classes:target/test-classes:\
${M2_REPO}/com/fasterxml/jackson/core/jackson-databind/2.17.2/jackson-databind-2.17.2.jar:\
${M2_REPO}/com/fasterxml/jackson/core/jackson-core/2.17.2/jackson-core-2.17.2.jar:\
${M2_REPO}/com/fasterxml/jackson/core/jackson-annotations/2.17.2/jackson-annotations-2.17.2.jar:\
${M2_REPO}/com/fasterxml/jackson/datatype/jackson-datatype-jsr310/2.17.2/jackson-datatype-jsr310-2.17.2.jar"

# 3. Execute ExperimentRunner
java -cp "$CLASSPATH" edu.academic.rag.accesscontrol.experiment.ExperimentRunner "$@"
