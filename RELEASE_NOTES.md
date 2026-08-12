# ChonkCraft 0.1.1-beta14 — BNE Combat and Action Scheduling

- Recreated the Battle.net Edition attack-program boundaries that decide when melee damage, projectiles, and impact effects become authoritative, even when presentation callbacks arrive early or late.
- Eliminated duplicate and delayed missiles by separating launch presentation from simulation ownership, preserving native constructor ordering, and matching the fixed-pool same-cycle impact cadence.
- Corrected the final construction pulse: completed buildings now wait for Battle.net Edition's last completion interval before becoming available and contributing supply.
- Recreated cooperative traffic refusal behavior so an attacking unit transfers into the native movement wait when its first heading is occupied, without restarting units already executing their movement program.
- Reduced early mobile action-cursor mismatches by 98 percent across the authenticated campaign evidence while preserving the accepted 52-campaign frontier with zero regressions or failed fixtures.
- Strengthened semantic parity evidence to compare exact action cursors, facing, and order points, making future scheduling differences visible instead of treating related action families as equivalent.
- Added focused regressions for melee timing, projectile preparation and impact cadence, construction completion, movement refusal, and semantic trace fidelity.
- Verified the full Java engine suite against the previous public baseline: the exact pre-existing failure set is unchanged, with five additional passing tests.
