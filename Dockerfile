# Multi-stage build for Frigate - Ubuntu-based amd64 image
FROM eclipse-temurin:22-jdk AS builder

# Install required build dependencies (only git needed for installDist)
RUN apt-get update && apt-get install -y \
    git \
    && rm -rf /var/lib/apt/lists/*

# Set working directory
WORKDIR /app

# Copy gradle files first for better layer caching
COPY build.gradle settings.gradle gradlew ./
COPY gradle/ ./gradle/

# Copy source code
COPY src/ ./src/

# Clone drongo dependency directly since git submodules won't work without .git
RUN rm -rf drongo \
    && git clone https://github.com/sparrowwallet/drongo.git drongo

# Remove Mac/Windows specific deployment resources to reduce image size
RUN rm -rf src/main/deploy/package/macos/ \
    src/main/deploy/package/windows/ \
    && find drongo/src/main/resources/native -type d -name "osx" -exec rm -rf {} + \
    && find drongo/src/main/resources/native -type d -name "windows" -exec rm -rf {} + \
    || true

# Configure Java preview features and remove Windows scripts
RUN printf '\n// Docker build configuration\nallprojects {\n    compileJava {\n        options.compilerArgs += ["--enable-preview"]\n        options.release = 22\n    }\n}\nstartScripts {\n    doLast {\n        delete windowsScript\n    }\n}\n' >> build.gradle

# Build the application - use installDist instead of jlink to avoid java executable issues
RUN ./gradlew clean installDist --no-daemon

# Production stage - use Eclipse Temurin JRE which has Java 22 support
FROM eclipse-temurin:22-jre

# Install minimal runtime dependencies
RUN apt-get update && apt-get install -y \
    ca-certificates \
    bash \
    && rm -rf /var/lib/apt/lists/* \
    && (getent passwd ubuntu && userdel ubuntu || true) \
    && (getent group 1000 && groupdel $(getent group 1000 | cut -d: -f1) || true) \
    && groupadd -r -g 1000 frigate \
    && useradd -r -u 1000 -g frigate -s /bin/false frigate

# Copy the Gradle-generated distribution
COPY --from=builder /app/build/install/frigate /opt/frigate

# Create frigate-cli executable since installDist only creates the main app
RUN echo '#!/bin/bash' > /opt/frigate/bin/frigate-cli \
    && echo 'exec java -cp "/opt/frigate/lib/*" com.sparrowwallet.frigate.cli.FrigateCli "$@"' >> /opt/frigate/bin/frigate-cli

# Create data directory for frigate configuration
RUN mkdir -p /home/frigate/.frigate \
    && chown -R frigate:frigate /home/frigate

# Set executable permissions for both binaries
RUN chmod +x /opt/frigate/bin/frigate /opt/frigate/bin/frigate-cli

# Verify build succeeded and all required files exist
RUN test -f /opt/frigate/bin/frigate || (echo "Build failed - frigate binary not found" && exit 1) \
    && test -f /opt/frigate/bin/frigate-cli || (echo "Build failed - frigate-cli binary not found" && exit 1) \
    && test -d /opt/frigate/lib || (echo "Build failed - lib directory not found" && exit 1) \
    && test -x /opt/frigate/bin/frigate || (echo "Build failed - frigate not executable" && exit 1) \
    && test -x /opt/frigate/bin/frigate-cli || (echo "Build failed - frigate-cli not executable" && exit 1)

# Switch to non-root user
USER frigate
WORKDIR /home/frigate

# Expose default frigate port 57001
EXPOSE 57001

# Health check to verify frigate is responsive
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD /opt/frigate/bin/frigate-cli --help > /dev/null || exit 1

# Set environment variables for container operation
ENV FRIGATE_DATA_DIR=/home/frigate/.frigate
ENV PATH="/opt/frigate/bin:$PATH"

# Default command runs the frigate server
CMD ["/opt/frigate/bin/frigate"]
