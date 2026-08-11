#!/bin/sh
set -eu

if [ "$#" -ne 2 ]; then
    echo "usage: prepare_patch.sh /path/to/War2Patch_202.exe destination" >&2
    exit 2
fi

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
harness_dir=$(dirname -- "$script_dir")
repo_dir=$(CDPATH= cd -- "$harness_dir/../.." && pwd)
work_dir="$harness_dir/work/java"
classpath_file="$work_dir/classpath.txt"
classes_dir="$work_dir/classes"

mkdir -p "$classes_dir"
mvn -q -f "$repo_dir/pom.xml" -pl launcher dependency:build-classpath \
    -Dmdep.outputFile="$classpath_file"
classpath=$(sed -n '1p' "$classpath_file")
javac -Xlint:all -Werror -cp "$classpath" -d "$classes_dir" \
    "$harness_dir/java/MpqTool.java"
java -cp "$classes_dir:$classpath" MpqTool extract-patch "$1" "$2"
