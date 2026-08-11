#!/bin/bash
# Detached e2e helper: start server (nohup, survives this shell), print PID.
cd "$(dirname "$0")/.."
export JAVA_HOME='C:\Users\Danny\jdk21\jdk-21.0.12+8'
GRADLE="C:/Users/Danny/gradle-8.14.3/gradle-8.14.3/bin/gradle"

# kill any leftover servers from prior runs
for pid in $(netstat -ano | grep -E ":8080|:25565|:25575" | grep LISTENING | awk '{print $NF}' | sort -u); do
  taskkill //F //PID $pid >/dev/null 2>&1
done
sleep 2
rm -f /tmp/server.log
nohup "$GRADLE" runServer --console=plain > /tmp/server.log 2>&1 < /dev/null &
echo "STARTED_PID=$!"
# wait for boot
for i in $(seq 1 120); do
  grep -aqE "Done \(" /tmp/server.log 2>/dev/null && { echo "BOOTED at attempt $i"; break; }
  sleep 2
done
grep -aoE "t=[0-9a-f]{32}" /tmp/server.log | head -1 | sed 's/^/TOKEN=/'
