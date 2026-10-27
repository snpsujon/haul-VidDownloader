"""Link checks: only http(s), and no private or loopback hosts unless allowed."""
import ipaddress
import socket
from urllib.parse import urlparse

from . import config


class BadLink(ValueError):
    pass


def check_url(url: str) -> str:
    url = (url or "").strip()
    if len(url) > 2048:
        raise BadLink("That link is too long.")
    parsed = urlparse(url)
    if parsed.scheme not in ("http", "https") or not parsed.hostname:
        raise BadLink("Paste a full link that starts with http:// or https://.")
    if not config.ALLOW_PRIVATE:
        _check_host(parsed.hostname)
    return url


def _check_host(host: str) -> None:
    try:
        infos = socket.getaddrinfo(host, None)
    except socket.gaierror:
        raise BadLink("Couldn't find that website. Check the link.")
    for info in infos:
        ip = ipaddress.ip_address(info[4][0].split("%")[0])
        if (ip.is_private or ip.is_loopback or ip.is_link_local or ip.is_reserved
                or ip.is_multicast or ip.is_unspecified):
            raise BadLink("That link points to a private address.")
