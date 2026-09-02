# udpgw-bridge

The desktop counterpart of Exclave's in-app UDP gateway: it lets UDP traffic ride a TCP-only
tunnel by re-framing it with the [badvpn](https://github.com/ambrop72/badvpn) `udpgw` protocol.

Exclave itself carries UDP over SSH on Android without any of this. This tool exists for Linux and
Windows desktops, where an `ssh -D` SOCKS5 proxy on its own can only carry TCP.

```
app --socks5/tcp--> bridge --socks5--> ssh -D --> internet
app --socks5/udp--> bridge --udpgw/tcp--> ssh -D --> badvpn-udpgw --> internet
```

## Requirements

- A JRE 11 or newer (`java -version`). The jar is plain Java with no native code, so the same file
  runs on Linux, Windows and macOS; only the launcher script differs.
  Windows note: run `udpgw-bridge.bat`, and start the tunnel with an `ssh -D 1080` from OpenSSH
  (bundled with Windows 10 1809 and later), PuTTY or any other SOCKS5 proxy.
- An SSH server you can reach, running `badvpn-udpgw` — typically:
  `badvpn-udpgw --listen-addr 127.0.0.1:7300 --max-clients 500`

## Build

```sh
./build.sh          # produces build/udpgw-bridge.jar
```

It uses `JAVA_HOME` when that points at a real JDK, and otherwise falls back to `javac` on `PATH`
or to any JDK under `/usr/lib/jvm`, so a stale `JAVA_HOME` is not fatal.

The jar is platform independent: the same file runs on Linux and Windows.

## Run

```sh
ssh -N -D 1080 user@server           # the tunnel
java -jar udpgw-bridge.jar           # the bridge
```

Then point applications at `socks5://127.0.0.1:1081`. TCP is relayed to the SSH proxy unchanged;
UDP goes over the udpgw tunnel.

| Option | Default | Meaning |
| --- | --- | --- |
| `--listen` | `127.0.0.1:1081` | SOCKS5 endpoint this tool publishes, TCP and UDP |
| `--upstream-socks` | `127.0.0.1:1080` | the SOCKS5 proxy to tunnel through, e.g. `ssh -D` |
| `--udpgw` | `127.0.0.1:7300` | the udpgw server, addressed as the tunnel exit sees it |
| `--max-connections` | `100` | concurrent UDP sessions kept on the udpgw server |

`--udpgw 127.0.0.1:7300` is resolved on the far side of the tunnel, so it means the SSH server's own
loopback — which is where `badvpn-udpgw` normally listens.

The convenience launchers `udpgw-bridge` (Linux/macOS) and `udpgw-bridge.bat` (Windows) find the
jar either next to themselves or under `build/`, so they work in a release and in a source tree.

## Notes

- udpgw addresses destinations by IP, so a SOCKS5 request carrying a domain name is resolved
  locally before the packet is sent.
- One connection id is allocated per client UDP endpoint, which keeps the server-side source port
  stable across destinations — the NAT behaviour games and VoIP need.
- The tunnel reconnects on its own with backoff, and every live connection id is rebound afterwards
  because the server keeps no state across reconnects.
