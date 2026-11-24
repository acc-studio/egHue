#!/usr/bin/env python3
"""
A stand-in Hue Bridge for developing and testing egHue without real hardware.

Implements the parts of the Hue CLIP v2 API that egHue uses, over HTTPS with a
self-signed certificate whose CN is the bridge id (like a real bridge):

  GET  /api/config                    bridge identity (unauthenticated)
  POST /api                           pairing; "press the link button" by typing
                                      `press` in this console, or start with --auto-press
  GET  /clip/v2/resource[/type[/id]]  state
  PUT  /clip/v2/resource/type/id      commands (light, grouped_light, scene recall)
  GET  /eventstream/clip/v2           Server-Sent Events with live updates

Usage:  python tools/fake_bridge.py [--port 8443] [--auto-press]
From the Android emulator, connect to 10.0.2.2:8443 ("Enter IP address instead").
"""
import argparse
import copy
import json
import os
import queue
import ssl
import subprocess
import sys
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

BRIDGE_ID = "001788FFFE4E6A01"
APP_KEY = "fake-app-key-0123456789"
HERE = os.path.dirname(os.path.abspath(__file__))
CERT_DIR = os.path.join(HERE, "fake-bridge-cert")

state = {}
lock = threading.Lock()
subscribers = []
link_pressed_at = 0.0
auto_press = False


def rid():
    return str(uuid.uuid4())


def build_state():
    res = []

    def add(o):
        res.append(o)
        state[o["id"]] = o
        return o

    lights = {}

    def light(name, archetype, on, bri, kind):
        dev_id, light_id, zb_id = rid(), rid(), rid()
        add({"id": dev_id, "type": "device", "metadata": {"name": name, "archetype": archetype},
             "product_data": {"model_id": "LCA001"},
             "services": [{"rid": light_id, "rtype": "light"}, {"rid": zb_id, "rtype": "zigbee_connectivity"}]})
        add({"id": zb_id, "type": "zigbee_connectivity", "owner": {"rid": dev_id, "rtype": "device"}, "status": "connected"})
        o = {"id": light_id, "type": "light", "owner": {"rid": dev_id, "rtype": "device"},
             "metadata": {"name": name, "archetype": archetype}, "on": {"on": on},
             "dimming": {"brightness": float(bri), "min_dim_level": 2.0}}
        if kind in ("color", "ct"):
            o["color_temperature"] = {"mirek": 366, "mirek_valid": True,
                                      "mirek_schema": {"mirek_minimum": 153, "mirek_maximum": 500}}
        if kind == "color":
            o["color"] = {"xy": {"x": 0.4573, "y": 0.41}, "gamut_type": "C",
                          "gamut": {"red": {"x": 0.6915, "y": 0.3083}, "green": {"x": 0.17, "y": 0.7},
                                    "blue": {"x": 0.1532, "y": 0.0475}}}
        add(o)
        lights[name] = (dev_id, light_id)
        return dev_id, light_id

    light("Sofa lamp", "sultan_bulb", True, 70, "color")
    light("Ceiling", "ceiling_round", True, 100, "ct")
    light("Floor lamp", "floor_shade", False, 40, "color")
    light("Pendant", "pendant_round", True, 55, "ct")
    light("Counter strip", "hue_lightstrip", False, 80, "color")
    light("Bedside", "table_shade", False, 30, "color")
    light("Wardrobe", "classic_bulb", False, 100, "dim")
    light("Hallway", "spot_bulb", True, 20, "ct")

    def group(kind, name, archetype, member_names, by_device=True):
        gid, glid = rid(), rid()
        if by_device:
            children = [{"rid": lights[n][0], "rtype": "device"} for n in member_names]
        else:
            children = [{"rid": lights[n][1], "rtype": "light"} for n in member_names]
        add({"id": gid, "type": kind, "metadata": {"name": name, "archetype": archetype},
             "children": children, "services": [{"rid": glid, "rtype": "grouped_light"}]})
        add({"id": glid, "type": "grouped_light", "owner": {"rid": gid, "rtype": kind},
             "on": {"on": False}, "dimming": {"brightness": 0.0}})
        return gid

    living = group("room", "Living room", "living_room", ["Sofa lamp", "Ceiling", "Floor lamp"])
    kitchen = group("room", "Kitchen", "kitchen", ["Pendant", "Counter strip"])
    bedroom = group("room", "Bedroom", "bedroom", ["Bedside", "Wardrobe"])
    group("room", "Hallway", "hallway", ["Hallway"])
    movie = group("zone", "Movie night", "tv", ["Sofa lamp", "Floor lamp", "Counter strip"], by_device=False)

    home_gl = rid()
    add({"id": rid(), "type": "bridge_home", "children": [], "services": [{"rid": home_gl, "rtype": "grouped_light"}]})
    add({"id": home_gl, "type": "grouped_light", "owner": {"rid": "home", "rtype": "bridge_home"},
         "on": {"on": True}, "dimming": {"brightness": 50.0}})

    def scene(name, group_id, member_names, action_for):
        actions = []
        for i, n in enumerate(member_names):
            actions.append({"target": {"rid": lights[n][1], "rtype": "light"}, "action": action_for(i)})
        add({"id": rid(), "type": "scene", "metadata": {"name": name},
             "group": {"rid": group_id, "rtype": state[group_id]["type"]},
             "actions": actions, "status": {"active": "inactive"}, "speed": 0.6})

    lr = ["Sofa lamp", "Ceiling", "Floor lamp"]
    scene("Relax", living, lr, lambda i: {"on": {"on": True}, "dimming": {"brightness": 56.0}, "color_temperature": {"mirek": 447}})
    scene("Concentrate", living, lr, lambda i: {"on": {"on": True}, "dimming": {"brightness": 100.0}, "color_temperature": {"mirek": 233}})
    scene("Savanna sunset", living, ["Sofa lamp", "Floor lamp"],
          lambda i: {"on": {"on": True}, "dimming": {"brightness": 80.0}, "color": {"xy": [{"x": 0.58, "y": 0.38}, {"x": 0.52, "y": 0.44}][i]}})
    scene("Tropical twilight", living, ["Sofa lamp", "Floor lamp"],
          lambda i: {"on": {"on": True}, "dimming": {"brightness": 70.0}, "color": {"xy": [{"x": 0.21, "y": 0.12}, {"x": 0.39, "y": 0.18}][i]}})
    scene("Nightlight", living, lr, lambda i: {"on": {"on": i == 0}, "dimming": {"brightness": 1.0}, "color_temperature": {"mirek": 500}})
    scene("Bright", kitchen, ["Pendant", "Counter strip"], lambda i: {"on": {"on": True}, "dimming": {"brightness": 100.0}, "color_temperature": {"mirek": 233}})
    scene("Dimmed", kitchen, ["Pendant", "Counter strip"], lambda i: {"on": {"on": True}, "dimming": {"brightness": 30.0}, "color_temperature": {"mirek": 400}})
    scene("Read", bedroom, ["Bedside", "Wardrobe"], lambda i: {"on": {"on": True}, "dimming": {"brightness": 80.0}})
    scene("Cinema", movie, ["Sofa lamp", "Floor lamp", "Counter strip"],
          lambda i: {"on": {"on": True}, "dimming": {"brightness": 25.0}, "color": {"xy": [{"x": 0.16, "y": 0.1}, {"x": 0.3, "y": 0.14}, {"x": 0.17, "y": 0.06}][i]}})
    for gl in [o for o in res if o["type"] == "grouped_light"]:
        recompute_group(gl["id"])


def lights_of_group(owner_id):
    if owner_id == "home":
        return [o for o in state.values() if o["type"] == "light"]
    g = state.get(owner_id)
    if not g:
        return []
    out = []
    for c in g.get("children", []):
        if c["rtype"] == "light":
            out.append(state[c["rid"]])
        elif c["rtype"] == "device":
            for s in state[c["rid"]]["services"]:
                if s["rtype"] == "light":
                    out.append(state[s["rid"]])
    return out


def recompute_group(gl_id):
    gl = state[gl_id]
    members = lights_of_group(gl["owner"]["rid"])
    on = [l for l in members if l["on"]["on"]]
    gl["on"] = {"on": bool(on)}
    gl["dimming"] = {"brightness": round(sum(l["dimming"]["brightness"] for l in on) / len(on), 1) if on else 0.0}


def merge(base, patch):
    for k, v in patch.items():
        if isinstance(v, dict) and isinstance(base.get(k), dict):
            merge(base[k], v)
        else:
            base[k] = copy.deepcopy(v)


def apply_light(light, body):
    changed = {}
    if "on" in body:
        light["on"] = {"on": bool(body["on"]["on"])}
        changed["on"] = light["on"]
    if "dimming" in body and "dimming" in light:
        light["dimming"]["brightness"] = max(1.0, min(100.0, float(body["dimming"]["brightness"])))
        changed["dimming"] = {"brightness": light["dimming"]["brightness"]}
    if "color_temperature" in body and "color_temperature" in light and body["color_temperature"].get("mirek"):
        light["color_temperature"]["mirek"] = int(body["color_temperature"]["mirek"])
        light["color_temperature"]["mirek_valid"] = True
        changed["color_temperature"] = {"mirek": light["color_temperature"]["mirek"], "mirek_valid": True}
    if "color" in body and "color" in light and "xy" in body["color"]:
        light["color"]["xy"] = body["color"]["xy"]
        changed["color"] = {"xy": light["color"]["xy"]}
        if "color_temperature" in light:
            light["color_temperature"]["mirek"] = None
            light["color_temperature"]["mirek_valid"] = False
            changed["color_temperature"] = {"mirek": None, "mirek_valid": False}
    return changed


def event(updates):
    if not updates:
        return
    payload = [{"creationtime": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "id": rid(),
                "type": "update", "data": updates}]
    for q in list(subscribers):
        q.put(payload)


def handle_put(rtype, rid_, body):
    updates = []
    affected_groups = set()
    if rtype == "light":
        l = state[rid_]
        ch = apply_light(l, body)
        if ch:
            updates.append({"id": rid_, "type": "light", "owner": l["owner"], **ch})
        affected_groups = {g["id"] for g in state.values() if g["type"] == "grouped_light"}
    elif rtype == "grouped_light":
        gl = state[rid_]
        for l in lights_of_group(gl["owner"]["rid"]):
            ch = apply_light(l, body)
            if ch:
                updates.append({"id": l["id"], "type": "light", "owner": l["owner"], **ch})
        affected_groups = {g["id"] for g in state.values() if g["type"] == "grouped_light"}
    elif rtype == "scene":
        s = state[rid_]
        if body.get("recall"):
            for a in s["actions"]:
                l = state[a["target"]["rid"]]
                ch = apply_light(l, a["action"])
                if ch:
                    updates.append({"id": l["id"], "type": "light", "owner": l["owner"], **ch})
            for other in state.values():
                if other["type"] == "scene" and other["group"]["rid"] == s["group"]["rid"]:
                    new = "static" if other["id"] == rid_ else "inactive"
                    if other["status"]["active"] != new:
                        other["status"]["active"] = new
                        updates.append({"id": other["id"], "type": "scene", "status": {"active": new}})
            affected_groups = {g["id"] for g in state.values() if g["type"] == "grouped_light"}
    for gid in affected_groups:
        before = json.dumps([state[gid]["on"], state[gid]["dimming"]])
        recompute_group(gid)
        if json.dumps([state[gid]["on"], state[gid]["dimming"]]) != before:
            updates.append({"id": gid, "type": "grouped_light", "on": state[gid]["on"], "dimming": state[gid]["dimming"]})
    event(updates)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stderr.write("%s %s\n" % (time.strftime("%H:%M:%S"), fmt % args))

    def send_json(self, obj, code=200):
        data = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def authed(self):
        if self.headers.get("hue-application-key") != APP_KEY:
            self.send_json({"errors": [{"description": "unauthorized user"}], "data": []}, 403)
            return False
        return True

    def body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(n) or b"{}")

    def do_GET(self):
        p = self.path.split("?")[0].rstrip("/")
        if p == "/api/config":
            return self.send_json({"name": "Fake Hue Bridge", "bridgeid": BRIDGE_ID, "modelid": "BSB002",
                                   "apiversion": "1.67.0", "swversion": "1967054020", "datastoreversion": "172",
                                   "mac": "00:17:88:4e:6a:01", "factorynew": False})
        if p == "/eventstream/clip/v2":
            if not self.authed():
                return
            return self.stream()
        if p.startswith("/clip/v2/resource"):
            if not self.authed():
                return
            parts = p.split("/")[4:]
            with lock:
                if not parts:
                    data = list(state.values())
                elif len(parts) == 1:
                    data = [o for o in state.values() if o["type"] == parts[0]]
                else:
                    data = [state[parts[1]]] if parts[1] in state else []
                return self.send_json({"errors": [], "data": copy.deepcopy(data)})
        self.send_json({"errors": [{"description": "not found"}]}, 404)

    def do_POST(self):
        if self.path.rstrip("/") == "/api":
            self.body()
            if auto_press or time.time() - link_pressed_at < 30:
                return self.send_json([{"success": {"username": APP_KEY, "clientkey": "00112233445566778899AABBCCDDEEFF"}}])
            return self.send_json([{"error": {"type": 101, "address": "", "description": "link button not pressed"}}])
        self.send_json({"errors": [{"description": "not found"}]}, 404)

    def do_PUT(self):
        parts = self.path.rstrip("/").split("/")
        if len(parts) == 6 and parts[1] == "clip" and self.authed():
            rtype, rid_ = parts[4], parts[5]
            body = self.body()
            with lock:
                if rid_ not in state:
                    return self.send_json({"errors": [{"description": "not found"}], "data": []}, 404)
                handle_put(rtype, rid_, body)
            return self.send_json({"errors": [], "data": [{"rid": rid_, "rtype": rtype}]})
        self.send_json({"errors": [{"description": "not found"}]}, 404)

    def stream(self):
        q = queue.Queue()
        subscribers.append(q)
        try:
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-cache")
            self.end_headers()
            self.wfile.write(b": hi\n\n")
            self.wfile.flush()
            while True:
                try:
                    payload = q.get(timeout=20)
                    self.wfile.write(("id: %d:0\ndata: %s\n\n" % (int(time.time()), json.dumps(payload))).encode())
                except queue.Empty:
                    self.wfile.write(b": keepalive\n\n")
                self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError, ssl.SSLError, OSError):
            pass
        finally:
            subscribers.remove(q)


def ensure_cert():
    os.makedirs(CERT_DIR, exist_ok=True)
    cert, key = os.path.join(CERT_DIR, "cert.pem"), os.path.join(CERT_DIR, "key.pem")
    if not (os.path.exists(cert) and os.path.exists(key)):
        subprocess.check_call(["openssl", "req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1",
                               "-nodes", "-keyout", key, "-out", cert, "-days", "3650",
                               "-subj", "/C=NL/O=Philips Hue/CN=%s" % BRIDGE_ID.lower()])
    return cert, key


def console():
    global link_pressed_at
    for line in sys.stdin:
        cmd = line.strip().lower()
        if cmd == "press":
            link_pressed_at = time.time()
            print("Link button pressed (30 s window)")
        elif cmd.startswith("toggle "):
            # Simulate another app changing a light: `toggle Sofa lamp`
            name = line.strip()[7:]
            with lock:
                for o in state.values():
                    if o["type"] == "light" and o["metadata"]["name"].lower() == name.lower():
                        handle_put("light", o["id"], {"on": {"on": not o["on"]["on"]}})
                        print("toggled", name)


def main():
    global auto_press
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8443)
    ap.add_argument("--auto-press", action="store_true", help="accept pairing without pressing the button")
    args = ap.parse_args()
    auto_press = args.auto_press
    build_state()
    cert, key = ensure_cert()
    httpd = ThreadingHTTPServer(("0.0.0.0", args.port), Handler)
    httpd.daemon_threads = True
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(cert, key)
    httpd.socket = ctx.wrap_socket(httpd.socket, server_side=True)
    threading.Thread(target=console, daemon=True).start()
    print("Fake Hue Bridge %s on https://0.0.0.0:%d  (type 'press' to press the link button)" % (BRIDGE_ID, args.port), flush=True)
    httpd.serve_forever()


if __name__ == "__main__":
    main()
