import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from src.collector.discover_targeted import read_seed_ids


class SeedExclusionTests(unittest.TestCase):
    def test_optional_seed(self):
        with patch.dict(os.environ, {}, clear=True):
            self.assertEqual(read_seed_ids(), set())

    def test_ids_loaded(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "seed.json"
            path.write_text(json.dumps({"products": [{"product_id": "12"}]}))
            with patch.dict(os.environ, {"SEED_MANIFEST": str(path)}):
                self.assertEqual(read_seed_ids(), {12})

    def test_invalid_manifest_fails_closed(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "seed.json"
            path.write_text(json.dumps({"products": []}))
            with patch.dict(os.environ, {"SEED_MANIFEST": str(path)}):
                with self.assertRaises(ValueError):
                    read_seed_ids()
