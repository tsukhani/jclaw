"""IP-range half of the SSRF check, mirrored from the JVM's SsrfGuard (JCLAW-1088).

This is a SECOND implementation of a security check, which is a cost worth naming.
The JVM guard stays authoritative for the entry URL; this exists because the browser
follows redirects and loads subresources on its own, and the sidecar has to decide
about those hosts without a round trip per request.

Kept stdlib-only and free of any Patchright import so StealthBrowserTest can run it
against the same address table the Java guard is fed and fail when the two drift.
"""

import ipaddress
import socket

EXTRA_BLOCKED = (
    ipaddress.ip_network("100.64.0.0/10"),   # carrier-grade NAT
    ipaddress.ip_network("fec0::/10"),       # deprecated v6 site-local
)


def is_public_ip(addr):
    """True when `addr` (a string or ip_address) is publicly routable.

    Covers SsrfGuard.isUnsafe and then some. Exact parity is NOT the invariant --
    this side must never be more permissive, and it is allowed to be stricter, which
    is what StealthBrowserTest asserts.

    EXTRA_BLOCKED carries the ranges `ipaddress` does not classify for us: fec0::/10
    is neither private nor reserved to it, though Java rejects it as site-local, and
    100.64.0.0/10 is not private on every Python version while Java rejects it as
    carrier-grade NAT reaching the ISP's own equipment.
    """
    try:
        ip = ipaddress.ip_address(addr)
    except ValueError:
        return False
    for net in EXTRA_BLOCKED:
        if ip.version == net.version and ip in net:
            return False
    return not (ip.is_private or ip.is_loopback or ip.is_link_local
                or ip.is_multicast or ip.is_reserved or ip.is_unspecified)


def is_public_host(host):
    """True when every address `host` resolves to is publicly routable.

    ALL addresses must pass: a hostname answering with one public and one private
    address would otherwise be admitted on the strength of the public one.
    """
    try:
        infos = socket.getaddrinfo(host, None)
    except (OSError, UnicodeError, ValueError):
        # An over-long or empty DNS label raises UnicodeEncodeError from the idna
        # codec, not OSError. Catching only OSError let it escape the route handler,
        # leaving the request neither continued nor aborted until the render timed out.
        return False
    return bool(infos) and all(is_public_ip(i[4][0]) for i in infos)


def is_allowed_proxy_ip(addr):
    """True unless `addr` is link-local, multicast or unspecified -- SsrfGuard.isBlockedForProvider.

    The operator's scrape proxy (JCLAW-1271) may sit on loopback or the LAN, so the rule is the
    provider one rather than is_public_ip: only the ranges that are never a proxy are refused.
    """
    try:
        ip = ipaddress.ip_address(addr)
    except ValueError:
        return False
    # Python 3.12.3 (Ubuntu 24.04) classifies ::ffff:169.254.169.254 by its v6 form; Java unwraps it.
    if ip.version == 6 and ip.ipv4_mapped:
        ip = ip.ipv4_mapped
    return not (ip.is_link_local or ip.is_multicast or ip.is_unspecified)


def is_allowed_proxy_host(host):
    """True when every address `host` resolves to passes is_allowed_proxy_ip."""
    try:
        infos = socket.getaddrinfo(host, None)
    except (OSError, UnicodeError, ValueError):
        return False
    return bool(infos) and all(is_allowed_proxy_ip(i[4][0]) for i in infos)
