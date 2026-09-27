#!/usr/bin/env python3
"""Exercise the portable entry point before any JVM can select its toolkit.

The old AppRun opened X11 even in a Wayland session. Setting options on just
one JVM also left the launcher and its child game using different backends.
These checks execute AppRun and observe the arguments and environment received
by its launcher and a child process, without requiring a display server.
"""
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import tempfile
import unittest


ENTRY = Path(os.environ.get("CHONKCRAFT_TEST_APPRUN", Path(__file__).with_name("AppRun")))


class AppImageEntryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="chonkcraft appimage ")
        self.addCleanup(self.temp.cleanup)
        self.app = Path(self.temp.name)
        (self.app / "bin").mkdir()
        runtime = self.app / "lib/runtime/lib"
        runtime.mkdir(parents=True)
        self.wlawt = runtime / "libawt_wlawt.so"
        self.wlawt.touch()
        shutil.copyfile(ENTRY, self.app / "AppRun")
        launcher = self.app / "bin/chonkcraft"
        launcher.write_text(
            f"#!{sys.executable}\n"
            "import json, os, subprocess, sys\n"
            "keys = ['JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'SEVEN_JAVA2D_PIPELINE']\n"
            "child = subprocess.check_output([sys.executable, '-c', "
            "'import json,os; print(json.dumps(dict(os.environ)))'], text=True)\n"
            "print(json.dumps({'args': sys.argv[1:], 'env': {k: os.getenv(k) for k in keys}, "
            "'child': {k: json.loads(child).get(k) for k in keys}}))\n"
        )
        launcher.chmod(0o755)
        self.socket = socket.socket(socket.AF_UNIX)
        self.addCleanup(self.socket.close)
        self.socket.bind(str(self.app / "wayland-test"))
        self.env = os.environ.copy()
        for key in ("WAYLAND_DISPLAY", "WAYLAND_SOCKET", "XDG_SESSION_TYPE",
                    "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS",
                    "SEVEN_JAVA2D_PIPELINE", "CHONKCRAFT_DISPLAY"):
            self.env.pop(key, None)
        self.env.update(XDG_RUNTIME_DIR=str(self.app), DISPLAY=":9")

    def run_entry(self, *args, success=True):
        result = subprocess.run(["bash", str(self.app / "AppRun"), *args],
                                env=self.env, capture_output=True, text=True, timeout=10)
        if not success:
            self.assertNotEqual(result.returncode, 0, "an unavailable forced display must fail clearly")
            return result
        self.assertEqual(result.returncode, 0, result.stderr)
        observed = json.loads(result.stdout)
        self.assertEqual(observed["env"], observed["child"],
                         "the child game must inherit the launcher's display and renderer")
        return observed

    def assert_wayland(self, observed):
        options = observed["env"]["_JAVA_OPTIONS"] or ""
        self.assertIn("-Dawt.toolkit.name=WLToolkit", options, "the window must connect to Wayland")
        self.assertIn("-Dsun.java2d.vulkan=false", options, "native Vulkan must not stall input")
        self.assertIn("-Dsun.java2d.opengl=false", options, "the launcher must use the tested renderer too")
        self.assertEqual(observed["env"]["SEVEN_JAVA2D_PIPELINE"], "software",
                         "the game must select software before initializing AWT")

    def test_wayland_session_selects_native_software_for_both_processes(self):
        self.env["WAYLAND_DISPLAY"] = "wayland-test"
        observed = self.run_entry("--home", "a home with spaces", "--launch")
        self.assert_wayland(observed)
        self.assertEqual(observed["args"], ["--home", "a home with spaces", "--launch"],
                         "the selected pack and launcher commands must survive the wrapper")

    def test_x11_session_keeps_its_existing_renderer(self):
        self.assertIsNone(self.run_entry()["env"]["_JAVA_OPTIONS"],
                          "X11 users must keep the normal Java graphics selection")

    def test_wayland_does_not_require_an_x11_display(self):
        self.env["WAYLAND_DISPLAY"] = "wayland-test"
        self.env.pop("DISPLAY")
        self.assert_wayland(self.run_entry())

    def test_toolkit_survives_the_pinned_runtime_startup(self):
        java_home = os.environ.get("JAVA_HOME")
        if not java_home:
            self.skipTest("run with scripts/jbr/with-jbr-25.sh to check the actual JVM")
        java = Path(java_home) / "bin/java"
        self.assertTrue(java.is_file(), "JAVA_HOME must contain the pinned runtime")
        self.env.update(WAYLAND_DISPLAY="wayland-test", CHONKCRAFT_TEST_JAVA=str(java))
        self.env.pop("DISPLAY")
        (self.app / "bin/chonkcraft").write_text(
            '#!/usr/bin/env bash\nexec "$CHONKCRAFT_TEST_JAVA" -XshowSettings:properties -version\n'
        )
        # JBR's native launcher overrides a toolkit supplied only through
        # JAVA_TOOL_OPTIONS. Checking the final property catches that regression
        # without starting AWT or needing a compositor in CI.
        result = subprocess.run(["bash", str(self.app / "AppRun")], env=self.env,
                                capture_output=True, text=True, timeout=15)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("    awt.toolkit.name = WLToolkit\n", result.stderr,
                      "the actual runtime must retain the selected Wayland toolkit")
        self.assertIn("    sun.java2d.vulkan = false\n", result.stderr)
        self.assertIn("    sun.java2d.opengl = false\n", result.stderr)

    def test_stale_wayland_name_falls_back(self):
        self.env.update(WAYLAND_DISPLAY="missing-socket", XDG_SESSION_TYPE="wayland")
        self.assertIsNone(self.run_entry()["env"]["_JAVA_OPTIONS"],
                          "a session label cannot make a missing Wayland display usable")

    def test_absolute_wayland_socket_works_without_runtime_directory(self):
        self.env["WAYLAND_DISPLAY"] = str(self.app / "wayland-test")
        self.env.pop("XDG_RUNTIME_DIR")
        self.assert_wayland(self.run_entry())

    def test_relative_socket_requires_runtime_directory(self):
        self.env["WAYLAND_DISPLAY"] = "wayland-test"
        self.env.pop("XDG_RUNTIME_DIR")
        self.assertIsNone(self.run_entry()["env"]["_JAVA_OPTIONS"],
                          "relative display names must resolve in the session runtime directory")

    def test_runtime_without_wayland_falls_back(self):
        self.env["WAYLAND_DISPLAY"] = "wayland-test"
        self.wlawt.unlink()
        self.assertIsNone(self.run_entry()["env"]["_JAVA_OPTIONS"],
                          "a runtime without WLToolkit must still be able to launch through X11")
        self.assertIn("WLToolkit", self.run_entry("--wayland", success=False).stderr)

    def test_xwayland_override_is_consumed_before_the_launcher(self):
        self.env["WAYLAND_DISPLAY"] = "wayland-test"
        observed = self.run_entry("--xwayland", "--launch")
        self.assertIn("-Dawt.toolkit.name=XToolkit", observed["env"]["_JAVA_OPTIONS"],
                      "the player can return to the Xwayland renderer")
        self.assertEqual(observed["args"], ["--launch"], "the launcher must not receive display switches")

    def test_forced_wayland_reports_an_unavailable_display(self):
        self.assertIn("Wayland socket", self.run_entry("--wayland", success=False).stderr)

    def test_explicit_java_toolkit_is_preserved(self):
        self.env.update(WAYLAND_DISPLAY="wayland-test",
                        JDK_JAVA_OPTIONS="-Dawt.toolkit.name=XToolkit")
        observed = self.run_entry()
        self.assertIsNone(observed["env"]["_JAVA_OPTIONS"], "automatic selection must respect an explicit toolkit")
        self.assertEqual(observed["env"]["JDK_JAVA_OPTIONS"], self.env["JDK_JAVA_OPTIONS"],
                         "existing JVM settings must reach the child unchanged")

    def test_explicit_gpu_pipeline_is_preserved(self):
        self.env.update(WAYLAND_DISPLAY="wayland-test", SEVEN_JAVA2D_PIPELINE="opengl")
        observed = self.run_entry()
        self.assertIsNone(observed["env"]["_JAVA_OPTIONS"], "an explicit OpenGL choice must keep X11")
        self.assertEqual(observed["env"]["SEVEN_JAVA2D_PIPELINE"], "opengl", "the caller owns the renderer override")

    def test_existing_options_and_literal_arguments_are_not_evaluated(self):
        self.env.update(WAYLAND_DISPLAY="wayland-test", _JAVA_OPTIONS='-Dexample="with spaces"')
        literal = "$(touch should-not-exist)"
        observed = self.run_entry("--wayland", literal)
        self.assert_wayland(observed)
        self.assertTrue(observed["env"]["_JAVA_OPTIONS"].startswith('-Dexample="with spaces" '),
                        "appending display choices must preserve existing JVM option quoting")
        self.assertEqual(observed["args"], [literal], "shell syntax in arguments must remain literal")

    def test_invalid_display_choice_fails_clearly(self):
        self.env["CHONKCRAFT_DISPLAY"] = "typo"
        self.assertIn("CHONKCRAFT_DISPLAY", self.run_entry(success=False).stderr)


if __name__ == "__main__":
    unittest.main()
