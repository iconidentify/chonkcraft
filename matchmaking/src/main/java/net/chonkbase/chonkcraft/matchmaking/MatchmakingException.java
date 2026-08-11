package net.chonkbase.chonkcraft.matchmaking;

import java.io.IOException;

/** An actionable refusal or outage from the room service. */
public final class MatchmakingException extends IOException {

    private final int status;
    private final String code;

    public MatchmakingException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code == null ? "unknown" : code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
