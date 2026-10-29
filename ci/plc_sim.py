"""机上試験用の模擬PLC（R04CPUのシンプルCPU通信の代わり）。

同じWi-FiのPCから、スマホ（木材角度判別アプリ）に3Eフレームで
  書込み: 要求番号 → スマホの D0
  読出し: スマホの D100〜D108 → 結果
を200msごとに行い、数秒ごとに撮影要求を出して結果を表示する。

使い方:  python plc_sim.py <スマホのIP> [ポート=5000] [要求の間隔秒=3]
※ アプリの設定で「PLCのIPアドレス」を入れていると、このPCからは接続できません（空欄にして試す）
"""
import socket
import struct
import sys
import time

host = sys.argv[1]
port = int(sys.argv[2]) if len(sys.argv) > 2 else 5000
interval = float(sys.argv[3]) if len(sys.argv) > 3 else 3.0
RESULT = {0: "-", 1: "判別OK", 2: "NG", 3: "カメラ異常"}
DIRECTION = {0: "不明", 1: "下(OK)", 2: "上", 3: "左", 4: "右"}


def frame(cmd, sub, data):
    body = struct.pack("<HHH", 0x0010, cmd, sub) + data
    return bytes([0x50, 0, 0, 0xFF, 0xFF, 0x03, 0]) + struct.pack("<H", len(body)) + body


def head(no, code, n):
    return struct.pack("<I", no)[:3] + bytes([code]) + struct.pack("<H", n)


def call(s, req):
    s.sendall(req)
    h = b""
    while len(h) < 9:
        c = s.recv(9 - len(h))
        if not c:
            raise ConnectionError("切断されました")
        h += c
    ln = struct.unpack("<H", h[7:9])[0]
    b = b""
    while len(b) < ln:
        c = s.recv(ln - len(b))
        if not c:
            raise ConnectionError("切断されました")
        b += c
    end = struct.unpack("<H", b[:2])[0]
    if end != 0:
        raise RuntimeError("終了コード %04X" % end)
    return b[2:]


s = socket.create_connection((host, port), timeout=3)
print("接続しました", host, port)
req = 0
sent_at = 0.0
next_req = time.time() + 1
waiting = False
while True:
    now = time.time()
    if not waiting and now >= next_req:
        req = req + 1 if req < 32767 else 1
        sent_at = now
        waiting = True
    # PLC → スマホ（書込み）
    call(s, frame(0x1401, 0, head(0, 0xA8, 1) + struct.pack("<H", req)))
    # スマホ → PLC（読出し）
    d = struct.unpack("<9h", call(s, frame(0x0401, 0, head(100, 0xA8, 9))))
    done, res, dr, ang, cx, cy, conf, hb, ms = d
    if waiting and done == req:
        print("要求%5d  %-6s 平らな面 %-6s 角度 %+6.1f°  信頼度 %3d  スマホ処理 %4dms  往復 %4.0fms"
              % (req, RESULT.get(res, res), DIRECTION.get(dr, dr), ang / 10, conf, ms, (time.time() - sent_at) * 1000))
        waiting = False
        next_req = time.time() + interval
    elif waiting and now - sent_at > 5:
        print("要求%5d  タイムアウト（5秒）" % req)
        waiting = False
        next_req = time.time() + interval
    time.sleep(0.2)
