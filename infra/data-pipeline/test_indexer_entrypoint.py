import importlib.util
import io
from pathlib import Path
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).parent
sys.path.insert(0, str(ROOT / "crawler/src"))
spec = importlib.util.spec_from_file_location("indexer_entrypoint", ROOT / "indexer/entrypoint.py")
entrypoint = importlib.util.module_from_spec(spec)
spec.loader.exec_module(entrypoint)


class IndexerEntrypointTests(unittest.TestCase):
    def test_deployment_mode_does_not_invoke_indexer(self):
        with patch("sys.argv", ["entrypoint", "--bucket", "unused", "--run-id", "deploy-only"]), \
                patch.object(entrypoint.subprocess, "run") as indexer:
            entrypoint.main()
            indexer.assert_not_called()

    def test_archive_traversal_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            archive = root / "bundle.tar.gz"
            with tarfile.open(archive, "w:gz") as output:
                item = tarfile.TarInfo("../escape.json")
                item.size = 2
                output.addfile(item, io.BytesIO(b"{}"))
            with self.assertRaisesRegex(ValueError, "Unsafe"):
                entrypoint.unpack_bundle(archive, root / "data")

    def test_regular_archive_extracts(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            archive = root / "bundle.tar.gz"
            with tarfile.open(archive, "w:gz") as output:
                item = tarfile.TarInfo("data/1.json")
                item.size = 2
                output.addfile(item, io.BytesIO(b"{}"))
            entrypoint.unpack_bundle(archive, root / "bundle")
            self.assertEqual((root / "bundle/data/1.json").read_text(), "{}")
