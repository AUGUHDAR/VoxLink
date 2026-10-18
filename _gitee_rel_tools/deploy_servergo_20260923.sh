#!/bin/bash
set -e
cd /www/wwwroot/VoxLink
cp -f voxlink-server-linux-amd64 voxlink-server-linux-amd64.bak-0923
mv -f /tmp/voxlink-server-new voxlink-server-linux-amd64
chown www:www voxlink-server-linux-amd64
chmod +x voxlink-server-linux-amd64
PID=$(ps aux | grep '[v]oxlink-server' | grep -v -e bash -e su | awk '{print $2}')
if [ -n "$PID" ]; then
  kill "$PID"
  sleep 2
fi
nohup su -s /bin/bash www -c 'cd /www/wwwroot/VoxLink && exec ./voxlink-server-linux-amd64' >> /www/wwwlogs/go/VoxLink.log 2>&1 < /dev/null &
sleep 3
ps aux | grep '[v]oxlink-server' | grep -v -e bash -e su
echo DEPLOY_OK
