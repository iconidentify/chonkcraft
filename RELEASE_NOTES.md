# Gameplay Reliability Update

- Fixed combat units occasionally accepting a move command and then stopping
  at the end of their previous attack step. Footmen, archers, siege engines
  and destroyers now finish the committed animation and continue to the new
  destination without requiring a separate Stop command.
- Restored Battle.net's per-unit rescue checks for allies that are working,
  moving, or using the building animation path. The workers and buildings in
  Human campaign mission 8 can now join the player when approached, without
  incorrectly transferring every unit owned by that rescue slot.
- Restored artillery splash against wall terrain. Catapults, ballistas and
  naval cannon now deal full damage to the impact wall and quarter damage to
  its four cardinal neighbours, matching the original Battle.net resolver.
- Fixed loaded oil tankers stopping short of a refinery when a legal route
  anchor touched coastline. Tanker returns now use the original Battle.net
  route-grid spread before choosing the refinery edge, including mixed
  horizontal, vertical, and ordinary shoreline approaches.
- Fixed loaded workers walking into a moving gold-return convoy after long
  congestion. They now wait out the original Battle.net movement cadence and
  replan when the route opens, while already-consumed return routes keep their
  existing retry behavior.
- Fixed a Battle.net-compatible woodcutting stall where a worker could choose
  a replacement tree whose only reachable side was occupied. Claimed-tree
  searches now continue to the next usable tree without changing ordinary or
  computer-controlled harvesting searches.
- Kept small warships on their consumed Battle.net patrol route when a moving
  allied hull temporarily blocks the final heading. Fresh terminal patrols,
  coast refusals and combat chases retain their distinct native behavior.
- Fixed oil tankers disappearing after building an oil platform or returning
  oil through an oppositely aligned shipyard or refinery. A tanker now finds a
  valid visible exit while retaining the original Battle.net movement grid.
- Fixed destroyers and other large combat ships that could be created on an
  invalid movement anchor and then sail forever past a right-clicked point as
  though they were patrolling. Newly trained ships surface on a valid anchor,
  and legacy/custom-map ships can recover on their next player move.
- Made directly commanded ballistas and catapults retain a live building
  target instead of silently switching to a nearby unit during their approach.
  Automatic targeting and attack-move behavior remain unchanged.
- Kept the gameplay safeguards separate from Battle.net parity execution:
  oracle and certification worlds still reproduce the authenticated original
  behavior, while normal single-player and multiplayer games receive the
  control-reliability fixes.
- Extended the authenticated Battle.net harness so real UI right-clicks can
  carry an exact unit target, and used it to audit both the human ballista and
  orc catapult paths against the pinned 2.02b binary.
