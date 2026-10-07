"""Pretends to be BeamNG to test the Minecraft side end to end.

Usage: python tools/fake_beamng.py [seconds]

Once Minecraft is in a world it: builds flat ground at y=60 around (20000, 20000), teleports Steve
onto it, walks forward, places a stone block, hits a pretend car with a fist and a diamond sword,
then breaks the block again.
"""
import socket
import sys
import time

MC_PORT, BNG_PORT = 47820, 47821
CX, CZ, TOP = 20000, 20000, 60
duration = float(sys.argv[1]) if len(sys.argv) > 1 else 90

sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.bind(("127.0.0.1", BNG_PORT))
sock.settimeout(0.02)


def send(*lines):
    sock.sendto(("\n".join(lines) + "\n").encode(), ("127.0.0.1", MC_PORT))


start = time.time()
last_hello = 0
st = None
seen = {}
inp = "in 0 0 0 0 1"


def recv_all():
    global st
    while True:
        try:
            data, _ = sock.recvfrom(65535)
        except (socket.timeout, ConnectionResetError):
            return
        for line in data.decode().splitlines():
            kind = line.split(" ")[0]
            seen[kind] = seen.get(kind, 0) + 1
            if kind == "st":
                st = [float(v) for v in line.split(" ")[1:6]]
            elif kind in ("blk", "hit", "respawned", "hb") or seen[kind] <= 2:
                print(f"{time.time() - start:6.1f}s <- {line}")
            if kind == "respawned":
                send(f"tp {CX + 0.5} {TOP + 0.05} {CZ + 0.5} 0")


def wait(seconds):
    end = time.time() + seconds
    while time.time() < end:
        send(inp)
        recv_all()
        time.sleep(0.02)


def say(text):
    print(f"{time.time() - start:6.1f}s -- {text}")


while st is None and time.time() - start < duration:
    if time.time() - last_hello > 1:
        send("hello")
        last_hello = time.time()
    recv_all()
    time.sleep(0.05)
if st is None:
    sys.exit("Minecraft never reached a world")

say(f"in world at {st[:3]}; mirroring ground and teleporting")
cols = [f"{CX + dx} {CZ + dz} {TOP}" for dx in range(-12, 13) for dz in range(-12, 13)]
for i in range(0, len(cols), 150):
    send("terrain " + " ".join(cols[i:i + 150]))
send(f"tp {CX + 0.5} {TOP + 0.05} {CZ + 0.5} 0")
wait(2)
say(f"landed at {st[:3]} (expect y={TOP})")

inp = "in 0 0 1 0 1"
wait(1)
inp = "in 0 0 0 0 1"
wait(0.5)
say(f"walked to {st[:3]} (expect z larger, y={TOP})")

send("cmd /clear @s", "cmd /give @s stone 64", "cmd /give @s diamond_sword", "press slot 0")
wait(1)
inp = "in 0 45 0 0 1"  # look down in front of the feet
wait(0.5)
send("press use")
say("placing stone (expect a blk line)")
wait(1.5)

send("press slot 2")
inp = "in 0 0 0 1 1"  # a car under the crosshair
wait(1.2)
send("press attack")
say("fist hit on car (expect hit ~1)")
wait(1.2)

send("press slot 1")
wait(1.5)
send("press attack")
say("diamond sword hit on car (expect hit ~7)")
wait(1)

send("press slot 2")
inp = "in 0 45 0 0 1"
wait(0.5)
send("press attack")
say("breaking the stone (expect blk ... -1)")
wait(1.5)
print("message counts:", seen)
