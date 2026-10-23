import tempfile
import unittest
from pathlib import Path

import fork_identity

PLUGIN_XML = "<idea-plugin>\r\n    <id>dev.marginalis.plugin</id>\r\n    <name>Marginalis</name>\r\n</idea-plugin>\r\n"
BUILD = ('plugins {\n    id("org.jetbrains.intellij.platform") version "2.18.1"\n}\n'
         'intellijPlatform {\n    pluginConfiguration {\n        id = "dev.marginalis.plugin"\n'
         '        name = "Marginalis"\n    }\n}\n'
         'filter { includeTestsMatching("dev.marginalis.plugin.sandbox.*") }\n')
LISTENER = ('package dev.marginalis.plugin\n\nimport dev.marginalis.plugin.store.MarginalisStore\n\n'
            'fun ours(id: String) = id == "dev.marginalis.plugin"\n')
SANDBOX_JS = "var store = service('dev.marginalis.plugin.store.MarginalisStore');\nPluginId.getId('dev.marginalis.plugin');\n"


class ForkIdentityTest(unittest.TestCase):

    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.write("src/main/resources/META-INF/plugin.xml", PLUGIN_XML)
        self.write("build.gradle.kts", BUILD)
        self.write("src/main/kotlin/dev/marginalis/plugin/Listener.kt", LISTENER)
        self.write("src/uiTest/kotlin/SandboxUi.kt", SANDBOX_JS)

    def write(self, relative: str, text: str) -> None:
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(text.encode("utf-8"))

    def read(self, relative: str) -> str:
        return (self.root / relative).read_bytes().decode("utf-8")

    def test_the_plugin_declares_the_fork_id_and_name(self):
        fork_identity.apply(self.root)

        self.assertEqual("<idea-plugin>\r\n    <id>dev.marginalis.plugin.schreko</id>\r\n"
                         "    <name>Marginalis (schreko)</name>\r\n</idea-plugin>\r\n",
                         self.read("src/main/resources/META-INF/plugin.xml"))
        self.assertIn('        id = "dev.marginalis.plugin.schreko"\n        name = "Marginalis (schreko)"\n',
                      self.read("build.gradle.kts"))

    def test_id_lookups_follow_and_package_names_stay(self):
        fork_identity.apply(self.root)

        self.assertEqual(LISTENER.replace('== "dev.marginalis.plugin"', '== "dev.marginalis.plugin.schreko"'),
                         self.read("src/main/kotlin/dev/marginalis/plugin/Listener.kt"))
        self.assertEqual(SANDBOX_JS.replace("getId('dev.marginalis.plugin')", "getId('dev.marginalis.plugin.schreko')"),
                         self.read("src/uiTest/kotlin/SandboxUi.kt"))
        self.assertIn('includeTestsMatching("dev.marginalis.plugin.sandbox.*")', self.read("build.gradle.kts"))

    def test_a_moved_declaration_stops_the_build(self):
        self.write("build.gradle.kts", BUILD.replace('id = "dev.marginalis.plugin"', 'id = pluginId'))

        with self.assertRaises(SystemExit) as stopped:
            fork_identity.apply(self.root)

        self.assertIn("build.gradle.kts: expected one match", str(stopped.exception.code))

    def test_a_checkout_already_rewritten_is_refused(self):
        fork_identity.apply(self.root)

        with self.assertRaises(SystemExit):
            fork_identity.apply(self.root)


if __name__ == "__main__":
    unittest.main()
