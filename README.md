# pingtunnel-client

[<img src="https://img.shields.io/github/license/esrrhs/pingtunnel-qt">](https://github.com/esrrhs/pingtunnel-qt)
[<img src="https://img.shields.io/github/languages/top/esrrhs/pingtunnel-qt">](https://github.com/esrrhs/pingtunnel-qt)
[<img src="https://img.shields.io/github/v/release/esrrhs/pingtunnel-qt">](https://github.com/esrrhs/pingtunnel-qt/releases)
[<img src="https://img.shields.io/github/downloads/esrrhs/pingtunnel-qt/total">](https://github.com/esrrhs/pingtunnel-qt/releases)
[<img src="https://img.shields.io/github/actions/workflow/status/esrrhs/pingtunnel-qt/go.yml?branch=master">](https://github.com/esrrhs/pingtunnel-qt/actions)

GUI client for [pingtunnel](https://github.com/esrrhs/pingtunnel). Fill in the parameters, click GO, and a normal ping value means the connection is ready. Closing the window minimizes the app to the system tray; click the tray icon to reopen it.

The UI is built with Go and [Fyne](https://fyne.io/), and supports Windows, macOS, and Linux. See `version.go` for the current version.

## Usage

1. Launch the app
2. Fill in server, listen port, target, and other parameters
3. Enable TCP mode / sock5 as needed; congestion control defaults to `bb` (bandwidth-adaptive, so heavy traffic is less likely to saturate the link)
4. Click GO
5. After a ping value appears, you can close the window; the app keeps running in the tray

**STOP** stops the tunnel. **EXIT** or tray **Quit** exits the app. The last-used parameters are saved as `.pingtunnel-qt.json` in the same directory as the executable.

ICMP tunnels on Windows usually require administrator privileges.

## Build

Requires Go 1.26+ and a C compiler. On Windows, MSYS2 gcc works.

```bash
# Windows: -H windowsgui is required, otherwise a console window pops up
go build -ldflags="-H windowsgui" -o pingtunnel-qt.exe .

# macOS / Linux
go build -o pingtunnel-qt .
```

```bash
go test ./...
```

## Release

The version number is the `Version` constant in `version.go`. Change it to a version that has not been published yet (for example `2.0.1`) and push to `master`. GitHub Actions will then:

1. Check whether that version already has a GitHub Release
2. If not, build and package on Windows, macOS, and Linux
3. Create a `vVERSION` Release and upload archives for all three platforms

Pushing the same version again will not create another release. You can also run the `Release` workflow manually from Actions.

## Download

https://github.com/esrrhs/pingtunnel-qt/releases
