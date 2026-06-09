#!/bin/bash
# Iniciar display virtual
Xvfb :99 -screen 0 1280x800x24 -ac +extension GLX +render -noreset &
export DISPLAY=:99
sleep 2

# Iniciar o collector
exec node collector.js
