"""Start, stop and locate a sandbox IntelliJ running Marginalis built straight from a worktree.

The sandbox is a separate IDE instance (own config/system dirs under RUNTIME/ide) next to Ondra's
everyday IDE: it gets the plugin from the given checkout, Remote Robot's server for UI automation,
and opens RUNTIME/project, a copy of project-template/.

Usage:  python sandbox.py start [--feature-dir PATH] [--fresh]
        python sandbox.py stop
        python sandbox.py status
Env:    MARGINALIS_SANDBOX_RUNTIME (default marginalis-sandbox-runtime next to the checkout),
        MARGINALIS_ROBOT_PORT (default 8082), MARGINALIS_DIR (default feature dir: this checkout),
        MARGINALIS_SANDBOX_DESKTOP (0-based virtual desktop for the window, default 2; `none` = leave it)
Needs PowerShell 7 (`pwsh`) and the VirtualDesktop module (`Install-Module VirtualDesktop -Scope CurrentUser`).
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import socket
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent
# Next to the checkout, so every worktree of one clone shares one sandbox.
RUNTIME = Path(os.environ.get("MARGINALIS_SANDBOX_RUNTIME", REPO.parent / "marginalis-sandbox-runtime"))
PROJECT = RUNTIME / "project"
PID_FILE = RUNTIME / "gradle.pid"
LOG_FILE = RUNTIME / "run.log"
ROBOT_PORT = int(os.environ.get("MARGINALIS_ROBOT_PORT", "8082"))
DEFAULT_FEATURE_DIR = Path(os.environ.get("MARGINALIS_DIR", REPO))
LOCAL_IDE = Path(os.environ["LOCALAPPDATA"]) / "Programs" / "IntelliJ IDEA Ultimate"
IDE_PORTS = range(63342, 63362)
# 0-based virtual desktop for the sandbox window (Ondra's 3rd, "diy"); MARGINALIS_SANDBOX_DESKTOP=none keeps it put.
_desktop = os.environ.get("MARGINALIS_SANDBOX_DESKTOP", "2")
DESKTOP = None if _desktop.lower() == "none" else int(_desktop)
START_TIMEOUT_S = 600


def find_jdk21() -> Path:
    candidates = sorted((Path.home() / ".jdks").glob("corretto-21*"), reverse=True)
    candidates += sorted(Path(r"C:\Program Files\Java").glob("jdk-21*"), reverse=True)
    for jdk in candidates:
        if (jdk / "bin" / "java.exe").exists():
            return jdk
    sys.exit("no JDK 21 found in ~/.jdks or C:\\Program Files\\Java")


def get_json(url: str, timeout: float = 2) -> dict | None:
    try:
        with urllib.request.urlopen(url, timeout=timeout) as response:
            return json.loads(response.read())
    except Exception:
        return None


def robot_up() -> bool:
    try:
        with socket.create_connection(("127.0.0.1", ROBOT_PORT), timeout=2):
            return True
    except OSError:
        return False


def marginalis_port() -> int | None:
    """The built-in server port of the IDE that has the sandbox project open."""
    wanted = PROJECT.as_posix().lower()

    def serves_sandbox(port: int) -> bool:
        ping = get_json(f"http://127.0.0.1:{port}/api/marginalis/ping", timeout=1)
        return bool(ping) and any(p.get("path", "").lower() == wanted for p in ping.get("projects", []))

    for port in IDE_PORTS:
        if serves_sandbox(port):
            return port
    # With the usual range taken by other IDEs the platform falls back to a random port; the sandbox knows it.
    if robot_up():
        import robot
        try:
            port = int(robot.call_js("String(org.jetbrains.ide.BuiltInServerManager.getInstance().getPort())"))
        except Exception:
            return None
        if serves_sandbox(port):
            return port
    return None


def fresh_project() -> None:
    if PROJECT.exists():
        shutil.rmtree(PROJECT)
    shutil.copytree(HERE / "project-template", PROJECT)


def start(feature_dir: Path, fresh: bool) -> None:
    if robot_up():
        sys.exit(f"a sandbox already answers on robot port {ROBOT_PORT}; run `stop` first")
    RUNTIME.mkdir(parents=True, exist_ok=True)
    if fresh or not PROJECT.exists():
        fresh_project()
    env = dict(os.environ, JAVA_HOME=str(find_jdk21()), MARGINALIS_SANDBOX_RUNTIME=RUNTIME.as_posix(),
               MARGINALIS_ROBOT_PORT=str(ROBOT_PORT))
    log = LOG_FILE.open("w", encoding="utf-8")
    process = subprocess.Popen(
        [str(feature_dir / "gradlew.bat"), "--no-daemon", "--init-script", str(HERE / "init.gradle"),
         "runIdeForUiTests", f"-Pmarginalis.localIde={LOCAL_IDE}"],
        cwd=feature_dir, env=env, stdout=log, stderr=subprocess.STDOUT,
        creationflags=subprocess.CREATE_NEW_PROCESS_GROUP,
    )
    PID_FILE.write_text(str(process.pid))
    print(f"gradle pid {process.pid}, log {LOG_FILE}")
    mover = None if DESKTOP is None else subprocess.Popen(
        ["pwsh", "-NoProfile", "-File", str(HERE / "move_to_desktop.ps1"),
         "-RuntimeMarker", RUNTIME.name, "-Desktop", str(DESKTOP)],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    deadline = time.monotonic() + START_TIMEOUT_S
    while time.monotonic() < deadline:
        if process.poll() is not None:
            sys.exit(f"gradle exited with {process.returncode} before the IDE came up; see {LOG_FILE}")
        port = marginalis_port() if robot_up() else None
        if port and (mover is None or mover.poll() is not None):
            size_the_frame()
            print(f"sandbox up: robot http://127.0.0.1:{ROBOT_PORT}, marginalis http://127.0.0.1:{port}"
                  f"{window_note(mover)}")
            return
        time.sleep(1)
    sys.exit(f"sandbox not up after {START_TIMEOUT_S}s; see {LOG_FILE}")


def size_the_frame() -> None:
    """Gives the IDE frame a fixed size. With the screen asleep or its layout changed, Windows opens new windows
    tiny (620x392 seen): the editor is then a few pixels wide, inlaid thread panels get zero width, and the robot
    finds no panel at all."""
    import robot
    robot.call_js("""
        var frame = com.intellij.openapi.wm.WindowManager.getInstance().getFrame(
            com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects()[0]);
        frame.setExtendedState(java.awt.Frame.NORMAL);
        frame.setBounds(0, 0, 1600, 1000);
        frame.validate();
        String(frame.getWidth() + 'x' + frame.getHeight())
    """, edt=True)


def window_note(mover: subprocess.Popen | None) -> str:
    if mover is None:
        return ""
    return {0: f", window on virtual desktop {DESKTOP + 1}",
            2: f"; no virtual desktop {DESKTOP + 1}, the window stays where it opened"}.get(
        mover.returncode, "; moving the window failed")


def process_alive(pid: str) -> bool:
    listing = subprocess.run(["tasklist", "/FI", f"PID eq {pid}", "/NH"], capture_output=True, text=True).stdout
    return pid in listing


def stop() -> None:
    pid = PID_FILE.read_text().strip() if PID_FILE.exists() else None
    if robot_up():
        import robot
        # Settings are saved explicitly first: the robot server goes down early in the shutdown, and a kill after
        # that loses whatever the exit had not flushed yet (workspace.xml, app options).
        robot.run_js("""
            var store = com.intellij.configurationStore.StoreUtil;
            var projects = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects();
            for (var i = 0; i < projects.length; i++) store.saveSettings(projects[i], true);
            store.saveSettings(com.intellij.openapi.application.ApplicationManager.getApplication(), true);
        """)
        try:
            robot.run_js("com.intellij.openapi.application.ApplicationManager.getApplication().exit(true, true, false)",
                         edt=True)
        except (ConnectionError, OSError):
            pass  # the IDE closes the connection before answering
    if pid:
        # The shutdown often hangs after the robot server is gone (a leak it reports); everything is saved by now,
        # so a short grace period and a kill are safe.
        for _ in range(20):
            if not process_alive(pid):
                break
            time.sleep(1)
        subprocess.run(["taskkill", "/T", "/F", "/PID", pid], capture_output=True)
        PID_FILE.unlink()
    print("sandbox stopped")


def status() -> None:
    port = marginalis_port()
    print(f"robot {'up' if robot_up() else 'down'} on {ROBOT_PORT}; marginalis {f'on {port}' if port else 'not found'}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=["start", "stop", "status"])
    parser.add_argument("--feature-dir", type=Path, default=DEFAULT_FEATURE_DIR)
    parser.add_argument("--fresh", action="store_true", help="recopy the test project from project-template")
    args = parser.parse_args()
    if args.command == "start":
        start(args.feature_dir.resolve(), args.fresh)
    elif args.command == "stop":
        stop()
    else:
        status()


if __name__ == "__main__":
    main()
