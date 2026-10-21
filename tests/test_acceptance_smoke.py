import importlib.util
import sys
from pathlib import Path

SCRIPT_PATH = Path(__file__).parents[1] / "scripts" / "acceptance_smoke.py"
SPEC = importlib.util.spec_from_file_location("acceptance_smoke", SCRIPT_PATH)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


def test_mask_secrets_hides_bearer_and_access_token():
    raw = 'Authorization: Bearer abc.def.ghi {"accessToken":"xyz.token.value"}'
    masked = MODULE.mask_secrets(raw, "super-secret-password")
    assert "abc.def.ghi" not in masked
    assert "xyz.token.value" not in masked
    assert "***" in masked


def test_mask_secrets_replaces_extra_secrets():
    assert MODULE.mask_secrets("pw=hunter2", "hunter2") == "pw=***"


def test_main_requires_base_url():
    assert MODULE.main([]) == 2
