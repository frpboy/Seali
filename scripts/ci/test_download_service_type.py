#!/usr/bin/env python3
"""The download keep-alive service must use the Play-listed dataSync type."""
import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
MANIFEST = (ROOT / "app/src/main/AndroidManifest.xml").read_text()
SERVICE = (ROOT / "app/src/main/java/com/frpboy/seali/DownloadService.kt").read_text()


class DownloadServiceTypeTest(unittest.TestCase):
    def test_download_service_is_data_sync(self):
        block = re.search(r'<service\s[^>]*android:name="\.DownloadService"[^>]*/>', MANIFEST, re.S)
        self.assertIsNotNone(block)
        self.assertIn('android:foregroundServiceType="dataSync"', block.group(0))

    def test_permissions_match_declared_type(self):
        self.assertIn("android.permission.FOREGROUND_SERVICE_DATA_SYNC", MANIFEST)
        self.assertNotIn("FOREGROUND_SERVICE_SPECIAL_USE", MANIFEST)
        self.assertNotIn("specialUse", MANIFEST)

    def test_service_handles_android15_timeout(self):
        self.assertIn("override fun onTimeout(startId: Int, fgsType: Int)", SERVICE)
        self.assertIn("stopSelf()", SERVICE.split("override fun onTimeout(startId: Int, fgsType: Int)", 1)[1])

    def test_timeout_and_refused_start_do_not_crash_or_wedge(self):
        timeout = SERVICE.split("override fun onTimeout(startId: Int, fgsType: Int)", 1)[1].split("override fun onTimeout(startId: Int)", 1)[0]
        self.assertIn("stopForeground(STOP_FOREGROUND_REMOVE)", timeout)
        self.assertIn("App.stopService()", timeout)
        self.assertIn("catch (e: IllegalStateException)", SERVICE)


if __name__ == "__main__":
    unittest.main()
