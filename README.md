# kotlin-xpra

A small [Xpra](https://github.com/Xpra-org/xpra) client for Android, in Kotlin and Jetpack
Compose. Sibling to [go-xpra](https://github.com/Xpra-org/go-xpra) and
[rust-xpra](https://github.com/Xpra-org/rust-xpra): the protocol is hand-written, with no xpra
dependencies and no networking or compression libraries.

It is a *phone* client, which is what shapes the design. There is no window manager metaphor,
no cursor and no keyboard, so instead of reproducing a desktop it shows **one top-level window
at a time, fullscreen and aspect-preserved**, composites popups on top of it, and adds a window
switcher, a soft-keyboard bridge, a clipboard widget and pinch-to-zoom.

## What works

- `tcp://` transport, rencodeplus framing, lz4 decompression, `hmac+sha256` authentication
- mDNS discovery of `_xpra._tcp` sessions on the local network, saved connections, manual URIs
- `png`, `png/P`, `png/L`, `jpeg`, `webp`, `rgb24` and `rgb32` picture encodings
- Server-side desktop scaling (default factor 2), re-reported on rotation
- Touch as pointer events, with long-press right-click, two-finger scroll and pinch zoom
- IME bridge for text plus a modifier row for the keys a phone keyboard lacks
- Clipboard, text only, both directions

## What it does not do

TLS, WebSocket and SSH transports; h264 and the other video encodings; the `scroll` encoding;
mmap; chunked packets; audio; window icons; notifications; file transfer; the system tray;
keymap upload. None of these are advertised in the hello, and the server sends nothing it was
not asked for, which is what keeps the client this small.

## Building

Requires a JDK (not just a JRE — Fedora's `java-25-openjdk` package ships without `javac`;
install `java-25-openjdk-devel`, or point `JAVA_HOME` at any JDK 17+):

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk
./gradlew assembleDebug
./gradlew installDebug          # to a connected device or running emulator
```

The Android SDK path goes in `local.properties` (`sdk.dir=...`); `compileSdk` is 37.

## Testing

The protocol layers are plain JVM Kotlin with no Android imports, so they test off-device:

```bash
./gradlew test
```

`RencodeplusTest` checks the encoder against the byte-exact vectors from the reference
implementation in `xpra-html5/html5/js/lib/rencode.js`, and `Lz4Test` against fixtures produced
by xpra's own compressor.

`ServerSmokeTest` talks to a real server and is skipped unless you point it at one. It is the
fastest way to iterate on the capability dictionary, since it exercises framing, rencodeplus,
the hello, `map-window` and the draw/ack loop without an emulator in the way:

```bash
./gradlew test --tests '*ServerSmokeTest*' -DxpraServer=192.168.0.10:10000
```

To have something to look at on the other end:

```bash
xpra start --bind-tcp=0.0.0.0:10000 --start=xterm
xpra control :7 start xterm     # add another window
```

## Layout

```
net/        framing, rencodeplus, lz4, socket        pure Kotlin, no Android imports
proto/      hello, challenge                          pure Kotlin
client/     state machine, windows, draw, input       + android.graphics.Bitmap
discovery/  NsdManager browse of _xpra._tcp
data/       saved connections, scaling factor
ui/         Compose screens and overlays
```

`XpraClient` follows go-xpra's concurrency model: one coroutine owns all mutable state and is
the only writer. The socket reader, the ping timer and the UI all post into a single channel,
and synthetic packets (`connection-lost`, `send-ping`) reuse the packet type as the internal
message type, so there is exactly one event loop.

## Notes on the protocol

A few things are load-bearing and easy to get wrong; they are commented at the site, but the
short list is:

- **rencodeplus tells text from binary by the separator alone** — `:` for UTF-8, `/` for
  bytes. Getting it backwards fails silently, because the peer sees `b"title"` where it expects
  `"title"` and every dictionary lookup misses.
- **The lz4 size prefix is little-endian**, unlike every other length field in the protocol.
- **Every `draw` must be acked**, including ones that fail to paint and ones for windows that
  no longer exist, or the server's damage accounting stalls that window.
- **Ping replies are not optional**: the server disconnects on `PING_TIMEOUT`.
- **`display` must be present in the hello** or `DisplayConnection` never loads and the desktop
  size — and therefore the whole scaling factor — is ignored.
- `chunks: false` makes the server inline binary payloads, which removes the entire
  chunk-reassembly path.

## Touch input

There was no prior art to copy — go-xpra and rust-xpra are mouse-only, the html5 client only
turns a one-finger drag into wheel events, and the server has no touch protocol at all. The
reasoning behind the mapping is in the header comment of
`ui/session/TouchInput.kt`, but in short:

- A **motion event always precedes a press**, so the remote toolkit gets the hover state it
  expects. Without it the pointer teleports and menus stop highlighting.
- **Double-clicks are never synthesized.** The remote toolkit does its own detection from the
  timing of two presses, so the pairs just need to arrive naturally. What does need handling is
  that two taps never land on the same pixel while toolkits require the second click within a
  few (GTK defaults to 5), so a second tap inside the double-tap window is **snapped onto the
  first one's coordinates**. That one change is what makes double-click work by touch.
- Tap → button 1, long press → button 3, drag → button 1 held (text selection), two-finger drag
  → wheel, pinch → client-side zoom only.
