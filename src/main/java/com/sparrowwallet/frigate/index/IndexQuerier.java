package com.sparrowwallet.frigate.index;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.silentpayments.SilentPaymentScanAddress;
import com.sparrowwallet.frigate.Frigate;
import com.sparrowwallet.frigate.SubscriptionStatus;
import com.sparrowwallet.frigate.electrum.SilentPaymentsNotification;
import com.sparrowwallet.frigate.electrum.SilentPaymentsSubscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

public class IndexQuerier {
    private static final Logger log = LoggerFactory.getLogger(IndexQuerier.class);
    public static final double PROGRESS_COMPLETE = 1.0d;

    private final Index blocksIndex;
    private final Index mempoolIndex;
    private final boolean scanForChange;

    public IndexQuerier(Index blocksIndex, Index mempoolIndex, boolean scanForChange) {
        this.blocksIndex = blocksIndex;
        this.mempoolIndex = mempoolIndex;
        this.scanForChange = scanForChange;
    }

    private final ExecutorService queryPool = Executors.newFixedThreadPool(10, r -> {
        ThreadFactory namedThreadFactory = new ThreadFactoryBuilder().setNameFormat("IndexQuery-%d").build();
        Thread t = namedThreadFactory.newThread(r);
        t.setDaemon(true);
        return t;
    });

    public void startHistoryScan(SilentPaymentScanAddress scanAddress, Integer startHeight, Integer endHeight, WeakReference<SubscriptionStatus> subscriptionStatusRef) {
        startHistoryScan(scanAddress, startHeight, endHeight, subscriptionStatusRef, true);
    }

    public void startHistoryScan(SilentPaymentScanAddress scanAddress, Integer startHeight, Integer endHeight, WeakReference<SubscriptionStatus> subscriptionStatusRef, boolean postIfEmpty) {
        queryPool.submit(() -> {
            log.trace("Starting history scan for address {} from height {} to {}, scanForChange={}",
                scanAddress, startHeight, endHeight, scanForChange);
            long scanStartTime = System.currentTimeMillis();

            SilentPaymentsSubscription subscription = new SilentPaymentsSubscription(scanAddress.toString(), startHeight == null ? 0 : startHeight);
            List<TxEntry> history = blocksIndex.getHistoryAsync(scanAddress, subscription, startHeight, endHeight, scanForChange, subscriptionStatusRef);

            log.trace("Block scan completed for {}, found {} transactions", scanAddress, history.size());

            List<TxEntry> mempoolHistory = getMempoolHistory(scanAddress, subscriptionStatusRef, subscription);
            log.trace("Mempool scan completed for {}, found {} transactions", scanAddress, mempoolHistory.size());

            history.addAll(mempoolHistory);

            if(postIfEmpty || !history.isEmpty()) {
                long scanDuration = System.currentTimeMillis() - scanStartTime;
                log.trace("History scan completed for {} in {}ms, total {} transactions found",
                    scanAddress, scanDuration, history.size());
                Frigate.getEventBus().post(new SilentPaymentsNotification(subscription, PROGRESS_COMPLETE, new ArrayList<>(history), subscriptionStatusRef.get()));
            } else {
                log.trace("History scan completed for {} with no results (postIfEmpty=false)", scanAddress);
            }
        });
    }

    public void startMempoolScan(SilentPaymentScanAddress scanAddress, Integer startHeight, Integer endHeight, WeakReference<SubscriptionStatus> subscriptionStatusRef) {
        queryPool.submit(() -> {
            log.trace("Starting mempool-only scan for address {}, scanForChange={}", scanAddress, scanForChange);
            long scanStartTime = System.currentTimeMillis();

            SilentPaymentsSubscription subscription = new SilentPaymentsSubscription(scanAddress.toString(), startHeight == null ? 0 : startHeight);
            List<TxEntry> mempoolHistory = getMempoolHistory(scanAddress, subscriptionStatusRef, subscription);

            if(!mempoolHistory.isEmpty()) {
                long scanDuration = System.currentTimeMillis() - scanStartTime;
                log.trace("Mempool-only scan completed for {} in {}ms, found {} transactions",
                    scanAddress, scanDuration, mempoolHistory.size());
                Frigate.getEventBus().post(new SilentPaymentsNotification(subscription, PROGRESS_COMPLETE, new ArrayList<>(mempoolHistory), subscriptionStatusRef.get()));
            } else {
                log.trace("Mempool-only scan completed for {} with no results", scanAddress);
            }
        });
    }

    private List<TxEntry> getMempoolHistory(SilentPaymentScanAddress scanAddress, WeakReference<SubscriptionStatus> subscriptionStatusRef, SilentPaymentsSubscription subscription) {
        List<TxEntry> mempoolHistory = mempoolIndex.getHistoryAsync(scanAddress, subscription, null, null, scanForChange, subscriptionStatusRef);
        SubscriptionStatus subscriptionStatus = subscriptionStatusRef.get();
        if(subscriptionStatus != null && subscriptionStatus.getSilentPaymentsMempoolTxids(scanAddress.toString()) != null) {
            mempoolHistory.removeIf(txEntry -> subscriptionStatus.getSilentPaymentsMempoolTxids(scanAddress.toString()).contains(Sha256Hash.wrap(txEntry.tx_hash)));
        }

        return mempoolHistory;
    }
}
