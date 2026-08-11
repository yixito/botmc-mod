#!/bin/bash
# End-to-end: boot headless server -> spawn bot -> WS smoke test -> cleanup.
cd "$(dirname "$0")/.."
export JAVA_HOME='C:\Users\Danny\jdk21\jdk-21.0.12+8'
GRADLE="C:/Users/Danny/gradle-8.14.3/gradle-8.14.3/bin/gradle"

for pid in $(netstat -ano | grep -E ":8080|:25565" | grep LISTENING | awk '{print $NF}' | sort -u); do
  taskkill //F //PID $pid >/dev/null 2>&1
done
sleep 2; rm -rf run/world run/logs run/crash-reports; rm -f /tmp/server.log /tmp/mc-in
mkfifo /tmp/mc-in
( exec 3<>/tmp/mc-in; cat <&3 ) | "$GRADLE" runServer --console=plain > /tmp/server.log 2>&1 &
SRV=$!
for i in $(seq 1 100); do grep -aqE "Done \(" /tmp/server.log 2>/dev/null && break; sleep 3; done
TOKEN=$(grep -aoE "t=[0-9a-f]{32}" /tmp/server.log | head -1 | cut -d= -f2)
echo "TOKEN=$TOKEN"
echo "remotebot spawn" > /tmp/mc-in
sleep 8
grep -aE "spawned|ERROR|crash" /tmp/server.log | tail -3
echo "=== WS test ==="
node test/ws-test.mjs "$TOKEN"
echo "=== cleanup ==="
for pid in $(netstat -ano | grep -E ":8080|:25565" | grep LISTENING | awk '{print $NF}' | sort -u); do
  taskkill //F //PID $pid >/dev/null 2>&1
done
kill $SRV 2>/dev/null
echo done
