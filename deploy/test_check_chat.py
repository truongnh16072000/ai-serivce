import contextlib
import importlib.util
import io
import pathlib
import unittest
import urllib.error
from unittest.mock import patch

spec = importlib.util.spec_from_file_location(
    "check_chat", pathlib.Path(__file__).with_name("check-chat.py")
)
check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check)


class CheckChatTests(unittest.TestCase):
    def run_check(self, results):
        with patch.object(check, "post_json", side_effect=results) as post:
            with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                result = check.main()
        return result, post

    def test_200_does_not_send_telegram(self):
        result, post = self.run_check([(200, '{"answer":"OK"}')])
        self.assertEqual(result, 0)
        post.assert_called_once_with(check.CHAT_URL, {"message": "Reply exactly OK."}, 140)

    def test_non_200_sends_bounded_error_to_telegram(self):
        result, post = self.run_check([(502, 'token_expired "quoted"\n' + "x" * 5000), (202, "")])
        self.assertEqual(result, 1)
        url, payload, timeout = post.call_args.args
        self.assertEqual(url, check.TELEGRAM_URL)
        self.assertEqual(timeout, 20)
        self.assertIn("HTTP: 502", payload["text"])
        self.assertIn('token_expired "quoted"\n', payload["text"])
        self.assertLessEqual(len(payload["text"]), 4096)

    def test_connection_error_or_timeout_sends_alert(self):
        for error in (urllib.error.URLError("connection refused"), TimeoutError("timed out")):
            with self.subTest(error=error):
                result, post = self.run_check([error, (202, "")])
                self.assertEqual(result, 1)
                self.assertIn("connection error", post.call_args.args[1]["text"])

    def test_failed_telegram_request_is_reported(self):
        for failure in ((503, "unavailable"), urllib.error.URLError("offline")):
            with self.subTest(failure=failure):
                result, _ = self.run_check([(502, "upstream failed"), failure])
                self.assertEqual(result, 2)

    def test_http_error_preserves_status_and_body(self):
        error = urllib.error.HTTPError(check.CHAT_URL, 502, "Bad Gateway", {}, io.BytesIO(b'{"detail":"expired"}'))
        with patch.object(check.urllib.request, "urlopen", side_effect=error):
            self.assertEqual(check.post_json(check.CHAT_URL, {}, 1), (502, '{"detail":"expired"}'))


if __name__ == "__main__":
    unittest.main()
