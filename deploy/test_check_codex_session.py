import base64
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location(
    "check_codex_session", Path(__file__).with_name("check-codex-session.py")
)
check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check)


class CheckCodexSessionTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.auth = Path(directory.name) / "auth.json"
        self.host_auth = Path(directory.name) / "host-auth.json"
        self.state = Path(directory.name) / "state.json"
        self.now = 1800000000
        for target, value in (("AUTH_FILE", self.auth), ("HOST_AUTH_FILE", self.host_auth), ("STATE_FILE", self.state)):
            patcher = patch.object(check, target, value)
            patcher.start()
            self.addCleanup(patcher.stop)

    def token_expiring_in(self, seconds):
        expiry = self.now + seconds
        payload = base64.urlsafe_b64encode(json.dumps({"exp": expiry}).encode()).decode().rstrip("=")
        self.auth.write_text(json.dumps({"tokens": {"access_token": "header." + payload + ".secret"}}))
        return expiry

    def run_check(self, now=None, failure=None):
        output = io.StringIO()
        with patch.object(check.time, "time", return_value=self.now if now is None else now):
            with patch.object(check, "notify", side_effect=failure) as notify:
                with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
                    result = check.main()
        return result, notify, output.getvalue()

    def test_no_alert_before_three_day_threshold(self):
        self.token_expiring_in(3 * 86400 + 1)
        result, notify, _ = self.run_check()
        self.assertEqual(result, 0)
        notify.assert_not_called()
        self.assertFalse(self.state.exists())

    def test_three_two_one_and_expired_alert_once_per_milestone(self):
        expiry = self.token_expiring_in(3 * 86400)
        for days in (3, 2, 1, 0):
            now = expiry - days * 86400
            result, notify, _ = self.run_check(now)
            self.assertEqual(result, 0)
            notify.assert_called_once()
            message = notify.call_args.args[0]
            self.assertIn(f"{days} ngay" if days else "DA HET HAN", message)
            self.assertNotIn("secret", message)
            result, notify, _ = self.run_check(now + 1)
            self.assertEqual(result, 0)
            notify.assert_not_called()
        self.assertEqual(json.loads(self.state.read_text())["sent"], [3, 2, 1, 0])

    def test_new_expiry_resets_milestones(self):
        self.token_expiring_in(2 * 86400)
        self.run_check()
        expiry = self.token_expiring_in(2 * 86400 - 1)
        result, notify, _ = self.run_check()
        self.assertEqual(result, 0)
        notify.assert_called_once()
        self.assertEqual(json.loads(self.state.read_text()), {"expiry": expiry, "sent": [2]})

    def test_failed_notification_is_retried_without_marking_sent(self):
        self.token_expiring_in(86400)
        result, _, output = self.run_check(failure=OSError("sensitive-secret"))
        self.assertEqual(result, 1)
        self.assertFalse(self.state.exists())
        self.assertNotIn("sensitive-secret", output)
        result, notify, _ = self.run_check()
        self.assertEqual(result, 0)
        notify.assert_called_once()

    def test_invalid_auth_or_expiry_fails_without_exposing_token(self):
        for token in ("sensitive-secret", "header.e30.secret", "header.!!!!.secret"):
            with self.subTest(token=token):
                self.auth.write_text(json.dumps({"tokens": {"access_token": token}}))
                result, notify, output = self.run_check()
                self.assertEqual(result, 1)
                notify.assert_not_called()
                self.assertNotIn(token, output)

    def test_syncs_newer_host_auth_atomically_with_private_permissions(self):
        new_expiry = self.token_expiring_in(10 * 86400)
        source = self.auth.read_bytes()
        self.host_auth.write_bytes(source)
        self.token_expiring_in(86400)
        result, notify, output = self.run_check()
        self.assertEqual(result, 0)
        notify.assert_not_called()
        self.assertEqual(self.auth.read_bytes(), source)
        self.assertEqual(check.token_expiry(self.auth.read_bytes()), new_expiry)
        self.assertEqual(self.auth.stat().st_mode & 0o777, 0o600)
        self.assertEqual(self.auth.stat().st_uid, self.auth.parent.stat().st_uid)
        self.assertEqual(list(self.auth.parent.glob(".auth-sync-*")), [])
        self.assertNotIn("secret", output)

    def test_does_not_overwrite_service_with_same_or_older_host_auth(self):
        for source_days in (5, 10):
            with self.subTest(source_days=source_days):
                self.token_expiring_in(source_days * 86400)
                self.host_auth.write_bytes(self.auth.read_bytes())
                self.token_expiring_in(10 * 86400)
                original = self.auth.read_bytes()
                result, _, _ = self.run_check()
                self.assertEqual(result, 0)
                self.assertEqual(self.auth.read_bytes(), original)

    def test_expired_or_invalid_host_keeps_existing_auth_and_warns(self):
        self.token_expiring_in(-86400)
        expired = self.auth.read_bytes()
        for source in (expired, b"invalid-secret"):
            with self.subTest(source=source):
                self.host_auth.write_bytes(source)
                self.token_expiring_in(86400)
                self.state.unlink(missing_ok=True)
                original = self.auth.read_bytes()
                result, notify, _ = self.run_check()
                self.assertEqual(result, 0)
                notify.assert_called_once()
                self.assertEqual(self.auth.read_bytes(), original)

    def test_valid_host_auth_recovers_invalid_service_auth(self):
        self.token_expiring_in(10 * 86400)
        source = self.auth.read_bytes()
        self.host_auth.write_bytes(source)
        self.auth.write_text("invalid-secret")
        result, notify, _ = self.run_check()
        self.assertEqual(result, 0)
        notify.assert_not_called()
        self.assertEqual(self.auth.read_bytes(), source)


if __name__ == "__main__":
    unittest.main()
