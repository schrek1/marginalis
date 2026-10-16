"""Start, stop and locate a sandbox IntelliJ running Marginalis built straight from a worktree.

The sandbox is a separate IDE instance (own config/system dirs under RUNTIME/ide) next to Ondra's
everyday IDE: it gets the plugin from the given checkout, Remote Robot's server for UI automation,
and opens RUNTIME/project, a copy of project-template/.

Usage:  python sandbox.py start [--feature-dir PATH] [--fresh]
        python sandbox.py stop
        python sandbox.py status
Env:    MARGINALIS_SANDBOX_RUNTIME (default marginalis-sandbox-runtime next to the checkout),
        MARGINALIS_ROBOT_PORT (default 8082), MARGINALIS_DIR (default feature dir: this checkout),
        MARGINALIS_SANDBOX_DESKTOP (0-based virtual desktop for the window, default 2; `none` = leave it),
        MARGINALIS_SANDBOX_DOCKER=1 (run the IDE in a container on a virtual screen instead, see README)
Needs PowerShell 7 (`pwsh`) and the VirtualDesktop module (`Install-Module VirtualDesktop -Scope CurrentUser`).
"""
from __future__ import annotations

import argparse
import hashlib
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
ROBOT_PORT = int(os.environ.get("MARGINALIS_ROBOT_PORT", "8082"))
# MARGINALIS_SANDBOX_DOCKER=1 runs the sandbox in a container on a virtual 2560x1440 screen (Dockerfile): a fixed
# window size whatever the monitor does (asleep it reports 640x480, which clamps every window), nothing on the
# desktop, and the platform floor IDE (IntelliJ Community from the build) as CI runs it.
DOCKER = os.environ.get("MARGINALIS_SANDBOX_DOCKER") == "1"
CONTAINER = f"marginalis-sandbox-{ROBOT_PORT}"
# Tagged by what goes into the image: runs of different checkouts build their own image instead of swapping each
# other's entrypoint under running containers.
IMAGE = "marginalis-sandbox:" + hashlib.sha256(b"".join(
    (HERE / name).read_bytes().replace(b"\r\n", b"\n") for name in ("Dockerfile", "docker-entrypoint.sh"))).hexdigest()[:12]
BUILD_CONTAINER = "marginalis-sandbox-build"
# Plugins built once by `sandbox.py build`; a sandbox started with this set runs the IDE without Gradle.
PREBUILT = Path(os.environ["MARGINALIS_SANDBOX_PREBUILT"]) if os.environ.get("MARGINALIS_SANDBOX_PREBUILT") else None
# Clear of the host's JetBrains IDEs (63342 and up) and of Windows' dynamic port range (49152 and up), where
# services hold ports at random.
BUILTIN_PORT = ROBOT_PORT + 10000 if DOCKER else None
# Next to the checkout, so every worktree of one clone shares one sandbox.
RUNTIME = Path(os.environ.get("MARGINALIS_SANDBOX_RUNTIME",
                              REPO.parent / (CONTAINER if DOCKER else "marginalis-sandbox-runtime")))
PROJECT = RUNTIME / "project"
PROJECT_IN_IDE = "/work/runtime/project" if DOCKER else PROJECT.as_posix()
PID_FILE = RUNTIME / "gradle.pid"
LOG_FILE = RUNTIME / "run.log"
DEFAULT_FEATURE_DIR = Path(os.environ.get("MARGINALIS_DIR", REPO))
LOCAL_IDE = Path(os.environ.get("LOCALAPPDATA", "")) / "Programs" / "IntelliJ IDEA Ultimate"
IDE_PORTS = [BUILTIN_PORT] if DOCKER else list(range(63342, 63362))
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
    wanted = PROJECT_IN_IDE.lower()

    def serves_sandbox(port: int) -> bool:
        ping = get_json(f"http://127.0.0.1:{port}/api/marginalis/ping", timeout=1)
        return bool(ping) and any(p.get("path", "").lower() == wanted for p in ping.get("projects", []))

    for port in IDE_PORTS:
        if serves_sandbox(port):
            return port
    # With the usual range taken by other IDEs the platform falls back to a random port; the sandbox knows it.
    if robot_up() and not DOCKER:
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


def container_volumes(name: str) -> list[str]:
    return [
        "--mount", f"type=volume,source={name}-work,target=/work",
        # Gradle hands its cache locks between processes over loopback, which each container has to itself: one
        # Gradle home per container, filled once from the build container's, which holds the downloaded IDE.
        "--mount", f"type=volume,source={name}-gradle,target=/root/.gradle",
        "--mount", f"type=volume,source={BUILD_CONTAINER}-gradle,target=/gradle-seed,readonly",
    ]


def build_in_docker(feature_dir: Path, prebuilt: Path) -> None:
    """Builds the checkout once and leaves its plugins in `prebuilt`, for sandboxes started with
    MARGINALIS_SANDBOX_PREBUILT pointing there."""
    subprocess.run(["docker", "build", "-q", "-t", IMAGE, str(HERE)], check=True, stdout=subprocess.DEVNULL)
    prebuilt.mkdir(parents=True, exist_ok=True)
    subprocess.run([
        "docker", "run", "--rm", "-e", "MARGINALIS_SANDBOX_MODE=build",
        "--mount", f"type=bind,source={feature_dir},target=/src,readonly",
        "--mount", f"type=bind,source={prebuilt.resolve()},target=/prebuilt",
        *container_volumes(BUILD_CONTAINER),
        IMAGE,
    ], check=True)
    print(f"plugins built into {prebuilt}")


def start_in_docker(feature_dir: Path) -> subprocess.Popen:
    """Builds the image (a no-op once cached) and runs the container; the returned process follows its log and
    ends with it. The build output, the sandbox IDE's config and the Gradle cache live in volumes between starts."""
    subprocess.run(["docker", "build", "-q", "-t", IMAGE, str(HERE)], check=True, stdout=subprocess.DEVNULL)
    subprocess.run(["docker", "rm", "-f", CONTAINER], capture_output=True)
    source = (["-e", "MARGINALIS_SANDBOX_MODE=prebuilt",
               "--mount", f"type=bind,source={PREBUILT.resolve()},target=/prebuilt,readonly"] if PREBUILT else
              ["--mount", f"type=bind,source={feature_dir},target=/src,readonly"])
    subprocess.run([
        "docker", "run", "-d", "--name", CONTAINER,
        "-p", f"127.0.0.1:{ROBOT_PORT}:8082", "-p", f"127.0.0.1:{BUILTIN_PORT}:63342",
        *source,
        "--mount", f"type=bind,source={PROJECT.resolve()},target=/project-host",
        *container_volumes(CONTAINER),
        IMAGE,
    ], check=True, stdout=subprocess.DEVNULL)
    log = LOG_FILE.open("w", encoding="utf-8")
    return subprocess.Popen(["docker", "logs", "-f", CONTAINER], stdout=log, stderr=subprocess.STDOUT)


def start(feature_dir: Path, fresh: bool) -> None:
    if robot_up():
        sys.exit(f"a sandbox already answers on robot port {ROBOT_PORT}; run `stop` first")
    RUNTIME.mkdir(parents=True, exist_ok=True)
    if fresh or not PROJECT.exists():
        fresh_project()
    if DOCKER:
        process = start_in_docker(feature_dir)
        print(f"sandbox in container {CONTAINER} (Xvfb 2560x1440), log {LOG_FILE}")
        wait_until_up(process, mover=None)
        return
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
    wait_until_up(process, mover)


def wait_until_up(process: subprocess.Popen, mover: subprocess.Popen | None) -> None:
    deadline = time.monotonic() + START_TIMEOUT_S
    while time.monotonic() < deadline:
        if process.poll() is not None:
            sys.exit(f"the IDE's build exited with {process.returncode} before the IDE came up; see {LOG_FILE}")
        port = marginalis_port() if robot_up() else None
        if port and (mover is None or mover.poll() is not None):
            size_the_frame()
            wait_until_settled()
            print(f"sandbox up: robot http://127.0.0.1:{ROBOT_PORT}, marginalis http://127.0.0.1:{port}"
                  f"{window_note(mover)}")
            return
        time.sleep(1)
    sys.exit(f"sandbox not up after {START_TIMEOUT_S}s; see {LOG_FILE}")


SETTLE_TIMEOUT_S = 180


def wait_until_settled() -> None:
    """Waits until the IDE is done starting: indexing over, startup activities passed, the global workspace model
    reloaded from disk, and no write action pending, in three samples in a row. Opening an editor in the first seconds after a start, while the platform's own background
    writes (the JPS model sync) are queued, blocks the EDT for 30 s on a loaded machine."""
    import robot
    probe = """
        var project = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects()[0];
        var smart = !com.intellij.openapi.project.DumbService.getInstance(project).isDumb();
        var started = com.intellij.ide.startup.StartupManagerEx.getInstanceEx(project).postStartupActivityPassed();
        var quiet = !com.intellij.openapi.application.ApplicationManager.getApplication().isWriteActionPending();
        // The global workspace model is first read from a cache and reloaded from disk seconds later, in a background
        // write (seen 4 s after the IDE answered). Without that private flag (another platform version), go on.
        var globalModel = true;
        try {
            var app = com.intellij.openapi.application.ApplicationManager.getApplication();
            var type = java.lang.Class.forName('com.intellij.workspaceModel.ide.JpsGlobalModelSynchronizer', true,
                app.getClass().getClassLoader());
            var sync = app.getService(type);
            var field = sync.getClass().getDeclaredField('loadedFromDisk');
            field.setAccessible(true);
            globalModel = field.getBoolean(sync);
        } catch (e) {}
        String(smart && started && quiet && globalModel)
    """
    deadline = time.monotonic() + SETTLE_TIMEOUT_S
    quiet_samples = 0
    while time.monotonic() < deadline:
        try:
            quiet_samples = quiet_samples + 1 if robot.call_js(probe) == "true" else 0
        except Exception:
            quiet_samples = 0
        if quiet_samples >= 3:
            return
        time.sleep(1)
    sys.exit(f"the sandbox IDE did not settle within {SETTLE_TIMEOUT_S} s (indexing, startup activities or a pending write)")


def size_the_frame() -> None:
    """Gives the IDE frame a fixed size. With the screen asleep or its layout changed, Windows opens new windows
    tiny (620x392 seen): the editor is then a few pixels wide, inlaid thread panels get zero width, and the robot
    finds no panel at all."""
    import robot
    # The servers answer before the project's frame exists; on a loaded machine (16 sandboxes) that gap ran seconds.
    for _ in range(60):
        size = robot.call_js("""
            var frame = com.intellij.openapi.wm.WindowManager.getInstance().getFrame(
                com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects()[0]);
            if (frame == null) String('none');
            else {
                frame.setExtendedState(java.awt.Frame.NORMAL);
                frame.setBounds(0, 0, 2560, 1440);
                frame.validate();
                String(frame.getWidth() + 'x' + frame.getHeight());
            }
        """, edt=True)
        if size != "none":
            return
        time.sleep(1)
    sys.exit("the sandbox IDE opened no project frame within 60 s")


def window_note(mover: subprocess.Popen | None) -> str:
    if mover is None:
        return ""
    return {0: f", window on virtual desktop {DESKTOP + 1}",
            2: f"; no virtual desktop {DESKTOP + 1}, the window stays where it opened"}.get(
        mover.returncode, "; moving the window failed")


def process_alive(pid: str) -> bool:
    listing = subprocess.run(["tasklist", "/FI", f"PID eq {pid}", "/NH"], capture_output=True, text=True).stdout
    return pid in listing


def container_running() -> bool:
    return DOCKER and subprocess.run(["docker", "inspect", "-f", "{{.State.Running}}", CONTAINER],
                                     capture_output=True, text=True).stdout.strip() == "true"


def project_text(relative: str) -> str | None:
    """A file of the test project as the IDE has it now: in Docker mode the running IDE works on the container's
    copy, which the host folder only catches up with at the next stop."""
    if container_running():
        shown = subprocess.run(["docker", "exec", CONTAINER, "cat", f"/work/runtime/project/{relative}"],
                               capture_output=True)
        return shown.stdout.decode("utf-8") if shown.returncode == 0 else None
    path = PROJECT / relative
    return path.read_text(encoding="utf-8") if path.exists() else None


def push_project() -> None:
    """Copies what the harness wrote into the host folder over to the running IDE's copy. `.idea` stays the IDE's."""
    if container_running():
        subprocess.run(["docker", "exec", CONTAINER, "rsync", "-a", "--exclude", "/.idea", "/project-host/",
                        "/work/runtime/project/"], check=True, capture_output=True)


def pull_project() -> None:
    """Brings the IDE's copy back into the host folder, so the next start (and reads after the stop) see it."""
    if container_running():
        subprocess.run(["docker", "exec", CONTAINER, "rsync", "-a", "--delete", "/work/runtime/project/",
                        "/project-host/"], capture_output=True)


def stop() -> None:
    pid = PID_FILE.read_text().strip() if PID_FILE.exists() else None
    if robot_up():
        import robot
        # Settings are saved explicitly first: the robot server goes down early in the shutdown, and a kill after
        # that loses whatever the exit had not flushed yet (workspace.xml, app options).
        try:
            robot.run_js("""
                var store = com.intellij.configurationStore.StoreUtil;
                var projects = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects();
                for (var i = 0; i < projects.length; i++) store.saveSettings(projects[i], true);
                store.saveSettings(com.intellij.openapi.application.ApplicationManager.getApplication(), true);
            """)
        except (OSError, robot.RobotError) as error:
            # A hung IDE never answers; the kill below still has to run.
            print(f"saving the sandbox's settings failed ({type(error).__name__}); killing it")
        pull_project()
        try:
            robot.run_js("com.intellij.openapi.application.ApplicationManager.getApplication().exit(true, true, false)",
                         edt=True)
        except (OSError, robot.RobotError):
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
    if DOCKER:
        subprocess.run(["docker", "rm", "-f", CONTAINER], capture_output=True)
    print("sandbox stopped")


def status() -> None:
    port = marginalis_port()
    print(f"robot {'up' if robot_up() else 'down'} on {ROBOT_PORT}; marginalis {f'on {port}' if port else 'not found'}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=["start", "stop", "status", "build"])
    parser.add_argument("--feature-dir", type=Path, default=DEFAULT_FEATURE_DIR)
    parser.add_argument("--fresh", action="store_true", help="recopy the test project from project-template")
    args = parser.parse_args()
    if args.command == "start":
        start(args.feature_dir.resolve(), args.fresh)
    elif args.command == "stop":
        stop()
    elif args.command == "build":
        if not (DOCKER and PREBUILT):
            sys.exit("build needs MARGINALIS_SANDBOX_DOCKER=1 and MARGINALIS_SANDBOX_PREBUILT=<folder for the plugins>")
        build_in_docker(args.feature_dir.resolve(), PREBUILT)
    else:
        status()


if __name__ == "__main__":
    main()
