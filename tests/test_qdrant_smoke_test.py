import importlib.util
from pathlib import Path
import unittest


SCRIPT_PATH = Path(__file__).parents[1] / "scripts" / "qdrant_smoke_test.py"
SPEC = importlib.util.spec_from_file_location("qdrant_smoke_test", SCRIPT_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class QdrantSmokeTest(unittest.TestCase):
    def test_safe_delete_rejects_non_smoke_collection(self) -> None:
        with self.assertRaisesRegex(ValueError, "non-smoke collection"):
            MODULE.safe_delete("http://localhost:6333", "fitzza_products_v1")

    def test_vector_contract_is_fashion_clip_dimension(self) -> None:
        self.assertEqual(MODULE.VECTOR_SIZE, 512)


if __name__ == "__main__":
    unittest.main()
