# Online Multiplayer Recovery

- Fixed a multiplayer startup race that could assign a client the wrong local player and produce an all-black, sightless battlefield.
- Added a production gate that drives two real rendered game clients through the public HTTPS/WSS service, replaces a deliberately wrong joiner map with the host's exact bytes, and proves 180 lockstep cycles end with identical world hashes.
- Added a post-deploy room/relay/map/start/game-traffic smoke test, so a healthy web endpoint alone can no longer certify multiplayer.
