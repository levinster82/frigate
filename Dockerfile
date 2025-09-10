# Multi-stage Dockerfile for Frigate Bitcoin Silent Payments Server
FROM eclipse-temurin:22-jdk-jammy AS builder

# Install git for submodule handling
RUN apt-get update && apt-get install -y git && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy gradle wrapper and build files
COPY gradlew gradlew.bat ./
COPY gradle gradle/
COPY settings.gradle build.gradle ./

# Initialize git repository and add submodules
# Note: This requires the build context to include .git directory
COPY .git .git/
COPY .gitmodules .gitmodules
# Explicitly set submodule URL to HTTPS and initialize
RUN git submodule set-url drongo https://github.com/sparrowwallet/drongo.git && \
    git submodule update --init --recursive

# Copy source code
COPY src src/

# Build the application
RUN chmod +x ./gradlew && ./gradlew build --no-daemon

# Create working runtime using traditional JAR approach
RUN ./gradlew build --no-daemon && \
    mkdir -p build/runtime/bin && \
    mkdir -p build/runtime/lib && \
    cp -r $JAVA_HOME/bin/* build/runtime/bin/ && \
    cp -r $JAVA_HOME/lib/* build/runtime/lib/ && \
    cp -r $JAVA_HOME/conf build/runtime/ && \
    echo '#!/bin/sh' > build/runtime/bin/frigate && \
    echo 'DIR="$(cd "$(dirname "$0")" && pwd)"' >> build/runtime/bin/frigate && \
    echo '"$DIR/java" -cp "$DIR/../lib/*" com.sparrowwallet.frigate.Frigate "$@"' >> build/runtime/bin/frigate && \
    chmod +x build/runtime/bin/frigate && \
    echo '#!/bin/sh' > build/runtime/bin/frigate-cli && \
    echo 'DIR="$(cd "$(dirname "$0")" && pwd)"' >> build/runtime/bin/frigate-cli && \
    echo '"$DIR/java" -cp "$DIR/../lib/*" com.sparrowwallet.frigate.cli.FrigateCli "$@"' >> build/runtime/bin/frigate-cli && \
    chmod +x build/runtime/bin/frigate-cli && \
    cp build/libs/*.jar build/runtime/lib/ && \
    cp drongo/build/libs/*.jar build/runtime/lib/ 2>/dev/null || true && \
    find ~/.gradle/caches -name "*.jar" -path "*/modules-2/files-2.1/*" -exec cp {} build/runtime/lib/ \; 2>/dev/null || true

# Runtime stage - minimal JRE image
FROM ubuntu:22.04

# Install required runtime dependencies and debugging tools
RUN apt-get update && \
    apt-get install -y \
    ca-certificates \
    curl \
    net-tools \
    file \
    nano \
    vim \
    strace \
    ltrace \
    lsof \
    procps \
    && rm -rf /var/lib/apt/lists/*

# Create frigate user
RUN useradd -r -s /bin/false frigate

# Create necessary directories
RUN mkdir -p /opt/frigate /home/frigate/.frigate && \
    chown -R frigate:frigate /opt/frigate /home/frigate

# Copy built application from builder stage (using manual runtime)
COPY --from=builder --chown=frigate:frigate /app/build/runtime/ /opt/frigate/

# Set up environment
ENV PATH="/opt/frigate/bin:$PATH"
ENV FRIGATE_HOME="/home/frigate/.frigate"

# Expose Frigate Electrum server port (DEFAULT_PORT = 57001)
EXPOSE 57001

# Switch to frigate user
USER frigate
WORKDIR /home/frigate

# Health check - using netstat since Frigate uses raw TCP sockets, not HTTP
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD netstat -tln | grep :57001 || exit 1

# Default command runs the main Frigate server
CMD ["frigate"]