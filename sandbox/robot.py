"""Minimal client for Remote Robot's robot-server running inside the sandbox IDE.

Scripts are Rhino JavaScript executed in the IDE's JVM: Java classes are reachable by their full name,
`robot` is the server's AWT robot, and in component-scoped calls `component` is the found Swing component.
`call_js` expects the script to evaluate to a String (wrap other values in `String(...)`).

CLI:  python robot.py js "<script>" [--edt] run a script (on the EDT) and print its String result
      python robot.py find "<xpath>"        list matching components
      python robot.py screenshot out.png [--frame]   the whole screen, or just the sandbox's IDE frame
"""
from __future__ import annotations

import json
import os
import struct
import sys
import urllib.request
from pathlib import Path

BASE = f"http://127.0.0.1:{os.environ.get('MARGINALIS_ROBOT_PORT', '8082')}"


class RobotError(RuntimeError):
    pass


def _post(path: str, body: dict) -> dict:
    request = urllib.request.Request(f"{BASE}/{path}", data=json.dumps(body).encode(),
                                     headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(request, timeout=60) as response:
        result = json.loads(response.read())
    if result.get("status") != "SUCCESS":
        raise RobotError(f"{path}: {result.get('message') or result}")
    return result


def _get(path: str) -> dict:
    with urllib.request.urlopen(f"{BASE}/{path}", timeout=60) as response:
        result = json.loads(response.read())
    if result.get("status") != "SUCCESS":
        raise RobotError(f"{path}: {result.get('message') or result}")
    return result


def _java_string(raw: list[int]) -> str:
    data = bytes(b & 0xFF for b in raw)
    if data[:4] != b"\xac\xed\x00\x05":
        raise RobotError(f"not a Java-serialized value: {data[:16]!r}")
    if data[4] == 0x74:
        (length,) = struct.unpack(">H", data[5:7])
        return data[7:7 + length].decode("utf-8")
    if data[4] == 0x7C:
        (length,) = struct.unpack(">Q", data[5:13])
        return data[13:13 + length].decode("utf-8")
    if data[4] == 0x70:
        return ""
    raise RobotError("script result is not a String; wrap it in String(...)")


def run_js(script: str, edt: bool = False, component_id: str | None = None) -> None:
    prefix = f"{component_id}/" if component_id else ""
    _post(f"{prefix}js/execute", {"script": script, "runInEdt": edt})


def call_js(script: str, edt: bool = False, component_id: str | None = None) -> str:
    prefix = f"{component_id}/" if component_id else ""
    return _java_string(_post(f"{prefix}js/retrieveAny", {"script": script, "runInEdt": edt})["bytes"])


def find_all(xpath: str) -> list[dict]:
    return _post("xpath/components", {"xpath": xpath})["elementList"]


def find_one(xpath: str) -> dict:
    found = find_all(xpath)
    if len(found) != 1:
        raise RobotError(f"expected one component for {xpath}, found {len(found)}")
    return found[0]


def click(component_id: str) -> None:
    run_js("robot.click(component)", component_id=component_id)


def screenshot(target: Path, component_id: str | None = None) -> Path:
    """The whole screen, or one component painted off-screen (works while the sandbox is behind other windows)."""
    path = f"{component_id}/screenshot?isPaintingMode=true" if component_id else "screenshot"
    data = bytes(b & 0xFF for b in _get(path)["bytes"])
    # The PNG travels as a Java-serialized byte[]; its payload starts at the PNG signature.
    target.write_bytes(data[data.index(b"\x89PNG"):])
    return target


def main() -> None:
    command, argument = sys.argv[1], sys.argv[2]
    if command == "js":
        print(call_js(argument, edt="--edt" in sys.argv[3:]))
    elif command == "find":
        for component in find_all(argument):
            print(json.dumps(component))
    elif command == "screenshot":
        frame = find_one("//div[@class='IdeFrameImpl']")["id"] if "--frame" in sys.argv[3:] else None
        print(screenshot(Path(argument), frame))
    else:
        sys.exit(f"unknown command {command}")


if __name__ == "__main__":
    main()
