"""Guard the Hebrew resources: every string translated, and Home mirrored rather than copied."""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET


RES = Path(__file__).resolve().parents[1] / "launcher/app/src/main/res"


def strings(folder):
    return {s.attrib["name"]: "".join(s.itertext())
            for s in ET.parse(RES / folder / "strings.xml").getroot()
            if s.tag == "string" and s.get("translatable") != "false"}


class HebrewResourcesTest(unittest.TestCase):
    def test_translations_cover_default_strings_and_format_arguments(self):
        default, hebrew = strings("values"), strings("values-iw")
        self.assertFalse(default.keys() - hebrew.keys(), "Missing Hebrew translations")
        for name, value in default.items():
            with self.subTest(string=name):
                self.assertEqual(re.findall(r"%\d+\$[a-zA-Z]", value),
                                 re.findall(r"%\d+\$[a-zA-Z]", hebrew[name]))

    def test_home_has_one_layout_mirrored_for_hebrew(self):
        # activity_main.xml uses start/end throughout, so Android mirrors it for Hebrew. A second copy under
        # layout-iw would silently replace it and fall behind every change made to the default one.
        self.assertFalse((RES / "layout-iw" / "activity_main.xml").exists())
        layout = (RES / "layout" / "activity_main.xml").read_text()
        self.assertNotRegex(layout, r'android:(layout_)?(gravity|alignParent|margin|padding)(Left|Right)="')
        self.assertNotRegex(layout, r'android:(layout_)?gravity="[^"]*\b(left|right)\b')

if __name__ == "__main__":
    unittest.main()
