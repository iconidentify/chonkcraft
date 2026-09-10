# Replacement Commands Cancel Unfired Attacks

- Fixed attackers firing one more shot at their old target when an Attack or
  Move command arrives on the firing cycle.
- Projectiles already in flight still finish their damage, as in Battle.net
  Edition. The replacement keeps its native movement and attack timing.
- Verified the firing boundary against fresh Battle.net Edition captures,
  including cancellation and command handoff across save/load.
