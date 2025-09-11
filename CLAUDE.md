# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Frigate is an experimental Electrum Server for Bitcoin Silent Payments scanning. It implements a "Remote Scanner" approach where the server performs cryptographic scanning operations using an optimized DuckDB database with custom secp256k1 extensions, rather than requiring clients to download and scan block data locally.

## Architecture

### Core Components

- **Main Application** (`com.sparrowwallet.frigate.Frigate`): Entry point that coordinates indexing and server operations
- **Indexing System** (`com.sparrowwallet.frigate.index`): Builds and maintains tweak index in DuckDB for efficient scanning
- **Bitcoin Core Integration** (`com.sparrowwallet.frigate.bitcoind`): RPC client for blockchain data retrieval
- **Electrum Server** (`com.sparrowwallet.frigate.electrum`): JSON-RPC server implementing Silent Payments protocol extensions
- **CLI Client** (`com.sparrowwallet.frigate.cli`): Command-line interface for testing and interacting with the server

### Database Architecture

Frigate uses DuckDB with a custom secp256k1 extension for cryptographic operations. The main table schema:

- `txid` (BLOB): Transaction ID
- `height` (INTEGER): Block height  
- `tweak_key` (BLOB): Computed tweak from transaction inputs
- `outputs` (LIST(BIGINT)): First 8 bytes of Taproot output public key x-values

### Dependencies

- **drongo**: Git submodule providing Bitcoin utilities (located at `drongo/`)
- **DuckDB**: OLAP database for analytical scanning workloads
- **HikariCP**: Database connection pooling
- **Simple JSON-RPC**: Client/server JSON-RPC framework
- **JCommander**: Command-line argument parsing

## Development Commands

### Building
```bash
./gradlew build                    # Build the project
./gradlew test                     # Run tests
./gradlew jpackage                 # Create platform-specific binaries
```

### Running
```bash
./gradlew run                      # Run Frigate server
./gradlew run --args="-n signet"   # Run on signet network
```

### Distribution Packaging
```bash
./gradlew packageZipDistribution   # Create zip distribution
./gradlew packageTarDistribution   # Create tar.gz distribution
```

## Configuration

Frigate stores configuration in:
- **macOS/Linux**: `~/.frigate/config`  
- **Windows**: `%APPDATA%/Frigate`

Key configuration options:
- `coreServer`: Bitcoin Core RPC endpoint
- `coreAuthType`: "COOKIE" or "USERPASS" 
- `coreDataDir`/`coreAuth`: Authentication credentials
- `startIndexing`: Whether to build/update index on startup
- `indexStartHeight`: Block height to start indexing from
- `scriptPubKeyCacheSize`: Memory limit for caching during indexing
- `dbThreads`: Number of CPU cores for DuckDB operations

Database is stored in `db/frigate.duckdb` within the config directory.

## Requirements

- **Java 22+** (built with Eclipse Temurin 22.0.2+9)
- **Bitcoin Core** with `txindex=1` for blockchain data access
- **Git submodules** must be initialized: `git clone --recursive` or `git submodule update --init`

## Testing Strategy

The project uses JUnit 5 for testing. Tests are located in `src/test/java/` following the same package structure as main code.

## Protocol Extensions

Frigate implements custom Electrum protocol methods for Silent Payments:

- `blockchain.silentpayments.subscribe`: Start scanning for a Silent Payments address
- `blockchain.silentpayments.unsubscribe`: Stop scanning for an address

These extend the standard Electrum protocol to support the computational requirements of Silent Payments scanning.

## Docker Implementation

### Current Solution
The Docker implementation uses a **traditional JAR-based approach** rather than jlink optimization due to module system conflicts discovered during development.

### Network Configuration
- **Server Port**: 57001 (not standard Electrum 50001)
- **Protocol**: Raw TCP sockets for Electrum JSON-RPC
- **Health Check**: Uses `netstat` to verify port binding

### Docker Services
- **frigate**: Main server container
- **frigate-cli**: CLI client for testing (profile-based)
- **bitcoind**: Optional Bitcoin Core container using `kylemanna/bitcoind:latest`

### Known Issues

#### jlink Module System Conflicts
**Problem**: The project's build.gradle uses the beryx jlink plugin with aggressive optimization options that create corrupted module systems.

**Specific Error**: `Package jdk.internal.jrtfs in both module jrt.fs and module java.base`

**Root Cause**: 
- The jlink configuration uses `--strip-native-commands` and other aggressive optimizations
- Module merging creates package conflicts between internal JDK modules
- The generated `jexec` executable fails with "invalid path" errors

**Investigation Results**:
- ✅ Basic jlink works fine with simple modules
- ❌ jlink with beryx plugin creates corrupted module system
- ❌ Both generated `jexec` and system `java` fail with jlink modules
- ✅ Traditional classpath approach works perfectly

**Current Workaround**: 
- Docker uses traditional JAR approach with full JRE
- Creates simple shell scripts instead of jlink launchers
- Copies all dependencies from Gradle cache to classpath

#### Git Submodules in Docker
**Problem**: Default git submodule URLs use SSH which requires authentication in Docker build context.

**Solution**: 
```dockerfile
RUN git submodule set-url drongo https://github.com/sparrowwallet/drongo.git
```

#### Gradle Plugin Repository Access
**Problem**: The `org.gradlex.extra-java-module-info` plugin couldn't be resolved during Docker builds.

**Solution**: Added explicit plugin repository configuration to `settings.gradle`:
```gradle
pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}
```

### Future Improvements
1. **Investigate Base Image Impact**: Test if different JDK distributions (Oracle, Adoptium, etc.) resolve jlink issues
2. **Fix jlink Configuration**: Modify build.gradle to remove problematic optimization flags
3. **Optimize Container Size**: The traditional JAR approach includes full JRE - could be optimized further