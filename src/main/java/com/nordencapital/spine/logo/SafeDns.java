package com.nordencapital.spine.logo;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.apache.hc.client5.http.DnsResolver;

/**
 * DNS for the logo fetcher (SSRF guard). The HTTP client connects ONLY to the addresses
 * returned here, so a name that points (even partly) to a private, loopback, link-local,
 * cloud metadata or other special address is refused. Used again on every redirect.
 */
final class SafeDns implements DnsResolver {

	/** The real lookup (replaced in tests). */
	interface Lookup {

		InetAddress[] all(String host) throws UnknownHostException;

	}

	private final Lookup lookup;

	SafeDns(Lookup lookup) {
		this.lookup = lookup;
	}

	@Override
	public InetAddress[] resolve(String host) throws UnknownHostException {
		InetAddress[] all = this.lookup.all(host);
		if (all == null || all.length == 0) {
			throw new UnknownHostException(host);
		}
		for (InetAddress address : all) {
			if (!isPublic(address)) {
				throw new RefusedAddressException(host);
			}
		}
		return all;
	}

	@Override
	public String resolveCanonicalHostname(String host) {
		return host;
	}

	/** True only for normal public internet addresses. */
	static boolean isPublic(InetAddress address) {
		if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
				|| address.isSiteLocalAddress() || address.isMulticastAddress()) {
			return false;
		}
		byte[] b = address.getAddress();
		if (b.length == 4) {
			return isPublicV4(b, 0);
		}
		int b0 = b[0] & 0xff;
		int b1 = b[1] & 0xff;
		if ((b0 & 0xfe) == 0xfc) {
			return false; // fc00::/7 unique local
		}
		if (b0 == 0xfe && (b1 & 0xc0) != 0) {
			return false; // fe80::/10 and fec0::/10
		}
		boolean first10Zero = true;
		for (int i = 0; i < 10; i++) {
			first10Zero &= b[i] == 0;
		}
		if (first10Zero && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
			return isPublicV4(b, 12); // ::ffff:a.b.c.d
		}
		if (first10Zero && b[10] == 0 && b[11] == 0) {
			return false; // ::, ::1, ::a.b.c.d
		}
		if (b0 == 0x00 && b1 == 0x64 && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b) {
			return false; // 64:ff9b::/96 NAT64 (can reach private IPv4)
		}
		if (b0 == 0x20 && b1 == 0x01 && (b[2] & 0xff) == 0x0d && (b[3] & 0xff) == 0xb8) {
			return false; // 2001:db8::/32 documentation
		}
		if (b0 == 0x20 && b1 == 0x01 && b[2] == 0 && b[3] == 0) {
			return false; // 2001::/32 Teredo
		}
		if (b0 == 0x20 && b1 == 0x02) {
			return isPublicV4(b, 2); // 2002::/16 6to4 carries an IPv4 address
		}
		return (b0 & 0xe0) == 0x20; // only 2000::/3 is global unicast
	}

	private static boolean isPublicV4(byte[] b, int at) {
		int a = b[at] & 0xff;
		int c = b[at + 1] & 0xff;
		int d = b[at + 2] & 0xff;
		return !(a == 0 || a == 10 || a == 127 // this network, private, loopback
				|| (a == 100 && c >= 64 && c <= 127) // 100.64.0.0/10 carrier NAT
				|| (a == 169 && c == 254) // link-local, cloud metadata 169.254.169.254
				|| (a == 172 && c >= 16 && c <= 31) // private
				|| (a == 192 && c == 0 && (d == 0 || d == 2)) // 192.0.0.0/24, 192.0.2.0/24
				|| (a == 192 && c == 88 && d == 99) // 6to4 relay
				|| (a == 192 && c == 168) // private
				|| (a == 198 && (c == 18 || c == 19)) // benchmarking
				|| (a == 198 && c == 51 && d == 100) || (a == 203 && c == 0 && d == 113) // documentation
				|| a >= 224); // multicast, reserved, broadcast
	}

	/** The name points to an address we never connect to. */
	static final class RefusedAddressException extends UnknownHostException {

		RefusedAddressException(String host) {
			super("Refused non-public address for " + host);
		}

	}

}
