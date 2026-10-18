#!/bin/bash
# deploy 1010sort: web lobby ordered by createdAt (web only) + "Hide Happy Link" UI button
exec 2>&1
SN=/www/wwwroot/VoxLink
UP=/root/upgrade_1010sort
BIN=voxlink-server-linux-amd64-1010sort
PIDF=/var/tmp/gopids/VoxLink.pid
WANT_MD5=26ab97a5e1b3b9aea48fc22c9b28da48
TS=$(date +%m%d-%H%M)

listener_pid() { ss -tlnp "sport = :1145" 2>/dev/null | grep -oE 'pid=[0-9]+' | head -1 | cut -d= -f2; }
stats() { curl -s -m 5 http://127.0.0.1:1145/api/v1/stats | head -c 150; }

echo "### 0. baseline ###"
stats; echo
BP=$(grep -c 'panic:' /www/wwwlogs/go/VoxLink.log 2>/dev/null); echo "  panic before=$BP"
echo "  tickets/feedback dirs: $(ls -1 $SN/data/tickets 2>/dev/null | wc -l) / $(ls -1 $SN/data/feedback 2>/dev/null | wc -l)"
echo "  rooms on disk: $(ls -1 $SN/data/rooms/rooms/*.json 2>/dev/null | wc -l)"

echo "### 1. backup ###"
cp -a $SN/voxlink-server-linux-amd64 $SN/voxlink-server-linux-amd64.bak-$TS
ls -la $SN/voxlink-server-linux-amd64.bak-$TS | awk '{print "  "$5" "$9}'

echo "### 2. stop by listener pid (verify exe) ###"
PID=$(listener_pid)
if [ -n "$PID" ]; then
  EXE=$(readlink /proc/$PID/exe 2>/dev/null); echo "  listener pid=$PID exe=$EXE"
  case "$EXE" in */voxlink-server-linux-amd64*) ;; *) echo "FATAL: holder is not this service"; exit 1 ;; esac
  kill "$PID"; sleep 3
  kill -0 "$PID" 2>/dev/null && { kill -9 "$PID"; sleep 2; }
fi
ss -tlnp "sport = :1145" | grep -q ':1145' && { echo "FATAL: port still held"; exit 1; }
echo "  port 1145 released"

echo "### 3. install ###"
install -m 755 -o www -g www $UP/$BIN $SN/voxlink-server-linux-amd64
GOT=$(md5sum $SN/voxlink-server-linux-amd64 | cut -d' ' -f1)
echo "  md5 want=$WANT_MD5 got=$GOT"
[ "$GOT" != "$WANT_MD5" ] && { echo "FATAL: landed md5 mismatch"; exit 1; }

echo "### 4. start via BT panel (keeps accounting) ###"
cd /www/server/panel && /www/server/panel/pyenv/bin/python3 -c "
import sys
for p in ('/www/server/panel','/www/server/panel/class'): sys.path.insert(0,p)
import public
from projectModel import goModel
d = public.dict_obj(); d.project_name = 'VoxLink'
try:
    print('  restart_project ->', str(goModel.main().restart_project(d))[:160])
except Exception as e:
    print('  panel restart FAILED:', type(e).__name__, str(e)[:120])
"
sleep 8
NEW=$(listener_pid)
if [ -z "$NEW" ]; then
  echo "  panel did not bring it up -> nohup fallback"
  su -s /bin/bash www -c "cd $SN && nohup ./voxlink-server-linux-amd64 >> /www/wwwlogs/go/VoxLink.log 2>&1 < /dev/null & "
  sleep 8
  NEW=$(listener_pid)
  [ -n "$NEW" ] && echo "$NEW" > $PIDF && echo "  pid file synced -> $NEW"
fi
[ -z "$NEW" ] && { echo "FATAL: not listening"; tail -25 /www/wwwlogs/go/VoxLink.log; exit 1; }
echo "  new pid=$NEW  exe md5=$(md5sum /proc/$NEW/exe | cut -d' ' -f1)  pidfile=$(cat $PIDF 2>/dev/null)"

echo "### 5. verify feature live ###"
sleep 6
stats; echo
AP=$(grep -c 'panic:' /www/wwwlogs/go/VoxLink.log 2>/dev/null); echo "  panic after=$AP (was $BP)"
echo "  tickets/feedback dirs: $(ls -1 $SN/data/tickets 2>/dev/null | wc -l) / $(ls -1 $SN/data/feedback 2>/dev/null | wc -l)"
IDX=$(curl -s -m 10 http://127.0.0.1:1145/ | wc -c)
echo "  index bytes=$IDX  created_desc=$(curl -s -m 10 http://127.0.0.1:1145/ | grep -c 'created_desc')  hlFilterBtn=$(curl -s -m 10 http://127.0.0.1:1145/ | grep -c 'hlFilterBtn')"
for a in app.js lobby-live.js style.css; do
  echo "  /assets/$a -> $(curl -s -o /dev/null -w '%{http_code}' -m 8 http://127.0.0.1:1145/assets/$a)"
done
curl -s -m 10 "http://127.0.0.1:1145/?route=/room/list&page=1&size=20&clientType=web" > /tmp/list_web.json
echo "  web rows json head: $(head -c 120 /tmp/list_web.json)"
echo "  createdAt present: $(grep -c 'createdAt' /tmp/list_web.json)"
tail -4 /www/wwwlogs/go/VoxLink.log
echo
echo "rollback: cp -a $SN/voxlink-server-linux-amd64.bak-$TS $SN/voxlink-server-linux-amd64 && rerun steps 2/4"
