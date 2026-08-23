# ChonkCraft online multiplayer

ChonkCraft keeps its deterministic host-authoritative game protocol and puts a
small room service in front of it. The service solves discovery and home-router
reachability; it does not simulate the game, choose commands, parse maps, or
rewrite lockstep packets.

## Player experience

- **Host Public Game** publishes a compatible waiting room in the browser.
- **Host Private Game** creates the same room and relay but omits it from the
  browser. Its six-character code and invite are the only discovery path.
- **Join Online Game** opens the public browser. A code can be typed or pasted
  in the same screen.
- **Direct / LAN** remains available beside online browsing, with LAN discovery
  and direct address entry even when the ChonkCraft service is unavailable.
- **Host Direct IP Game** and **Join Direct IP Game** never contact the room
  service. The host chooses a
  UDP port, sees the router and firewall requirements before binding it, and
  shares `public-ip:port`. Joiners use **Direct / LAN** and may enter IPv4, a
  hostname, or bracketed IPv6. UDP 7100 is the default; only the host forwards
  the selected port. UDP 7099 is LAN discovery and is not an internet game port.
- The lobby displays the share code and copies a canonical invite URL. Game and
  map versions are checked before a seat is granted. An incompatible client
  receives the host's required release, its own release, and a **Quit to
  Update** action instead of a cycle-one desync.

## Network design

All players open an outbound `wss://` connection. This is the key reachability
property: no player forwards a router port and no public IP address is exposed
to another player. The relay authenticates a 256-bit, room-scoped ticket and
adds only a five-byte source/destination envelope around the existing datagram.
Two one-byte host-only lifecycle messages hide a started room and remove an
explicitly cancelled room without waiting for expiry.

Direct IP mode bypasses this service and relay entirely. The existing datagram
protocol binds the host's selected UDP port and joining clients use ephemeral
outbound ports. Internet hosting therefore requires an operating-system
firewall allowance and a router UDP forward to the address shown by the setup
screen. LAN discovery advertises that same lobby; it is a convenience over the
direct protocol, not a second game transport. A host behind carrier-grade NAT
cannot accept an ordinary router forward and should use Online mode instead.

```text
public browser / room code ── HTTPS ──> room directory
host lobby packets           ── WSS ──> opaque relay ── WSS ──> joiner
joiner lobby packets         ── WSS ──> opaque relay ── WSS ──> host
lockstep command datagrams   ── same authenticated path, unchanged bytes ──>
```

The host remains authoritative. Joiners address only endpoint zero; only the
host may forward to another endpoint. The relay never accepts a client-supplied
source identity. `GameLobby` still proves the host's exact map SHA-256 and bytes,
and `NetworkSession` still authenticates the claimed player against the host
relay topology.

## In-game messages and roster

The controls follow the Battle.net Edition manual: **Enter** opens the message
line, **Enter** sends it, **Escape** cancels it, and the **Messages** control at
the top-right of the battlefield selects the recipients. The connected-player
panel keeps the eight player colours and offers Everyone, mutual Allies, or an
individual set. Its Mute control is local: it hides that player's later text
and sound without changing the match, recipient choices, or another player's
client. Departure and connection notices are system events and are never
silenced by a player mute.

Conversation is side-band data, not a `GameCommand`. It cannot delay a net
cycle, enter the sync hash, or desynchronise two worlds. The same authenticated
host topology carries it in both Direct/LAN and Online games. Sender identity
comes from the settled lobby slot, text is valid bounded UTF-8, recipients are
an explicit player bitmask, repeated UDP packets are deduplicated, and only the
host forwards a client's packet. The public relay also deduplicates and limits
client-originated message bursts before they reach a game process; the host
enforces the same short burst for direct games.

## Exact-build admission

Every published game JAR carries its authenticated release identifier into
both `chonkcraft.version` and `chonkcraft.network.build`. The value belongs to
the installed child JAR, not to the separately packaged launcher. Multiplayer
requires exact equality because even a one-line gameplay fix can change a
lockstep outcome.

The rule is enforced in two independent places:

1. The online room directory filters browsing by build and returns HTTP 426 to
   a code join from another build. No relay seat or ticket is issued.
2. Lobby wire version 5 carries the build in `JOIN`, authoritative `STATE`, and
   `START`. A direct or relayed host compares it before allocating a player
   slot. A rejected peer receives no roster seat, map chunk, readiness state,
   or start packet.

LAN announcements carry the same build. An incompatible LAN game is marked
**Update** and cannot be contacted from the browser. A typed direct address is
still protected by the lobby handshake, so every participant joining a
multi-player direct host is checked individually. Older lobby wire versions
are never admitted.

Temporary WebSocket loss is treated like UDP loss. The client reconnects with
bounded backoff while lobby heartbeats and lockstep retries replace missing
datagrams. It deliberately does not queue traffic during an outage, because
releasing stale movement packets after reconnect would be worse than dropping
them.

## Room lifecycle and abuse boundaries

- Six characters use an ambiguity-free 31-symbol alphabet (about 30 bits).
- Public and private waiting rooms have the same security properties.
- Host and relay credentials are cryptographically random and are never placed
  in URLs; the WebSocket uses an Authorization header.
- Create, join, and browse have separate per-address rate limits in the service,
  with an additional ingress rate limit.
- Unused join reservations expire after 20 seconds, disconnected seats have a
  30-second reconnect window, and a host-silent room expires after 90 seconds.
- Rooms disappear from browsing as soon as the host enters STARTING or PLAYING.
- Payloads above the game's 1,200-byte datagram budget are refused.
- Chat payloads have a separate 384-byte UTF-8 limit and six-message/five-second
  participant burst.

Rooms and live sockets are intentionally memory-resident. The deployment is
therefore one `Recreate` replica. Horizontal scaling requires a shared room
directory plus sticky relay ownership; increasing `replicas` before that work
would split healthy rooms between processes.

## Running locally

```bash
scripts/jbr/with-jbr-25.sh mvn -DskipTests package
MATCHMAKER_PORT=9092 \
PUBLIC_RELAY_URI=ws://127.0.0.1:9092/relay \
scripts/jbr/with-jbr-25.sh java -jar \
  matchmaker-server/target/chonkcraft-matchmaker.jar

# Point a development game at it:
-Dchonkcraft.matchmaker.url=http://127.0.0.1:9092
```

The Kubernetes manifests are in `infra/k8s/matchmaker`. Production terminates
TLS at ingress and advertises `wss://match.chonkbase.net/relay`. The image
workflow publishes immutable commit tags to GHCR after the real two-client
relay/map/start test passes. A master push then authenticates with a dedicated
Kubernetes identity that can update only the four named ChonkCraft matchmaker
resources, applies the immutable commit image, waits for the single-replica
rollout, verifies the public TLS health endpoint, and runs a production protocol
smoke through the newly deployed service. A second job on the authenticated BNE
runner then drives two rendered game clients through that public service. The
deployer cannot read Secrets or mutate the existing ChonkBlocker relay in the
shared namespace.

Cluster bootstrap is deliberately separate from ordinary releases: an
administrator applies `deployer-rbac.yaml` and the kustomization once, then
stores the resulting narrow kubeconfig as the repository secret
`CHONKCRAFT_MATCHMAKER_KUBECONFIG_B64`. Subsequent master releases cannot use
that credential to create new privileges or unrelated workloads.

## Proof

`MatchmakerEndToEndTest` starts a real Netty service, creates and browses a
public room over HTTP, joins it by normalized code, opens two authenticated
WebSockets, transfers a multi-chunk map through the unchanged `GameLobby`
protocol, commits the final lobby roster, and proves the started room leaves
the browser. Its incompatible-build lane proves HTTP 426 names both releases.
`GameLobbyTest` proves a stale direct client gets no slot or map while a current
client joins the same host. The same end-to-end relay test carries a chat line
in both directions and proves the service drops the seventh message in one
burst. `ChatTransportTest` covers selected-recipient routing and UTF-8 bounds;
`InGameChatVisualTest` renders the live message line, roster, recipient controls,
and mute state and drives Enter-to-send into the real network session.
The settled lobby also carries its game template. In **Top vs Bottom**, the
host assigns colours/start slots and players whose fixed map starts occupy the
same vertical area enter as mutual allies with mutual shared vision; the
two-process network gate starts this mode and requires both peers to report the
same template before comparing their final simulation hashes.
`MultiplayerVisualTest` renders the online browser, local fallback,
invite lobby, and the retry/local recovery screen at design, laptop, and
widescreen sizes for image review.

`ProductionMatchmakerSmokeTest` is an opt-in post-deploy proof against
`match.chonkbase.net`: it creates a private room, occupies both WSS relay seats,
forces a deliberately incorrect joiner map through exact host replacement,
starts the lobby, and carries game batches in both directions. The workflow then
runs `scripts/check-bne-network-gate.sh` with an authenticated BNE pack. Its two
independent `NetworkPeer` JVMs repeat the map-replacement and lobby path, render
both 640x480 battlefields and reject all-black output, advance 180 cycles, and
require the same final simulation hash. This distinguishes a live web service
from a multiplayer game that players can actually enter and see.
