# BNE oil lifecycle proof

This is the durable evidence contract for Warcraft II Battle.net Edition
2.02b oil harvesting. The oil lane is **GREEN** only while all of the tests
and corpus checks below pass. A change to tanker routing, containment,
resource timing, destruction, cargo art, AI-ready dispatch, or save/load state
must run this gate again.

## Oracle identity

All native observations below come from the pinned English retail executable:

```text
Warcraft II BNE.exe
bytes   712704
sha256  b0e914a9cb7dcc81a205e700a9bb0a1d0649df19d459388051ba170783d2c807
```

The primary geometry and cadence witness is the sealed Orc 14 fixture:

```text
case       retail-orc-14-idle
scenario   Campaign\Orc\Orc14.pud
seed       1
cycles     1800
fixture id a27cd9506c1a88f35f92084e28dc241e2ced3e11687f9dc0bc3deeb834b129b1
trace sha  e72531b71ff3aca36a9f06b96dfb85cde1e62b8517478d54f3e125d9329350c1
state sha  6b845e8e02faab8a4e41b1dbf07bff86045c3b8eb4268a4ff8844f3077485b31
```

## Native state machine

The Java model names the resource substates by their retail action numbers;
these are not generic booleans or inferred presentation states:

| Raw action | Java state | Meaning |
|---:|---|---|
| 23 | `TO_RESOURCE` | sail or wait at the platform boarding seat |
| 24 | `TO_DEPOT` | carry the load to a valid oil depot |
| 25 | `FINAL_APPROACH` | three visible final boarding visits |
| 26 | `INSIDE_RESOURCE` | removed into the platform for the oil dwell |

The native order dispatcher reaches action 23 at `0x00436960` and action 24
at `0x00436ac0`. The final approach and contained-resource transitions are in
the branch family at `0x00424040`, `0x004240aa`, and `0x0042473c`.

Orc 14 tanker 1565 proves the resource cadence: action 23 through fixture
cycle 34, action 25 on cycles 35--37, action 26 on cycles 38--187, visible
Still on cycles 188--212, then action 24 on cycles 213--215. Both the platform
and depot stays are 150 cycles. The generated human and orc tanker roster
therefore declares `waitAtResource=150` and `waitAtDepot=150`; the test gate
measures entry and exit cycles rather than merely waiting for eventual oil.

## Geometry matrix

| Shape | Retail witness | Required result |
|---|---|---|
| Adjacent | Orc 14 tanker 1575 beside the western platform | three action-23 visits, three action-25 visits, then enter |
| Distant | Orc 14 tanker 1566 from 116,6 to the eastern platform | remain visible through the route, action 25 on cycles 101--103, enter on 104 |
| Diagonal | tanker 1566, doubled sea grid | execute `NE, NE, SE`; raw route bytes `01 01 03` |
| Congested | tanker 1576 stationary at 120,4 while 1566 plans | the stationary hull remains solid and causes the `NE, NE, SE` wall-follow |
| Multiple tankers | the two Orc 14 platform lanes and `OilLifecycleGateTest` | no starvation; each tanker can board and bank a load |
| Blocked boarding seat | `OilLifecycleGateTest.twoTankersSurviveOnePlatformLane` | the following tanker waits/reroutes and eventually boards rather than ending its order |
| Opposite-parity 3x3 depot | `TankerRoundTripTest.aTankerLeavesAnEvenAnchorShipyardAfterBankingOil` | a contained tanker uses a fail-safe aligned exit instead of disappearing permanently |

The route explanation is important. Native `0x00450690` draws the ordinary
doubled-delta line with `0x00429f10`/`0x00429fa0`; it does not use a special
half-grid line. The free line for tanker 1566 would be `NE, E, NE`, but its
second anchor is occupied by stationary tanker 1576. Native `0x004500f0`
wall-follows around that blocker and stores `NE, NE, SE`. At `0x004507b5`, an
allied unit is soft-cleared only when its internal action byte is Move (3).
A HARVEST order by itself does not make a stationary tanker transparent.

This replaces two incorrect Java approximations: unconditional soft-clearing
of fellow harvesting tankers and a narrow 3-by-2 half-grid route exception.
The same rule also preserves the nearby 1576 route, which legitimately takes
`NE` to 122,2 and enters on cycle 41.

## Platform builder and failures

The authenticated Human 14 native capture
`diag-human14-oil-builder-autohaul` has fixture id
`2cfe1ae2f6d439983f99ae838427861944a3a1764760234b8459f56d47a7636c`
and trace SHA-256
`a68d6349aa89ef5ed3114e4495f653ed21968b208101c49331ffbe28fbeabf64`.
Tanker 1406 remains removed while BUILD through cycle 3068, changes directly
to HARVEST while removed on 3069, surfaces laden on 3220, and changes to the
return leg on 3245. A tanker that completes a platform therefore begins its
first haul without another player or AI order.

The lifecycle gate also proves:

- a destroyed platform releases and reroutes its contained tanker;
- a destroyed selected depot makes a laden tanker select another depot;
- a depleted platform disappears, the final load remains conserved, and the
  empty tanker selects another oil field;
- a 100-oil load banks as 125 with a refinery's 25% bonus;
- empty and laden tanker artwork follows actual cargo state; and
- the raw oil state and cadence survive save/load.

## Return-order and save boundary

Raw action 24 (`TO_DEPOT`) is the authoritative retail state for a laden
tanker. The Java navigation projection (`returningToDepot`, remembered depot,
and weak return goal) must agree with it and must be serialized for visible
workers as well as workers contained in a platform or depot. Schema-2 saves
which predate those fields are repaired from action 24 and select a live depot
again.

The reported odd-anchor geometry is covered directly: a full 2x2 tanker can
resume from a legacy off-lattice anchor, finish the cached approach to a
3x3 shipyard, bank its load, and leave no repeating route. BNE's resource
order interprets a cached next anchor that enters the depot footprint as
`PF_REACHED`; it is not a collision to retry every fifteen cycles. Ordinary
large ships still use BNE's doubled even-anchor lattice. Only a command which
starts from an invalid legacy odd anchor uses a single-lattice recovery route,
so the compatibility repair does not alter authenticated normal routes.
The inverse custom-map case is a 3x3 shipyard whose anchor gives every native
resource-exit face the wrong parity. The pinned executable's placement
callback at `0x004512a0` tests the doubled-movement flag at `0x004512bb`, then
`0x004512c0`--`0x004512ca` rejects a candidate if either coordinate is odd.
Java therefore keeps the native absolute-even test and face-first search. Only
after the face geometry proves that search can never intersect the required
grid does it walk a parity-changing side ring for an aligned free square.
The captured player save adds the interrupted-command boundary: its tanker is
at `(53,18)` with `IX=-36`, a Move goal of `(53,18)`, cargo 100 and action 24.
Without another command it now finishes the owed pixels, centres on that tile
and becomes Still instead of snapping west and east forever. A subsequent
Return with Goods reaches the saved shipyard and deposits the complete load.

The mission-four platform-builder report adds the order-ownership boundary.
Its saved human tanker carried 100 oil at `(23,4)` with native action 24 but
Java `STILL`; the authoritative native substate and its outer resource order
had split. Load compatibility now rejoins that orphaned action 24 to its
resource order. When the restored anchor is outside the native doubled lattice
or cannot produce a native route, it also selects the nearest free
absolute-even water anchor toward the depot. Valid live platform exits are not
relocated. An explicit player Stop destroys action 24, so compatibility repair
cannot revive a command the player deliberately cancelled. The exact reported
save was replayed without another player order and banked its existing load.

### Subsystem-assessment rule learned here

An end-state test ("oil eventually arrived") was not enough to expose this
failure. Oil is represented across four coupled layers: the native action byte,
the outer resource order, navigation geometry and persisted save state. The
executable lifecycle gate now checks their contract on every simulated cycle:

- native actions 24, 25 and 26 must still be owned by the resource order;
- visible action 24 must project to a homeward leg with cargo and a depot
  route (after docking it can be hidden in the depot with the load banked);
- actions 25 and 26 must progress through board/inside/exit rather than merely
  preserve their final result;
- saves are replayed from each boundary, including deliberately contradictory
  legacy tuples; and
- explicit cancellation must destroy the native substate so compatibility
  repair cannot resurrect a command the player cancelled.

Use the same assessment shape for other BNE subsystems: enumerate the native
states, name every Java projection of each state, assert the tuple after every
tick, checkpoint every transition, and add a bounded liveness outcome. That
finds split-brain state like this before a player discovers the eventual stuck
unit.

## Executable gate

Focused behavior and persistence:

```sh
mvn -q -pl engine \
  -Dchonkcraft.pack=/path/to/bne.chonkpack \
  -Dtest='BattleNetResourceApproachTest#adjacentTankerUsesNativeApproachState+boardSeatTankerHoldsCoverBeforeEnter+distantTankerDoesNotEnterOnTheLandingCall,OilLifecycleGateTest,TankerRoundTripTest,OilPlatformTest#aTankerFoundsAPlatformOnAPatchAndPumpsIt,WorkerSpriteTest,SaveGameTest#tankerOilStateRoundTrips,BattleNetPathFinderTest#orcFourteenTankerRoutesAroundStationaryTanker' \
  test
```

Authenticated Orc 14 check:

```sh
python3 tools/bne-harness/scripts/bne_java.py case \
  /path/to/corpus/cases/retail-orc-14-idle.bnefx \
  --asset-pack /path/to/bne.chonkpack \
  --output-dir /tmp/orc14-oil --through 600 --report-all
```

The 2026-08-10 proof reported no oil-tanker or oil-platform findings through
600 cycles. Its first remaining difference was unrelated critter movement at
cycle 57.

The 52-case candidate survey was compared against a clean-HEAD 600-cycle
survey: **PASS**, 52 cases, zero regressions. XHuman 5 improved from first
divergence 108 to 158; every other first-divergence cycle was unchanged.

The 2026-08-10 operator-save correction was independently compared against a
fresh clean-HEAD 600-cycle survey: **PASS**, 52 cases, zero regressions and
identical first-divergence cycles. The exact saved mission also completed a
fresh Return with Goods command within 3,000 engine cycles and credited the
oil bank.
