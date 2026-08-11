# Multiplayer departure policy

This document is the contract for a player leaving a running network game. It
separates behaviour recovered from Warcraft II Battle.net Edition 2.02b from a
small, explicit ChonkCraft team-continuation extension.

## Retail baseline

The pinned 2.02b executable has separate resources for `Quit (Draw)`,
`Surrender`, and the battle timeout dialog. Its timeout path clamps the wait to
45 seconds and asks the network service to drop each dead player. The manual's
team guidance tells a player with useful units to remain and help the teammate;
it does not describe automatic unit transfer. Therefore a dropped player's
forces are not converted to an ally as a claim of retail parity.

## Running-game rules

1. Every player sends empty lockstep batches when they have no orders. Silence
   is consequently a connection failure, not an idle player.
2. Only the host adjudicates a non-host timeout. After 45 seconds it inserts a
   `QUIT/TIMEOUT` into the exact net cycle on which all survivors are stalled
   and broadcasts that decision on behalf of the absent slot.
3. Clients never time out a third player independently. They wait for the host's
   decision. A client that loses the host ends the match; there is no host
   migration in this protocol.
4. An intentional exit sends five `QUIT/LEFT` copies before closing. Packet
   loss still falls back to the host timeout.
5. A future QUIT does not remove its player early. That player remains required
   through every preceding net cycle and becomes inactive only when the QUIT is
   released.
6. Late packets from an inactive slot are ignored and are not relayed.
7. The screen names the departed player, distinguishes leaving from a dropped
   connection, counts down the timeout, and says when the host's departure has
   ended the match.

## Team continuation extension

At the departure cycle, every active **mutual** ally receives command authority
over the departed slot. A one-way alliance is not enough. The authority set is
carried in the QUIT command, so all machines install the same set on the same
cycle.

This is shared control, not ownership transfer:

- units and buildings retain their owner and colour;
- supply, scores and resources remain in the departed player's bank;
- production and research spend that owner's resources;
- allies may select and order the units through the normal command path;
- allies receive the departed slot's vision while they control it;
- enemies and non-allies remain unable to command them;
- if there is no active mutual ally, the forces remain owned but receive no
  player orders.

When several allies command the same departed unit in one net cycle, ordinary
lockstep player order resolves the conflict identically on every machine.

## Trust boundary

The player number inside a UDP packet is authenticated against the source
address settled by the lobby. A client also trusts the settled host address as
the relay. Commands inside the packet must name the same issuing player. This
allows a host to carry an adjudicated timeout without allowing another client
to forge somebody else's orders or departure.

The lobby protocol carries the host's actual slot. Moving the host away from
slot zero therefore does not break relay authentication or host-loss handling.

## Deliberate limits

- There is no mid-match reconnection or host migration.
- Surrender remains a distinct game action and must not be inferred from a
  network failure.
- The continuation rule consumes the world's synchronized diplomacy table; a
  future multiplayer team-selection UI must populate that table rather than
  inventing a second notion of team.
