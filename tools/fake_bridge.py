#!/usr/bin/env python3
"""
A stand-in Hue Bridge for developing and testing egHue without real hardware.

Implements the parts of the Hue CLIP v2 API that egHue uses, over HTTPS with a
self-signed certificate whose CN is the bridge id (like a real bridge):

  GET  /api/config                    bridge identity (unauthenticated)
  POST /api                           pairing; "press the link button" by typing
                                      `press` in this console, or start with --auto-press
  GET  /clip/v2/resource[/type[/id]]  state
  POST /clip/v2/resource/scene        create a scene
  PUT  /clip/v2/resource/type/id      commands (light, grouped_light, scene recall/update)
  DELETE /clip/v2/resource/scene/id   delete a scene
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
    counter = {"light": 0, "group": 80}

    def light(name, archetype, on, bri, kind, powerup=None):
        dev_id, light_id, zb_id = rid(), rid(), rid()
        counter["light"] += 1
        v1 = "/lights/%d" % counter["light"]
        add({"id": dev_id, "type": "device", "id_v1": v1, "metadata": {"name": name, "archetype": archetype},
             "product_data": {"model_id": "LCA001"},
             "services": [{"rid": light_id, "rtype": "light"}, {"rid": zb_id, "rtype": "zigbee_connectivity"}]})
        add({"id": zb_id, "type": "zigbee_connectivity", "owner": {"rid": dev_id, "rtype": "device"}, "status": "connected"})
        o = {"id": light_id, "type": "light", "id_v1": v1, "owner": {"rid": dev_id, "rtype": "device"},
             "metadata": {"name": name, "archetype": archetype}, "on": {"on": on},
             "dimming": {"brightness": float(bri), "min_dim_level": 2.0}}
        o["powerup"] = powerup_state(powerup or {"preset": "safety"}, kind)
        if kind in ("color", "ct"):
            o["color_temperature"] = {"mirek": 366, "mirek_valid": True,
                                      "mirek_schema": {"mirek_minimum": 153, "mirek_maximum": 500}}
        if kind == "color":
            o["color"] = {"xy": {"x": 0.4573, "y": 0.41}, "gamut_type": "C",
                          "gamut": {"red": {"x": 0.6915, "y": 0.3083}, "green": {"x": 0.17, "y": 0.7},
                                    "blue": {"x": 0.1532, "y": 0.0475}}}
            o["effects"] = {"status": "no_effect", "status_values": ["no_effect", "candle", "fire", "prism", "sparkle", "opal", "glisten"],
                            "effect_values": ["no_effect", "candle", "fire", "prism", "sparkle", "opal", "glisten"]}
        add(o)
        lights[name] = (dev_id, light_id)
        return dev_id, light_id

    light("Sofa lamp", "sultan_bulb", True, 70, "color", {"preset": "last_on_state"})
    light("Ceiling", "ceiling_round", True, 100, "ct", {
        "preset": "custom", "on": {"mode": "on", "on": {"on": True}},
        "dimming": {"mode": "dimming", "dimming": {"brightness": 65.2}},
        "color": {"mode": "color_temperature", "color_temperature": {"mirek": 428}}})
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
        counter["group"] += 1
        v1 = "/groups/%d" % counter["group"]
        add({"id": gid, "type": kind, "id_v1": v1, "metadata": {"name": name, "archetype": archetype},
             "children": children, "services": [{"rid": glid, "rtype": "grouped_light"}]})
        add({"id": glid, "type": "grouped_light", "id_v1": v1, "owner": {"rid": gid, "rtype": kind},
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
        add({"id": rid(), "type": "scene", "id_v1": "/scenes/" + uuid.uuid4().hex[:16], "metadata": {"name": name},
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

    # A Hue motion sensor (SML001) in the hallway.
    dev_id, zb_id, pw_id, mo_id, te_id, ll_id = rid(), rid(), rid(), rid(), rid(), rid()
    add({"id": dev_id, "type": "device", "id_v1": "/sensors/5", "metadata": {"name": "Hallway sensor", "archetype": "unknown_archetype"},
         "product_data": {"model_id": "SML001", "product_name": "Hue motion sensor"},
         "services": [{"rid": zb_id, "rtype": "zigbee_connectivity"}, {"rid": pw_id, "rtype": "device_power"},
                      {"rid": mo_id, "rtype": "motion"}, {"rid": te_id, "rtype": "temperature"}, {"rid": ll_id, "rtype": "light_level"}]})
    add({"id": zb_id, "type": "zigbee_connectivity", "owner": {"rid": dev_id, "rtype": "device"}, "status": "connected"})
    add({"id": pw_id, "type": "device_power", "owner": {"rid": dev_id, "rtype": "device"}, "power_state": {"battery_level": 64}})
    add({"id": mo_id, "type": "motion", "id_v1": "/sensors/5", "owner": {"rid": dev_id, "rtype": "device"}, "enabled": True,
         "motion": {"motion": False, "motion_valid": True, "motion_report": {"changed": "2026-11-24T20:01:00.000Z", "motion": False}}})
    add({"id": te_id, "type": "temperature", "owner": {"rid": dev_id, "rtype": "device"}, "enabled": True,
         "temperature": {"temperature": 21.37, "temperature_valid": True}})
    add({"id": ll_id, "type": "light_level", "id_v1": "/sensors/6", "owner": {"rid": dev_id, "rtype": "device"}, "enabled": True,
         "light": {"light_level": 18000, "light_level_valid": True}})
    bridge_dev = rid()
    add({"id": bridge_dev, "type": "device", "metadata": {"name": "Fake Hue Bridge", "archetype": "bridge_v2"},
         "product_data": {"model_id": "BSB002", "product_name": "Hue Bridge", "software_version": "1.67.1967054020"},
         "services": []})
    add({"id": rid(), "type": "bridge", "owner": {"rid": bridge_dev, "rtype": "device"}, "bridge_id": BRIDGE_ID.lower()})

    # A Hue dimmer switch (RWL022) with the factory button setup, as v1 rules on the living room.
    dev_id, zb_id, pw_id = rid(), rid(), rid()
    button_ids = [rid() for _ in range(4)]
    add({"id": dev_id, "type": "device", "id_v1": "/sensors/2", "metadata": {"name": "Living room dimmer", "archetype": "unknown_archetype"},
         "product_data": {"model_id": "RWL022", "product_name": "Hue dimmer switch", "manufacturer_name": "Signify Netherlands B.V."},
         "services": [{"rid": zb_id, "rtype": "zigbee_connectivity"}] + [{"rid": b, "rtype": "button"} for b in button_ids]
         + [{"rid": pw_id, "rtype": "device_power"}]})
    add({"id": zb_id, "type": "zigbee_connectivity", "owner": {"rid": dev_id, "rtype": "device"}, "status": "connected"})
    add({"id": pw_id, "type": "device_power", "owner": {"rid": dev_id, "rtype": "device"},
         "power_state": {"battery_state": "normal", "battery_level": 87}})
    for i, b in enumerate(button_ids):
        add({"id": b, "type": "button", "id_v1": "/sensors/2", "owner": {"rid": dev_id, "rtype": "device"},
             "metadata": {"control_id": i + 1}, "button": {"event_values": ["initial_press", "repeat", "short_release", "long_release", "long_press"]}})
    v1_sensors["2"] = {"name": "Living room dimmer", "type": "ZLLSwitch", "modelid": "RWL022",
                       "state": {"buttonevent": 1002, "lastupdated": "none"}}
    v1_sensors["4"] = {"name": "Dimmer switch hue-press", "type": "CLIPGenericStatus", "modelid": "Automation MultiplePresses Event",
                       "state": {"status": 0, "lastupdated": "none"}}
    lg = state[living]["id_v1"]
    with open(os.path.join(HERE, "..", "app", "src", "test", "resources", "factory_dimmer_rules.json"), encoding="utf-8") as f:
        factory = json.load(f)
    for k, r in factory.items():
        v1_rules[k] = json.loads(json.dumps(r).replace("/groups/81/", lg + "/"))
    next_id["n"] = 100


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
    if "effects" in body and "effects" in light:
        light["effects"]["status"] = body["effects"].get("effect", "no_effect")
        changed["effects"] = {"status": light["effects"]["status"]}
    if "metadata" in body:
        light["metadata"].update(body["metadata"])
        changed["metadata"] = light["metadata"]
    if "powerup" in body:
        kind = "color" if "color" in light else ("ct" if "color_temperature" in light else "dim")
        light["powerup"] = powerup_state(body["powerup"], kind)
        changed["powerup"] = light["powerup"]
    return changed


def powerup_state(p, kind):
    """Expands a powerup preset the way the bridge reports it."""
    preset = p.get("preset", "safety")
    if preset == "custom":
        out = {"preset": "custom", "configured": True, "on": p.get("on", {"mode": "on", "on": {"on": True}}),
               "dimming": p.get("dimming", {"mode": "previous"})}
        if kind != "dim":
            out["color"] = p.get("color", {"mode": "previous"})
        return out
    if preset == "safety":
        out = {"preset": "safety", "configured": True, "on": {"mode": "on", "on": {"on": True}},
               "dimming": {"mode": "dimming", "dimming": {"brightness": 100.0}}}
        if kind != "dim":
            out["color"] = {"mode": "color_temperature", "color_temperature": {"mirek": 366}}
        return out
    out = {"preset": preset, "configured": True,
           "on": {"mode": "previous"} if preset == "powerfail" else {"mode": "on", "on": {"on": True}},
           "dimming": {"mode": "previous"}}
    if kind != "dim":
        out["color"] = {"mode": "previous"}
    return out


# ---- v1 API: sensors and rules, with a small rule engine ----------------------------------

v1_sensors = {}
v1_rules = {}
v1_schedules = {}
next_id = {"n": 100}


def v1_light(path):
    for o in state.values():
        if o["type"] == "light" and o.get("id_v1") == path:
            return o


def v1_group_lights(path):
    if path == "/groups/0":
        return [o for o in state.values() if o["type"] == "light"]
    for o in state.values():
        if o["type"] in ("room", "zone") and o.get("id_v1") == path:
            return lights_of_group(o["id"])
    return []


def v1_to_v2(body, light=None):
    out = {}
    if "on" in body:
        out["on"] = {"on": bool(body["on"])}
    if "bri" in body:
        out["dimming"] = {"brightness": round(body["bri"] / 2.54, 1)}
    if "bri_inc" in body and light is not None:
        out["dimming"] = {"brightness": max(1.0, min(100.0, light["dimming"]["brightness"] + body["bri_inc"] / 2.54))}
    if "ct" in body:
        out["color_temperature"] = {"mirek": body["ct"]}
    if "xy" in body:
        out["color"] = {"xy": {"x": body["xy"][0], "y": body["xy"][1]}}
    return out


def run_action(a):
    addr, body = a["address"], a.get("body", {})
    if addr.startswith("/sensors/") and addr.endswith("/state"):
        sid = addr.split("/")[2]
        if sid in v1_sensors:
            v1_sensors[sid]["state"].update(body)
        return
    if addr.startswith("/lights/") and addr.endswith("/state"):
        l = v1_light(addr[:-len("/state")])
        if l:
            handle_put("light", l["id"], v1_to_v2(body, l))
        return
    if addr.startswith("/groups/") and addr.endswith("/action"):
        g = addr[:-len("/action")]
        if "scene" in body:
            for s in state.values():
                if s["type"] == "scene" and s.get("id_v1") == "/scenes/" + body["scene"]:
                    handle_put("scene", s["id"], {"recall": {"action": "active"}})
            return
        for l in v1_group_lights(g):
            handle_put("light", l["id"], v1_to_v2(body, l))


def check(c, sensor_id, event_code):
    addr, op, val = c["address"], c["operator"], c.get("value")
    if addr == "/sensors/%s/state/buttonevent" % sensor_id:
        return op == "eq" and int(val) == event_code
    if addr == "/sensors/%s/state/lastupdated" % sensor_id:
        return op == "dx"
    if addr.endswith("/state/lastupdated"):
        return False  # timers (ddx) on other sensors don't fire on a press
    if addr.endswith("/state/status"):
        sid = addr.split("/")[2]
        return op == "eq" and str(v1_sensors.get(sid, {}).get("state", {}).get("status")) == str(val)
    if addr.endswith("/state/any_on"):
        any_on = any(l["on"]["on"] for l in v1_group_lights(addr[:-len("/state/any_on")]))
        return op == "eq" and str(any_on).lower() == str(val).lower()
    return False


def press(sensor_id, event_code):
    """Simulates a switch event and runs the matching rules (conditions first, then actions)."""
    v1_sensors[sensor_id]["state"]["buttonevent"] = event_code
    v1_sensors[sensor_id]["state"]["lastupdated"] = time.strftime("%Y-%m-%dT%H:%M:%S")
    fired = [r for r in v1_rules.values()
             if r.get("status") == "enabled" and r["conditions"] and all(check(c, sensor_id, event_code) for c in r["conditions"])]
    for r in fired:
        for a in r["actions"]:
            run_action(a)
    return [r["name"] for r in fired]


DELETABLE = {"scene", "zone", "behavior_instance"}


def event(updates, kind="update"):
    if not updates:
        return
    payload = [{"creationtime": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "id": rid(),
                "type": kind, "data": updates}]
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
    elif rtype in ("zone", "room"):
        z = state[rid_]
        ch = {}
        if "children" in body:
            z["children"] = body["children"]; ch["children"] = body["children"]
        if "metadata" in body:
            z["metadata"].update(body["metadata"]); ch["metadata"] = z["metadata"]
        if ch:
            updates.append({"id": rid_, "type": rtype, **ch})
        affected_groups = {s["rid"] for s in z["services"] if s["rtype"] == "grouped_light"}
    elif rtype == "device":
        d = state[rid_]
        if "identify" in body:
            print("identify", d["metadata"]["name"], flush=True)
        if "metadata" in body:
            d["metadata"].update(body["metadata"])
            updates.append({"id": rid_, "type": "device", "metadata": d["metadata"]})
    elif rtype in ("motion", "temperature", "light_level"):
        o = state[rid_]
        if "enabled" in body:
            o["enabled"] = bool(body["enabled"])
            updates.append({"id": rid_, "type": rtype, "enabled": o["enabled"]})
    elif rtype == "scene":
        s = state[rid_]
        changed = {}
        if "actions" in body:
            s["actions"] = body["actions"]; changed["actions"] = body["actions"]
        if "metadata" in body:
            merge(s["metadata"], body["metadata"]); changed["metadata"] = s["metadata"]
        if changed:
            updates.append({"id": rid_, "type": "scene", **changed})
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
        if p.startswith("/api/" + APP_KEY + "/"):
            what = p[len("/api/" + APP_KEY + "/"):]
            with lock:
                if what == "rules":
                    return self.send_json(v1_rules)
                if what == "sensors":
                    return self.send_json(v1_sensors)
                if what == "schedules":
                    return self.send_json(v1_schedules)
                if what.startswith("rules/") and what[6:] in v1_rules:
                    return self.send_json(v1_rules[what[6:]])
            return self.send_json([{"error": {"type": 3, "address": "/" + what, "description": "resource not available"}}])
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
        p = self.path.rstrip("/")
        if p == "/debug/press":
            b = self.body()
            with lock:
                fired = press(str(b["sensor"]), int(b["event"]))
            print("press", b, "->", fired, flush=True)
            return self.send_json({"fired": fired})
        if p == "/clip/v2/resource/scene":
            if not self.authed():
                return
            b = self.body()
            with lock:
                name = (b.get("metadata") or {}).get("name", "")
                group = b.get("group") or {}
                clash = any(o["type"] == "scene" and o["group"]["rid"] == group.get("rid") and o["metadata"]["name"] == name
                            for o in state.values())
                if not name or len(name) > 32 or group.get("rid") not in state or not b.get("actions") or clash:
                    return self.send_json({"errors": [{"description": "invalid scene"}], "data": []}, 400)
                sid = rid()
                scene = {"id": sid, "type": "scene", "id_v1": "/scenes/" + uuid.uuid4().hex[:16],
                         "metadata": b["metadata"], "group": group, "actions": b["actions"],
                         "status": {"active": "inactive"}}
                state[sid] = scene
                event([copy.deepcopy(scene)], kind="add")
            print("created scene", name, flush=True)
            return self.send_json({"errors": [], "data": [{"rid": sid, "rtype": "scene"}]})
        if p == "/clip/v2/resource/zone":
            if not self.authed():
                return
            b = self.body()
            with lock:
                name = (b.get("metadata") or {}).get("name", "")
                kids = b.get("children") or []
                if not name or len(name) > 32 or any(c.get("rid") not in state for c in kids):
                    return self.send_json({"errors": [{"description": "invalid zone"}], "data": []}, 400)
                gid, glid = rid(), rid()
                zone = {"id": gid, "type": "zone", "id_v1": "/groups/%d" % (200 + len(state)),
                        "metadata": {"name": name, "archetype": (b.get("metadata") or {}).get("archetype", "other")},
                        "children": kids, "services": [{"rid": glid, "rtype": "grouped_light"}]}
                gl = {"id": glid, "type": "grouped_light", "id_v1": zone["id_v1"], "owner": {"rid": gid, "rtype": "zone"},
                      "on": {"on": False}, "dimming": {"brightness": 0.0}}
                state[gid] = zone
                state[glid] = gl
                recompute_group(glid)
                event([copy.deepcopy(zone), copy.deepcopy(gl)], kind="add")
            print("created zone", name, flush=True)
            return self.send_json({"errors": [], "data": [{"rid": gid, "rtype": "zone"}]})
        if p.startswith("/api/" + APP_KEY + "/"):
            what = p[len("/api/" + APP_KEY + "/"):]
            b = self.body()
            with lock:
                next_id["n"] += 1
                new_id = str(next_id["n"])
                if what == "rules":
                    if not b.get("conditions") or not b.get("actions") or len(b.get("name", "")) > 32 or len(b["actions"]) > 8:
                        return self.send_json([{"error": {"type": 7, "address": "/rules", "description": "invalid rule"}}])
                    b.setdefault("status", "enabled")
                    v1_rules[new_id] = b
                elif what == "schedules":
                    lt = b.get("localtime", "")
                    cmd = b.get("command") or {}
                    if (len(b.get("name", "")) > 32 or len(b.get("description", "")) > 64 or not cmd.get("address")
                            or not (lt.startswith("W") or lt.startswith("PT") or lt[:2] == "20" or lt.startswith("R"))):
                        return self.send_json([{"error": {"type": 7, "address": "/schedules", "description": "invalid schedule"}}])
                    b.setdefault("status", "enabled")
                    b["created"] = time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime())
                    if lt.startswith("PT"):
                        b["starttime"] = b["created"]
                    v1_schedules[new_id] = b
                elif what == "sensors":
                    b.setdefault("state", {"status": 0})
                    v1_sensors[new_id] = b
                else:
                    return self.send_json([{"error": {"type": 3, "address": "/" + what, "description": "not available"}}])
            return self.send_json([{"success": {"id": new_id}}])
        self.send_json({"errors": [{"description": "not found"}]}, 404)

    def do_DELETE(self):
        p = self.path.rstrip("/")
        if p.startswith("/api/" + APP_KEY + "/rules/"):
            rid_ = p.rsplit("/", 1)[1]
            with lock:
                if v1_rules.pop(rid_, None) is None:
                    return self.send_json([{"error": {"type": 3, "address": "/rules/" + rid_, "description": "not available"}}])
            return self.send_json([{"success": "/rules/%s deleted" % rid_}])
        if p.startswith("/api/" + APP_KEY + "/schedules/"):
            sid = p.rsplit("/", 1)[1]
            with lock:
                if v1_schedules.pop(sid, None) is None:
                    return self.send_json([{"error": {"type": 3, "address": "/schedules/" + sid, "description": "not available"}}])
            return self.send_json([{"success": "/schedules/%s deleted" % sid}])
        parts = p.split("/")
        if len(parts) == 6 and parts[1] == "clip" and self.authed():
            rtype, rid_ = parts[4], parts[5]
            with lock:
                o = state.get(rid_)
                if o is None or o["type"] != rtype or rtype not in DELETABLE:
                    return self.send_json({"errors": [{"description": "not found"}], "data": []}, 404)
                del state[rid_]
                gone = [{"id": rid_, "type": rtype}]
                for s in o.get("services", []):
                    if state.pop(s["rid"], None) is not None:
                        gone.append({"id": s["rid"], "type": s["rtype"]})
                event(gone, "delete")
            return self.send_json({"errors": [], "data": [{"rid": rid_, "rtype": rtype}]})
        self.send_json({"errors": [{"description": "not found"}]}, 404)

    def do_PUT(self):
        p = self.path.rstrip("/")
        if p.startswith("/api/" + APP_KEY + "/schedules/"):
            sid = p.rsplit("/", 1)[1]
            b = self.body()
            with lock:
                if sid not in v1_schedules:
                    return self.send_json([{"error": {"type": 3, "address": "/schedules/" + sid, "description": "not available"}}])
                v1_schedules[sid].update(b)
            return self.send_json([{"success": {"/schedules/%s/%s" % (sid, k): v}} for k, v in b.items()])
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
