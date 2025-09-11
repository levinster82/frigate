FROM eclipse-temurin:22-jdk-jammy AS builder

# Install git for submodules
RUN apt-get update && \
    apt-get install -y git && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy gradle wrapper and build files  
COPY gradlew gradlew.bat ./
COPY gradle gradle/
COPY settings.gradle build.gradle ./

# Initialize git repository and add submodules
COPY .git .git/
COPY .gitmodules .gitmodules
RUN git submodule set-url drongo https://github.com/sparrowwallet/drongo.git && \
    git submodule update --init --recursive

# Copy source code
COPY src src/

# Build the application
RUN chmod +x ./gradlew && ./gradlew build --no-daemon

# Create optimized runtime using jlink + classpath approach
RUN ./gradlew jlink --no-daemon && \
    cd build/image && \
    rm -f lib/jrt-fs.jar && \
    cp /app/build/libs/*.jar lib/ && \
    cp /app/drongo/build/libs/*.jar lib/ && \
    find ~/.gradle/caches -name "*.jar" -path "*/modules-2/files-2.1/*" -exec cp {} lib/ \; && \
    mkdir -p bin && \
    echo '#!/bin/sh' > bin/frigate && \
    echo 'DIR="$(cd "$(dirname "$0")" && pwd)"' >> bin/frigate && \
    echo 'exec java -cp "$DIR/../lib/*" com.sparrowwallet.frigate.Frigate "$@"' >> bin/frigate && \
    chmod +x bin/frigate

# Runtime stage
FROM eclipse-temurin:22-jre-jammy

# Install runtime dependencies
RUN apt-get update && \
    apt-get install -y \
    ca-certificates \
    net-tools \
    && rm -rf /var/lib/apt/lists/*

# Create frigate user with uid/gid 1000
RUN groupadd -g 1000 frigate && \
    useradd -u 1000 -g 1000 -r -s /bin/false frigate

# Create directories
RUN mkdir -p /opt/frigate /home/frigate/.frigate && \
    chown -R frigate:frigate /opt/frigate /home/frigate

# Copy optimized runtime
COPY --from=builder --chown=frigate:frigate /app/build/image/ /opt/frigate/

# Set environment
ENV PATH="/opt/frigate/bin:$PATH"
ENV FRIGATE_HOME="/home/frigate/.frigate"

# Expose port
EXPOSE 57001

# Switch to frigate user
USER frigate
WORKDIR /home/frigate

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD netstat -tln | grep :57001 || exit 1

# Default command
CMD ["frigate"]