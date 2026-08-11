FROM debian:bookworm-slim

ARG DEBIAN_FRONTEND=noninteractive

RUN dpkg --add-architecture i386 \
    && apt-get update \
    && apt-get install --yes --no-install-recommends \
        ca-certificates \
        gdb \
        libgl1-mesa-dri:i386 \
        libglx-mesa0:i386 \
        python3 \
        wine \
        wine32:i386 \
        wine64 \
        x11-apps \
        xauth \
        xvfb \
    && rm -rf /var/lib/apt/lists/*

COPY entrypoint.sh /usr/local/bin/bne-headless-entrypoint
RUN chmod 0755 /usr/local/bin/bne-headless-entrypoint

ENV DISPLAY=:99 \
    LIBGL_ALWAYS_SOFTWARE=1 \
    WINEARCH=win32 \
    WINEDEBUG=-all \
    WINEDLLOVERRIDES=winemenubuilder.exe=d \
    WINEPREFIX=/oracle/prefix

WORKDIR /oracle
ENTRYPOINT ["/usr/local/bin/bne-headless-entrypoint"]
