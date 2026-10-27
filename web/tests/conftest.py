import os
import tempfile

os.environ.setdefault("HAUL_DATA_DIR", tempfile.mkdtemp(prefix="haul-test-"))
