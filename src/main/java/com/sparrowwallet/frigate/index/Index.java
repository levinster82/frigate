package com.sparrowwallet.frigate.index;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.protocol.*;
import com.sparrowwallet.drongo.silentpayments.SilentPaymentScanAddress;
import com.sparrowwallet.drongo.silentpayments.SilentPaymentUtils;
import com.sparrowwallet.drongo.wallet.BlockTransaction;
import com.sparrowwallet.frigate.ConfigurationException;
import com.sparrowwallet.frigate.Frigate;
import com.sparrowwallet.frigate.SubscriptionStatus;
import com.sparrowwallet.frigate.electrum.SilentPaymentsNotification;
import com.sparrowwallet.frigate.electrum.SilentPaymentsSubscription;
import com.sparrowwallet.frigate.io.Config;
import com.sparrowwallet.frigate.io.Storage;
import org.duckdb.DuckDBPreparedStatement;
import org.duckdb.QueryProgress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.lang.ref.WeakReference;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public class Index {
    private static final Logger log = LoggerFactory.getLogger(Index.class);
    public static final String DEFAULT_DB_FILENAME = "frigate.duckdb";
    private static final String TWEAK_TABLE = "tweak";
    public static final int HISTORY_PAGE_SIZE = 100;

    private final DbManager dbManager;
    private int lastBlockIndexed = -1;

    public Index(int startHeight, boolean inMemory) {
        lastBlockIndexed = Math.max(lastBlockIndexed, startHeight - 1);

        if(inMemory) {
            dbManager = new MemoryDbManager();
        } else {
            String dbUrl = Config.get().getDbUrl();
            List<String> readDbUrls = Config.get().getReadDbUrls();
            if(dbUrl != null && readDbUrls != null && !readDbUrls.isEmpty()) {
                dbManager = new ScalingDbManager(dbUrl, readDbUrls);
            } else if(dbUrl == null) {
                File dbFile = new File(Storage.getFrigateDbDir(), DEFAULT_DB_FILENAME);
                dbManager = new SingleDbManager(DbManager.DB_PREFIX + dbFile.getAbsolutePath());
            } else {
                dbManager = new SingleDbManager(dbUrl);
            }
        }

        try {
            dbManager.executeWrite(connection -> {
                try(Statement stmt = connection.createStatement()) {
                    return stmt.execute("CREATE TABLE IF NOT EXISTS " + TWEAK_TABLE + " (txid BLOB NOT NULL, height INTEGER NOT NULL, tweak_key BLOB NOT NULL, outputs BIGINT[])");
                }
            });
        } catch(Exception e) {
            throw new ConfigurationException("Error initialising index", e);
        }
    }

    public void close() {
        dbManager.close();
    }

    public int getLastBlockIndexed() {
        try {
            return dbManager.executeRead(connection -> {
                try(PreparedStatement statement = connection.prepareStatement("SELECT MAX(height) from " + TWEAK_TABLE)) {
                    ResultSet resultSet = statement.executeQuery();
                    return resultSet.next() ? Math.max(lastBlockIndexed, resultSet.getInt(1)) : lastBlockIndexed;
                }
            });
        } catch(Exception e) {
            log.error("Error getting last block indexed", e);
            return lastBlockIndexed;
        }
    }

    public void addToIndex(Map<BlockTransaction, byte[]> transactions) {
        if(dbManager.isShutdown()) {
            return;
        }

        int fromBlockHeight = lastBlockIndexed;
        try {
            lastBlockIndexed = dbManager.executeWrite(connection -> {
                try(PreparedStatement statement = connection.prepareStatement("INSERT INTO " + TWEAK_TABLE + " VALUES (?, ?, ?, ?)")) {
                    int blockHeight = -1;

                    for(BlockTransaction blkTx : transactions.keySet()) {
                        statement.setBytes(1, blkTx.getTransaction().getTxId().getBytes());
                        statement.setInt(2, blkTx.getHeight());
                        statement.setObject(3, transactions.get(blkTx));

                        List<TransactionOutput> outputs = blkTx.getTransaction().getOutputs();
                        List<Long> hashPrefixes = new ArrayList<>();
                        for(TransactionOutput output : outputs) {
                            if(ScriptType.P2TR.isScriptType(output.getScript())) {
                                long hashPrefix = getHashPrefix(ScriptType.P2TR.getPublicKeyFromScript(output.getScript()).getPubKey(), 1);
                                hashPrefixes.add(hashPrefix);
                            }
                        }
                        statement.setArray(4, connection.createArrayOf("BIGINT", hashPrefixes.toArray()));
                        statement.addBatch();

                        blockHeight = Math.max(blockHeight, blkTx.getHeight());
                    }

                    statement.executeBatch();
                    if(blockHeight <= 0 && lastBlockIndexed < 0) {
                        log.info("Indexed " + transactions.size() + " mempool transactions");
                    } else if(blockHeight > 0) {
                        log.info("Indexed " + transactions.size() + " transactions to block height " + blockHeight);
                    }

                    return blockHeight;
                }
            });

            if(lastBlockIndexed <= 0) {
                Frigate.getEventBus().post(new SilentPaymentsMempoolIndexAdded(transactions.keySet().stream().map(blkTx -> blkTx.getTransaction().getTxId()).collect(Collectors.toSet())));
            } else {
                Frigate.getEventBus().post(new SilentPaymentsBlocksIndexUpdate(fromBlockHeight + 1, lastBlockIndexed, transactions.size()));
            }
        } catch(Exception e) {
            log.error("Error adding to index", e);
        }
    }

    public void removeFromIndex(int startHeight) {
        if(dbManager.isShutdown()) {
            return;
        }

        try {
            dbManager.executeWrite(connection -> {
                try(PreparedStatement statement = connection.prepareStatement("DELETE FROM " + TWEAK_TABLE + " WHERE height >= ?")) {
                    statement.setInt(1, startHeight);
                    return statement.execute();
                }
            });
        } catch(Exception e) {
            log.error("Error removing from index", e);
        }
    }

    public void removeFromIndex(Set<Sha256Hash> txIds) {
        if(dbManager.isShutdown()) {
            return;
        }

        try {
            dbManager.executeWrite(connection -> {
                try(PreparedStatement statement = connection.prepareStatement("DELETE FROM " + TWEAK_TABLE + " WHERE txid = ?")) {
                    for(Sha256Hash txId : txIds) {
                        statement.setBytes(1, txId.getBytes());
                        statement.addBatch();
                    }

                    statement.executeBatch();
                    return txIds.size();
                }
            });

            Frigate.getEventBus().post(new SilentPaymentsMempoolIndexRemoved(txIds));
        } catch(Exception e) {
            log.error("Error removing from index", e);
        }
    }

    public List<TxEntry> getHistoryAsync(SilentPaymentScanAddress scanAddress, SilentPaymentsSubscription subscription, Integer startHeight, Integer endHeight, boolean scanForChange, WeakReference<SubscriptionStatus> subscriptionStatusRef) {
        ConcurrentLinkedQueue<TxEntry> queue = new ConcurrentLinkedQueue<>();
        AtomicLong rowsProcessedStart = new AtomicLong(0L);
        long queryStartTime = System.currentTimeMillis();

        try {
            dbManager.executeRead(connection -> {
                String sql = "SELECT txid, tweak_key, height FROM " + TWEAK_TABLE +
                        " WHERE scan_silent_payments(outputs, [?, ?, tweak_key], " + (scanForChange ? "[?])" : "[])");

                if(startHeight != null) {
                    sql += " AND height >= ?";
                }
                if(endHeight != null) {
                    sql += " AND height <= ?";
                }

                log.trace("Executing scan query for {}: {} (startHeight={}, endHeight={}, scanForChange={})",
                    scanAddress, sql, startHeight, endHeight, scanForChange);

                try(DuckDBPreparedStatement statement = connection.prepareStatement(sql).unwrap(DuckDBPreparedStatement.class)) {
                    if(isUnsubscribed(scanAddress, subscriptionStatusRef)) {
                        log.trace("Scan cancelled before execution - address {} already unsubscribed", scanAddress);
                        return false;
                    }

                    statement.setBytes(1, scanAddress.getScanKey().getPrivKeyBytes());
                    statement.setBytes(2, SilentPaymentUtils.getSecp256k1PubKey(scanAddress.getSpendKey()));
                    if(scanForChange) {
                        statement.setBytes(3, SilentPaymentUtils.getSecp256k1PubKey(scanAddress.getChangeTweakKey()));
                    }
                    if(startHeight != null) {
                        statement.setInt(scanForChange ? 4 : 3, startHeight);
                    }
                    if(endHeight != null) {
                        statement.setInt(scanForChange ? startHeight == null ? 4 : 5 : startHeight == null ? 3 : 4, endHeight);
                    }
                    statement.setFetchSize(1);

                    log.trace("Query prepared for {}, beginning execution...", scanAddress);

                    try(ScheduledThreadPoolExecutor queryProgressExecutor = new ScheduledThreadPoolExecutor(1, r -> {
                        ThreadFactory namedThreadFactory = new ThreadFactoryBuilder().setNameFormat("IndexQueryProgress-%d").build();
                        Thread t = namedThreadFactory.newThread(r);
                        t.setDaemon(true);
                        return t;
                    })) {
                        queryProgressExecutor.scheduleAtFixedRate(() -> {
                            try {
                                if(dbManager.isShutdown() || isUnsubscribed(scanAddress, subscriptionStatusRef)) {
                                    log.trace("Cancelling scan for {} - shutdown={}, unsubscribed={}",
                                        scanAddress, dbManager.isShutdown(), isUnsubscribed(scanAddress, subscriptionStatusRef));
                                    statement.cancel();
                                    queryProgressExecutor.shutdownNow();
                                    return;
                                }

                                QueryProgress queryProgress = statement.getQueryProgress();
                                if(queryProgress.getRowsProcessed() == queryProgress.getTotalRowsToProcess()) {
                                    log.trace("Scan for {} completed processing all {} rows",
                                        scanAddress, queryProgress.getTotalRowsToProcess());
                                    return;
                                }

                                double progress = 0.0d;
                                if(rowsProcessedStart.get() == 0L && queryProgress.getRowsProcessed() > 0) {
                                    rowsProcessedStart.set(queryProgress.getRowsProcessed());
                                    log.trace("Scan for {} started processing - initial rows: {}, total rows: {}",
                                        scanAddress, queryProgress.getRowsProcessed(), queryProgress.getTotalRowsToProcess());
                                }
                                if(rowsProcessedStart.get() > 0L) {
                                    progress = (queryProgress.getRowsProcessed() - rowsProcessedStart.get()) / (double)(queryProgress.getTotalRowsToProcess() - rowsProcessedStart.get());
                                }

                                log.trace("Scan progress for {}: {}/{} rows ({:.2f}%), queue size: {}",
                                    scanAddress, queryProgress.getRowsProcessed(), queryProgress.getTotalRowsToProcess(),
                                    progress * 100, queue.size());

                                List<TxEntry> history = new ArrayList<>();
                                TxEntry entry;
                                while((entry = queue.poll()) != null) {
                                    history.add(entry);
                                    if(history.size() >= HISTORY_PAGE_SIZE) {
                                        log.trace("Posting progress update for {} with {} entries (page full)", scanAddress, history.size());
                                        Frigate.getEventBus().post(new SilentPaymentsNotification(subscription, progress, new ArrayList<>(history), subscriptionStatusRef.get()));
                                        history.clear();
                                    }
                                }
                                if(!history.isEmpty() || queryProgressExecutor.getTaskCount() % 5 == 0) {
                                    if(!history.isEmpty()) {
                                        log.trace("Posting progress update for {} with {} entries", scanAddress, history.size());
                                    }
                                    Frigate.getEventBus().post(new SilentPaymentsNotification(subscription, progress, new ArrayList<>(history), subscriptionStatusRef.get()));
                                    history.clear();
                                }
                            } catch(SQLException e) {
                                log.error("Error getting query progress", e);
                            }
                        }, 1, 1, TimeUnit.SECONDS);

                        ResultSet resultSet = statement.executeQuery();
                        int matchCount = 0;
                        while(resultSet.next()) {
                            byte[] txid = resultSet.getBytes(1);
                            byte[] tweak_key = compressRawKey(resultSet.getBytes(2));
                            int height = resultSet.getInt(3);
                            TxEntry txEntry = new TxEntry(height, 0, Utils.bytesToHex(txid), Utils.bytesToHex(tweak_key));
                            queue.offer(txEntry);
                            matchCount++;
                            log.trace("Found match for {} at height {}: txid={}, tweak={}",
                                scanAddress, height, Utils.bytesToHex(txid), Utils.bytesToHex(tweak_key));
                        }
                        log.trace("Query execution completed for {}, found {} total matches", scanAddress, matchCount);
                    }
                }

                return true;
            });
        } catch(SQLTimeoutException e) {
            if(e.getMessage().startsWith("INTERRUPT Error")) {
                log.debug("Query cancelled for {}", scanAddress, e);
                log.trace("Scan interrupted for {} after {}ms", scanAddress, System.currentTimeMillis() - queryStartTime);
            } else {
                log.error("Query timeout for {}", scanAddress, e);
            }
            return Collections.emptyList();
        } catch(Exception e) {
            log.error("Error scanning index for {}", scanAddress, e);
            return Collections.emptyList();
        }

        if(isUnsubscribed(scanAddress, subscriptionStatusRef)) {
            log.trace("Scan for {} unsubscribed after query completion, discarding results", scanAddress);
            return Collections.emptyList();
        }

        List<TxEntry> history = new ArrayList<>();
        TxEntry entry;
        while((entry = queue.poll()) != null) {
            history.add(entry);
        }

        long totalDuration = System.currentTimeMillis() - queryStartTime;
        log.trace("Scan completed for {} in {}ms, returning {} results", scanAddress, totalDuration, history.size());

        return history;
    }

    private static boolean isUnsubscribed(SilentPaymentScanAddress scanAddress, WeakReference<SubscriptionStatus> subscriptionStatusRef) {
        SubscriptionStatus status = subscriptionStatusRef.get();
        return status == null || !status.isConnected() || !status.isSilentPaymentsAddressSubscribed(scanAddress.toString());
    }

    public static long getHashPrefix(byte[] hash, int offset) {
        if(hash.length < 8 + offset) {
            throw new IllegalArgumentException("Hash must be at least 8 bytes long from the offset");
        }

        long result = 0;
        // Process 8 bytes from the offset in big-endian order
        for (int i = offset; i < 8 + offset; i++) {
            result = (result << 8) | (hash[i] & 0xFF);
        }
        return result;
    }

    public static byte[] compressRawKey(byte[] rawUncompressed) {
        byte[] uncompressed = new byte[64];
        System.arraycopy(rawUncompressed, 0, uncompressed, 32, 32);
        System.arraycopy(rawUncompressed, 32, uncompressed, 0, 32);
        uncompressed = Utils.reverseBytes(uncompressed);

        ECKey ecKey = ECKey.fromPublicOnly(Utils.concat(new byte[] {0x04}, uncompressed));
        return ecKey.getPubKey(true);
    }
}
