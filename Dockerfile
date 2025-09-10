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
# Configure git to use HTTPS instead of SSH for GitHub
RUN git config --global url."https://github.com/".insteadOf "git@github.com:" && \
    git config --global url."https://github.com/".insteadOf "ssh://git@github.com/" && \
    git submodule update --init --recursive

# Copy source code
COPY src src/

# Build the application
RUN chmod +x ./gradlew && ./gradlew build --no-daemon

# Create distribution
RUN ./gradlew jlink --no-daemon

# Runtime stage - minimal JRE image
FROM ubuntu:22.04

# Install required runtime dependencies
RUN apt-get update && \
    apt-get install -y \
    ca-certificates \
    curl \
    net-tools \
    && rm -rf /var/lib/apt/lists/*

# Create frigate user
RUN useradd -r -s /bin/false frigate

# Create necessary directories
RUN mkdir -p /opt/frigate /home/frigate/.frigate && \
    chown -R frigate:frigate /opt/frigate /home/frigate

# Copy built application from builder stage
COPY --from=builder --chown=frigate:frigate /app/build/image/ /opt/frigate/

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