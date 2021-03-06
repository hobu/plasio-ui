#!/bin/sh

CWD=`pwd`
TARGET_DIR="$CWD/resources/public"

cp ../plasio.js/dist/renderer.cljs.js "$TARGET_DIR/js/plasio-renderer.cljs.js"

if [ ! -d "$TARGET_DIR/lib/dist" ] ; then
    mkdir -p "$TARGET_DIR/lib/dist"
fi

cp ../plasio.js/dist/plasio.js "$TARGET_DIR/lib/dist/"
cp ../plasio.js/dist/plasio.webworker.js "$TARGET_DIR/lib/dist/"
cp ../plasio.js/dist/plasio.color.webworker.js "$TARGET_DIR/lib/dist/"
cp ../plasio.js/dist/laz-perf.asm.js "$TARGET_DIR/lib/dist/"
cp ../plasio.js/dist/laz-perf.asm.js.mem "$TARGET_DIR/lib/dist/"
cp ../plasio.js/dist/laz-perf.js "$TARGET_DIR/lib/dist/"
cp ../plasio.js/dist/laz-perf.wasm "$TARGET_DIR/lib/dist/"
