# Home (Light Phone Home Assistant client)

A text-first Home Assistant client for the Light Phone III, built as a LightOS tool on the
[Light SDK](https://github.com/lightphone/light-sdk). Areas, devices and controls are drawn with
the SDK's Light components. It's a native client, not a web page.

- Tool code: [`tool/`](tool/) (`com.candytaco.lighthome`)
- Plan and status: [PLAN.md](PLAN.md)
- Light's tool requirements: [TOOL_GUIDELINES.md](TOOL_GUIDELINES.md)

## Repository layout

| Path | What it is |
|---|---|
| `tool/` | The Home Assistant tool |
| `sdk/client`, `sdk/ui`, `sdk/shared` | Light SDK libraries the tool compiles against |
| `sdk/server`, `sdk/emulator` | LightOS emulator app, for local testing ([setup](docs/system_app)) |
| `plugin/`, `lint-rules/` | Light SDK build plugin and lint rules (dependency and API allowlists) |
| `docs/` | SDK docs kept for reference: emulator, sideloading, `lighttool.toml` |

## Running a debug build

1. `./gradlew :tool:installDebug` (for the LightOS emulator, switch `serverPackage` in
   `tool/lighttool.toml` and add `-DlightSdk.allowAltServerPackage=true`).
2. Point the phone at your server, either way:
   - **Setup QR code:** `pip install qrcode`, then
     `python tools/make_setup_qr.py https://ha.example.com --cf-client-id <id>.access`
     (omit `--cf-client-id` if the server isn't behind Cloudflare Access; the secret is asked for
     without echo). On the phone: **SET UP** (or Settings → Scan setup code) and scan.
   - **Debug defaults:** copy `tool/dev.properties.example` to `tool/dev.properties` (git-ignored,
     debug builds only) before building. Settings saved on the phone take precedence.
3. Sign in to Home Assistant on the phone, then pick your dashboard in Settings.

`tools/` holds helpers that run on your computer; it isn't part of the tool's build.
