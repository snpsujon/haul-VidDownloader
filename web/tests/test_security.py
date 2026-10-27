import pytest

from app import security


@pytest.mark.parametrize("url", ["ftp://example.com/a", "file:///etc/passwd", "example.com", "", "http://"])
def test_rejects_non_http(url):
    with pytest.raises(security.BadLink):
        security.check_url(url)


@pytest.mark.parametrize("url", ["http://127.0.0.1:8000/", "http://localhost/", "http://169.254.169.254/latest/meta-data", "http://10.0.0.5/x", "http://[::1]/"])
def test_rejects_private(url):
    with pytest.raises(security.BadLink):
        security.check_url(url)
