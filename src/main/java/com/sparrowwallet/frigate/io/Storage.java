package com.sparrowwallet.frigate.io;

import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.OsType;
import com.sparrowwallet.frigate.Frigate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.Set;

public class Storage {
    private static final Logger log = LoggerFactory.getLogger(Storage.class);

    public static final String FRIGATE_DIR = ".frigate";
    public static final String WINDOWS_FRIGATE_DIR = "Frigate";
    public static final String LOGBACK_CONFIG_FILE = "logback.xml";

    public static File getSecp256k1ExtensionFile() {
        String resourcePath;
        String osName = System.getProperty("os.name");
        String osArch = System.getProperty("os.arch");
        if(osName.startsWith("Mac") && osArch.equals("aarch64")) {
            resourcePath = "/native/macos/arm64/secp256k1.duckdb_extension";
        } else if(osName.startsWith("Mac")) {
            resourcePath = "/native/macos/amd64/secp256k1.duckdb_extension";
        } else if(osName.startsWith("Windows")) {
            resourcePath = "/native/windows/amd64/secp256k1.duckdb_extension";
        } else if(osArch.equals("aarch64")) {
            resourcePath = "/native/linux/arm64/secp256k1.duckdb_extension";
        } else {
            resourcePath = "/native/linux/amd64/secp256k1.duckdb_extension";
        }

        File extensionFile = new File(getFrigateDbDir(), "secp256k1.duckdb_extension");

        try(InputStream is = Storage.class.getResourceAsStream(resourcePath)) {
            if(is == null) {
                throw new IOException("Could not find secp256k1 extension for the current platform: " + osName + " " + osArch);
            }

            Files.copy(is, extensionFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch(IOException e) {
            log.error("Error loading secp256k1 extension", e);
        }

        return extensionFile;
    }

    public static File getFrigateDbDir() {
        File dbDir = new File(getFrigateDir(), "db");
        if(!dbDir.exists()) {
            createOwnerOnlyDirectory(dbDir);
        }
        return dbDir;
    }

    public static File getFrigateDir() {
        File frigateDir;
        Network network = Network.get();
        if(network != Network.MAINNET) {
            frigateDir = new File(getFrigateHome(), network.getHome());
            if(!network.getName().equals(network.getHome()) && !frigateDir.exists()) {
                File networkNameDir = new File(getFrigateHome(), network.getName());
                if(networkNameDir.exists() && networkNameDir.isDirectory() && !Files.isSymbolicLink(networkNameDir.toPath())) {
                    try {
                        if(networkNameDir.renameTo(frigateDir) && !isWindows()) {
                            Files.createSymbolicLink(networkNameDir.toPath(), Path.of(frigateDir.getName()));
                        }
                    } catch(Exception e) {
                        log.debug("Error creating symlink from " + networkNameDir.getAbsolutePath() + " to " + frigateDir.getName(), e);
                    }
                }
            }
        } else {
            frigateDir = getFrigateHome();
        }

        if(!frigateDir.exists()) {
            createOwnerOnlyDirectory(frigateDir);
        }

        if(!network.getName().equals(network.getHome()) && !isWindows()) {
            try {
                Path networkNamePath = getFrigateHome().toPath().resolve(network.getName());
                if(Files.isSymbolicLink(networkNamePath)) {
                    Path symlinkTarget = getFrigateHome().toPath().resolve(Files.readSymbolicLink(networkNamePath));
                    if(!Files.isSameFile(frigateDir.toPath(), symlinkTarget)) {
                        Files.delete(networkNamePath);
                        Files.createSymbolicLink(networkNamePath, Path.of(frigateDir.getName()));
                    }
                } else if(!Files.exists(networkNamePath)) {
                    Files.createSymbolicLink(networkNamePath, Path.of(frigateDir.getName()));
                }
            } catch(Exception e) {
                log.debug("Error updating symlink from " + network.getName() + " to " + frigateDir.getName(), e);
            }
        }

        return frigateDir;
    }

    public static File getFrigateHome() {
        return getFrigateHome(false);
    }

    public static File getFrigateHome(boolean useDefault) {
        if(!useDefault && System.getProperty(Frigate.APP_HOME_PROPERTY) != null) {
            return new File(System.getProperty(Frigate.APP_HOME_PROPERTY));
        }

        if(isWindows()) {
            return new File(getHomeDir(), WINDOWS_FRIGATE_DIR);
        }

        return new File(getHomeDir(), FRIGATE_DIR);
    }

    static File getHomeDir() {
        if(isWindows()) {
            return new File(System.getenv("APPDATA"));
        }

        return new File(System.getProperty("user.home"));
    }

    public static boolean createOwnerOnlyDirectory(File directory) {
        try {
            if(isWindows()) {
                Files.createDirectories(directory.toPath());
                return true;
            }

            Files.createDirectories(directory.toPath(), PosixFilePermissions.asFileAttribute(getDirectoryOwnerOnlyPosixFilePermissions()));
            return true;
        } catch(UnsupportedOperationException e) {
            return directory.mkdirs();
        } catch(IOException e) {
            log.error("Could not create directory " + directory.getAbsolutePath(), e);
        }

        return false;
    }

    public static boolean createOwnerOnlyFile(File file) {
        try {
            if(isWindows()) {
                Files.createFile(file.toPath());
                return true;
            }

            Files.createFile(file.toPath(), PosixFilePermissions.asFileAttribute(getFileOwnerOnlyPosixFilePermissions()));
            return true;
        } catch(UnsupportedOperationException e) {
            return false;
        } catch(IOException e) {
            log.error("Could not create file " + file.getAbsolutePath(), e);
        }

        return false;
    }

    private static Set<PosixFilePermission> getDirectoryOwnerOnlyPosixFilePermissions() {
        Set<PosixFilePermission> ownerOnly = getFileOwnerOnlyPosixFilePermissions();
        ownerOnly.add(PosixFilePermission.OWNER_EXECUTE);

        return ownerOnly;
    }

    private static Set<PosixFilePermission> getFileOwnerOnlyPosixFilePermissions() {
        Set<PosixFilePermission> ownerOnly = EnumSet.noneOf(PosixFilePermission.class);
        ownerOnly.add(PosixFilePermission.OWNER_READ);
        ownerOnly.add(PosixFilePermission.OWNER_WRITE);

        return ownerOnly;
    }

    private static boolean isWindows() {
        return OsType.getCurrent() == OsType.WINDOWS;
    }

    public static void setupLogbackConfig() {
        // Get the Frigate home directory
        File frigateHome = getFrigateHome();
        File logbackConfigFile = new File(frigateHome, LOGBACK_CONFIG_FILE);

        // Set system property to tell Logback where to find the config file
        System.setProperty("logback.configurationFile", logbackConfigFile.getAbsolutePath());

        // Create default logback.xml if it doesn't exist
        if(!logbackConfigFile.exists()) {
            try {
                String defaultConfig = getDefaultLogbackConfig();
                Files.writeString(logbackConfigFile.toPath(), defaultConfig, StandardCharsets.UTF_8);
                System.out.println("Created default logback configuration at " + logbackConfigFile.getAbsolutePath());
            } catch(IOException e) {
                System.err.println("Warning: Could not create logback.xml at " + logbackConfigFile.getAbsolutePath());
                e.printStackTrace();
            }
        }
    }

    private static String getDefaultLogbackConfig() {
        return """
                <configuration scan="true" scanPeriod="30 seconds">
                    <statusListener class="ch.qos.logback.core.status.NopStatusListener" />

                    <logger name="sun.net.www.protocol.http.HttpURLConnection" level="INFO" />
                    <logger name="com.github.arteam.simplejsonrpc.server.JsonRpcServer" level="INFO" />
                    <logger name="com.zaxxer.hikari.HikariDataSource" level="WARN" />
                    <logger name="com.zaxxer.hikari.pool.PoolBase" level="ERROR" />
                    <logger name="com.zaxxer.hikari.pool.HikariPool" level="ERROR" />
                    <logger name="com.sparrowwallet.frigate.bitcoind.BitcoindTransport" level="INFO" />
                    <logger name="com.sparrowwallet.frigate.index" level="TRACE" />

                    <define name="appDir" class="com.sparrowwallet.drongo.PropertyDefiner">
                        <application>frigate</application>
                    </define>

                    <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
                        <file>${appDir}/frigate.log</file>
                        <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
                            <fileNamePattern>${appDir}/frigate.%d{yyyy-MM-dd}.%i.log.gz</fileNamePattern>
                            <maxFileSize>100MB</maxFileSize>
                            <maxHistory>30</maxHistory>
                            <totalSizeCap>3GB</totalSizeCap>
                        </rollingPolicy>
                        <encoder>
                            <pattern>%date %level [%thread] %logger{10} [%file:%line] %msg%n</pattern>
                        </encoder>
                    </appender>

                    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
                        <encoder>
                            <pattern>%date %level %msg%n</pattern>
                        </encoder>
                    </appender>

                    <root level="info">
                        <appender-ref ref="FILE" />
                        <appender-ref ref="STDOUT" />
                    </root>
                </configuration>
                """;
    }
}
