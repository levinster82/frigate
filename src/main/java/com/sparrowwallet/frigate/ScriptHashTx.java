package com.sparrowwallet.frigate;

import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.wallet.BlockTransaction;
import com.sparrowwallet.drongo.wallet.BlockTransactionHash;

public class ScriptHashTx {
    public int height;
    public String tx_hash;
    public long fee;

    public ScriptHashTx() {}

    public ScriptHashTx(int height, String tx_hash, long fee) {
        this.height = height;
        this.tx_hash = tx_hash;
        this.fee = fee;
    }

    public BlockTransactionHash getBlockchainTransactionHash() {
        Sha256Hash hash = Sha256Hash.wrap(tx_hash);
        return new BlockTransaction(hash, height, null, fee, null);
    }

    @Override
    public String toString() {
        return "ScriptHashTx{height=" + height + ", tx_hash='" + tx_hash + '\'' + ", fee=" + fee + '}';
    }
}
