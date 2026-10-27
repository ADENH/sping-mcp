"""Exercise the shipped HTTP demo rather than a duplicate in-memory fixture."""
import json
import threading
import unittest
from http.server import ThreadingHTTPServer
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from mock_backend import Handler


class MockBackendTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.base = f"http://127.0.0.1:{cls.server.server_port}"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=3)

    def get(self, path, token=None):
        headers = {"Authorization": f"Bearer {token}"} if token else {}
        with urlopen(Request(self.base + path, headers=headers), timeout=3) as response:
            return json.load(response)

    def test_catalog_filters_and_paginates(self):
        self.assertEqual(self.get("/api/v1/ai/?search=garden")["data"][0]["id"], 1)
        self.assertEqual(self.get("/api/v1/ai/?page=2")["data"], [])
        self.assertEqual(self.get("/api/v1/ai/?category=video")["meta"]["total"], 0)
        self.assertEqual(self.get("/api/v1/ai/1")["data"]["name"], "Garden Image AI")

    def test_generation_workflow(self):
        request = Request(self.base + "/api/v1/replicate/create-prediction",
                          data=json.dumps({"prompt": "a garden"}).encode(),
                          headers={"Authorization": "Bearer demo-token", "Content-Type": "application/json"})
        with urlopen(request, timeout=3) as response:
            prediction = json.load(response)
        result = self.get("/api/v1/replicate/status/" + prediction["id"], "demo-token")
        self.assertEqual(result["status"], "succeeded")
        self.assertTrue(result["urls"])

    def test_status_requires_fixture_token(self):
        with self.assertRaises(HTTPError) as error:
            self.get("/api/v1/replicate/status/demo-prediction-1")
        self.assertEqual(error.exception.code, 401)
        error.exception.close()

    def test_model_and_health(self):
        self.assertEqual(self.get("/health")["status"], "ok")
        self.assertEqual(self.get("/api/v1/replicate/model-info")["model"], "hidream-l1-fast")

    def test_invalid_pagination(self):
        with self.assertRaises(HTTPError) as error:
            self.get("/api/v1/ai/?limit=999")
        self.assertEqual(error.exception.code, 422)
        error.exception.close()


if __name__ == "__main__":
    unittest.main()
