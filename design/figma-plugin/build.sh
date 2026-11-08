#!/bin/sh
# Concatenate the plugin sources into code.js (the file manifest.json points at).
cd "$(dirname "$0")" && cat src/01-core.js src/02-components.js src/03-shells-auth.js src/04-lead.js src/05-more.js src/06-main.js > code.js && node --check code.js && echo "built code.js"
