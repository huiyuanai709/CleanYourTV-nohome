# TCL fork notes (CleanYourTV-nohome)

This repository is a personal fork of [Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv) tuned for **TCL Android TV** sideload via MultiScreen.

## Why no `category.HOME`

Stock CleanYourTV declares `android.intent.category.HOME`. On some TCL firmwares that blocks install or update through **MultiScreen Interaction** (cast/sideload). This fork removes that category so the APK can be installed that way.

Home is still reachable: enable the launcher's accessibility service (`HomeKeyService`). It watches window events and, when the stock home / cyberui surfaces, sends Home so CleanYourTV comes back.

## Tested device

- **TCL x32a0**, Android 9
- Dock: Settings, MultiScreen, Media Center, Live TV (4 large tiles, Sweden theme)
- Version line: `2.6-big-tiles-sweden` (see Releases for the matching APK)

## Companion remote

LAN web / CLI remote for the same TV family:

- [huiyuanai709/tcl-remote-dotnet](https://github.com/huiyuanai709/tcl-remote-dotnet)

Use it for power, navigation, volume, and source macros over the local network. It does not replace the launcher.

## Upstream

Bug fixes and themes should generally go upstream when they are not TCL-specific. This fork keeps only the no-HOME + accessibility Home path and TCL dock/banner defaults.
