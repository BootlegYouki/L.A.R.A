"""Fails when design-system/ drifts from docs/design-system-showcase.html (the design sample)."""
import json
import os
import re
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))


def read(path):
    with open(os.path.join(ROOT, path), encoding="utf-8") as f:
        return f.read()


def norm(value):
    return re.sub(r"\s+", " ", value.strip().lower())


class TestDesignTokens(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.html = read("docs/design-system-showcase.html")
        root = re.search(r":root\s*\{(.*?)\n\s*\}", cls.html, re.S)
        cls.root_vars = dict(re.findall(r"(--[a-z0-9-]+):\s*([^;]+);", root.group(1)))
        kt = re.search(r"object LaraColors \{(.*?)\n\}", cls.html, re.S)
        cls.sample_kotlin_colors = dict(re.findall(r"val (\w+) = Color\(0xFF([0-9A-Fa-f]{6})\)", kt.group(1)))
        tw = re.search(r"lara:\s*\{(.*?)\}", cls.html, re.S)
        cls.sample_tailwind = {k: v.upper() for k, v in re.findall(r"'?([a-z0-9-]+)'?:\s*'#([0-9A-Fa-f]{6})'", tw.group(1))}

    def test_sample_was_parsed(self):
        self.assertGreater(len(self.root_vars), 20)
        self.assertGreater(len(self.sample_kotlin_colors), 15)
        self.assertGreater(len(self.sample_tailwind), 15)

    def test_tokens_css_has_every_showcase_variable_with_the_same_value(self):
        css = read("design-system/tokens.css")
        defined = dict(re.findall(r"(--[a-z0-9-]+):\s*([^;]+);", css))
        for name, value in self.root_vars.items():
            self.assertIn(name, defined, f"tokens.css is missing {name} from the showcase")
            self.assertEqual(norm(defined[name]), norm(value), f"tokens.css {name} differs from the showcase")

    def test_color_kt_has_every_sample_color(self):
        kt = read("design-system/mobile/Color.kt")
        have = dict(re.findall(r"val (\w+) = Color\(0xFF([0-9A-Fa-f]{6})\)", kt))
        for name, hex_ in self.sample_kotlin_colors.items():
            self.assertIn(name, have, f"Color.kt is missing LaraColors.{name}")
            self.assertEqual(have[name].upper(), hex_.upper(), f"LaraColors.{name} differs from the showcase")

    def test_tailwind_theme_and_theme_css_have_every_sample_color(self):
        ts = read("design-system/desktop/tailwind.theme.ts")
        css = read("design-system/desktop/theme.css")
        for key, hex_ in self.sample_tailwind.items():
            self.assertRegex(ts, rf"'?{re.escape(key)}'?:\s*'#{hex_}'", f"tailwind.theme.ts: lara-{key}")
            self.assertRegex(css, rf"--color-lara-{re.escape(key)}:\s*#{hex_}", f"theme.css: lara-{key}")

    def test_tokens_json_contains_every_showcase_color(self):
        data = read("design-system/tokens.json").upper()
        for name, value in self.root_vars.items():
            if re.fullmatch(r"#[0-9A-Fa-f]{6}", value.strip()):
                self.assertIn(value.strip().upper(), data, f"tokens.json is missing {name} = {value.strip()}")
        json.loads(read("design-system/tokens.json"))

    def test_type_scale_matches_the_showcase_kotlin(self):
        sample = re.findall(r"fontSize = (\d+)\.sp, lineHeight = (\d+)\.sp", self.html)
        self.assertEqual(len(sample), 7)
        type_kt = read("design-system/mobile/Type.kt")
        for size, line in sample:
            self.assertRegex(type_kt, rf"fontSize = {size}\.sp, lineHeight = {line}\.sp", f"Type.kt {size}/{line}")

    def test_shape_kt_has_what_the_showcase_code_uses(self):
        shape = read("design-system/mobile/Shape.kt")
        for needle in ("object LaraSpacing", "val LaraShapes", "fun Modifier.laraShadow", "val MinTouchTarget = 52.dp",
                       "RoundedCornerShape(8.dp)", "RoundedCornerShape(12.dp)", "RoundedCornerShape(16.dp)",
                       "RoundedCornerShape(24.dp)", "Color(0x1417213D)", "Color(0x2017213D)"):
            self.assertIn(needle, shape)

    def test_no_gradients_and_no_external_urls_in_design_files(self):
        for path in ("design-system/tokens.css", "design-system/desktop/theme.css", "docs/design-system-showcase.html"):
            text = read(path)
            self.assertNotRegex(text, r"(linear|radial|conic)-gradient\(", f"{path} contains a gradient")
            self.assertNotRegex(text, r"https?://(?!www\.w3\.org)", f"{path} references an external URL")

    def test_kotlin_theme_uses_only_tokens(self):
        for path in ("design-system/mobile/Theme.kt", "design-system/mobile/Type.kt", "design-system/mobile/Shape.kt"):
            hexes = re.findall(r"Color\(0x[0-9A-Fa-f]{8}\)", read(path))
            allowed = {"Color(0x1417213D)", "Color(0x2017213D)"}
            self.assertEqual([h for h in hexes if h not in allowed], [], f"{path} hardcodes a color")


if __name__ == "__main__":
    unittest.main()
