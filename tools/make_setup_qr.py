"""Print a setup QR code for the Light Phone Home Assistant tool.

The phone scans it in Settings > Scan setup code (or SET UP on a fresh install). The code holds
the server address and, optionally, a Cloudflare Access service token:

    {"url": "https://ha.example.com", "cf_client_id": "....access", "cf_client_secret": "cfast_..."}

The secret is asked for without echo, so it never lands in your shell history, and the code is
drawn in the terminal instead of saved to a file. Clear the terminal afterwards.

Requires: pip install qrcode
Usage:
    python tools/make_setup_qr.py https://ha.example.com
    python tools/make_setup_qr.py https://ha.example.com --cf-client-id 0123abcd.access
"""

import argparse
import getpass
import json
import sys

try:
    import qrcode
except ImportError:
    sys.exit("Missing dependency: pip install qrcode")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("url", help="Home Assistant address, e.g. https://ha.example.com")
    parser.add_argument("--cf-client-id", help="Cloudflare Access service token client ID (ends in .access)")
    args = parser.parse_args()

    url = args.url.strip().rstrip("/")
    if not url.startswith(("https://", "http://")):
        sys.exit("The address must start with https:// or http://")

    if args.cf_client_id:
        secret = getpass.getpass("Cloudflare client secret (not shown): ").strip()
        if not secret:
            sys.exit("No secret entered")
        payload = json.dumps(
            {"url": url, "cf_client_id": args.cf_client_id.strip(), "cf_client_secret": secret},
            separators=(",", ":"),
        )
    else:
        # A bare URL means "no Cloudflare Access" to the phone.
        payload = url

    qr = qrcode.QRCode(border=2, error_correction=qrcode.constants.ERROR_CORRECT_L)
    qr.add_data(payload)
    qr.make(fit=True)
    # Dark-on-light for typical dark terminals; drop invert=True if your terminal is light.
    qr.print_ascii(invert=True)
    print("Scan with Settings > Scan setup code. Clear the terminal when done.")


if __name__ == "__main__":
    main()
