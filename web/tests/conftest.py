import os
import tempfile

os.environ.setdefault("HAUL_AUTO_UPDATE", "0")
os.environ.setdefault("HAUL_DATA_DIR", tempfile.mkdtemp(prefix="haul-test-"))
