# Frigate Docker image built from the official upstream release.
#
# Downloads the signed release tarball from github.com/sparrowwallet/frigate,
# verifies Craig Raw's PGP signature on the release manifest and the tarball's
# sha256, then copies it into a slim runtime image. The release bundles its own
# jlink Java runtime and both launchers (frigate, frigate-cli).
#
# GPU: NVIDIA via CUDA only. Frigate bundles its CUDA extension and the NVIDIA
# container runtime injects the driver (see the deploy: block in
# docker-compose.yml). No OpenCL loader or Intel/AMD runtimes are included, so
# on those GPUs computeBackend = "AUTO" falls back to CPU.
#
# Build:            docker build -t frigate .
# Other version:    docker build --build-arg FRIGATE_VERSION=x.y.z -t frigate .
# Multi-arch:       docker buildx build --platform linux/amd64,linux/arm64 -t frigate .

FROM debian:trixie-slim AS downloader

ARG TARGETARCH
ARG FRIGATE_VERSION=1.6.1
# Craig Raw <craig@sparrowwallet.com> - full fingerprint, pinned
ARG FRIGATE_PGP_FINGERPRINT=D4D0D3202FC06849A257B38DE94618334C674B40
ARG FRIGATE_PGP_KEY_URL=https://keybase.io/craigraw/pgp_keys.asc

# Each RUN below starts with `set -eu` so a failed check always aborts the
# build, even under Podman/Buildah where SHELL is ignored for OCI images.
SHELL ["/bin/bash", "-euo", "pipefail", "-c"]

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl gnupg \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /tmp/frigate

# Download release tarball, signed manifest and signing key
RUN set -eu; \
    case "${TARGETARCH:-amd64}" in \
        amd64) ARCH=x86_64 ;; \
        arm64) ARCH=aarch64 ;; \
        *) echo "Unsupported architecture: ${TARGETARCH}" >&2; exit 1 ;; \
    esac; \
    BASE="https://github.com/sparrowwallet/frigate/releases/download/${FRIGATE_VERSION}"; \
    curl -fsSL "${BASE}/frigate-${FRIGATE_VERSION}-${ARCH}.tar.gz" -o frigate.tar.gz; \
    curl -fsSL "${BASE}/frigate-${FRIGATE_VERSION}-manifest.txt" -o manifest.txt; \
    curl -fsSL "${BASE}/frigate-${FRIGATE_VERSION}-manifest.txt.asc" -o manifest.txt.asc; \
    curl -fsSL "${FRIGATE_PGP_KEY_URL}" -o signing-key.asc; \
    echo "frigate-${FRIGATE_VERSION}-${ARCH}.tar.gz" > tarball-name

# Verify the manifest is signed by the pinned key fingerprint
RUN set -eu; \
    export GNUPGHOME="$(mktemp -d)"; \
    gpg --batch --quiet --import signing-key.asc; \
    if ! gpg --batch --status-fd 1 --verify manifest.txt.asc manifest.txt > gpg-status \
       || ! grep -Eq "^\[GNUPG:\] VALIDSIG .* ${FRIGATE_PGP_FINGERPRINT}\$" gpg-status; then \
        echo "PGP verification of release manifest failed" >&2; cat gpg-status >&2; exit 1; \
    fi

# Verify the tarball sha256 against the signed manifest, then extract
RUN set -eu; \
    TARBALL="$(cat tarball-name)"; \
    EXPECTED="$(grep -F "*${TARBALL}" manifest.txt | cut -d' ' -f1)"; \
    test -n "${EXPECTED}" || { echo "${TARBALL} not listed in manifest" >&2; exit 1; }; \
    echo "${EXPECTED}  frigate.tar.gz" | sha256sum --check --strict -; \
    tar -xzf frigate.tar.gz -C /opt; \
    test -x /opt/frigate/bin/frigate; \
    test -x /opt/frigate/bin/frigate-cli


FROM debian:trixie-slim

# ca-certificates: for SSL connections to a backendElectrumServer or remote RPC
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -g 1000 frigate \
    && useradd -u 1000 -g frigate -m -d /home/frigate -s /usr/sbin/nologin frigate \
    && mkdir -p /home/frigate/.frigate \
    && chown -R frigate:frigate /home/frigate

COPY --from=downloader /opt/frigate /opt/frigate

ENV PATH="/opt/frigate/bin:${PATH}"

USER frigate
WORKDIR /home/frigate

# Electrum TCP (default) and SSL (if enabled in config.toml)
EXPOSE 50001 50002

# Checks that the Electrum listener accepts TCP connections on HEALTHCHECK_PORT.
# Set it to match your [server] tcp/ssl port (a TCP connect works for either),
# e.g. `-e HEALTHCHECK_PORT=60011`. start-period allows for startup.
ENV HEALTHCHECK_PORT=50001
HEALTHCHECK --interval=30s --timeout=10s --start-period=2m --retries=3 \
    CMD bash -c '</dev/tcp/127.0.0.1/${HEALTHCHECK_PORT}' || exit 1

# CMD (not ENTRYPOINT) so compose `command: frigate --level DEBUG` and
# `docker run <image> frigate-cli ...` both work.
CMD ["frigate"]
