package com.samourai.wallet.psbt;

import com.samourai.wallet.segwit.SegwitAddress;
import com.samourai.wallet.util.FormatsUtilGeneric;
import com.samourai.wallet.util.Z85;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.bitcoinj.core.*;
import org.bouncycastle.util.encoders.Base64;
import org.bouncycastle.util.encoders.Hex;

import java.io.*;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;


//
// Partially Signed Bitcoin Transaction Format
//
public class PSBT {

    public static final int ENCODING_HEX = 0;
    public static final int ENCODING_BASE64 = 1;
    public static final int ENCODING_Z85 = 2;

    public static final byte PSBT_GLOBAL_UNSIGNED_TX = (byte)0x00;
    public static final byte PSBT_GLOBAL_XPUB = (byte)0x01;
    public static final byte PSBT_GLOBAL_VERSION = (byte)0xFB;
    public static final byte PSBT_GLOBAL_PROPRIETARY = (byte)0xFC;

    public static final byte PSBT_IN_NON_WITNESS_UTXO = (byte)0x00;
    public static final byte PSBT_IN_WITNESS_UTXO = (byte)0x01;
    public static final byte PSBT_IN_PARTIAL_SIG = (byte)0x02;
    public static final byte PSBT_IN_SIGHASH_TYPE = (byte)0x03;
    public static final byte PSBT_IN_REDEEM_SCRIPT = (byte)0x04;
    public static final byte PSBT_IN_WITNESS_SCRIPT = (byte)0x05;
    public static final byte PSBT_IN_BIP32_DERIVATION = (byte)0x06;
    public static final byte PSBT_IN_FINAL_SCRIPTSIG = (byte)0x07;
    public static final byte PSBT_IN_FINAL_SCRIPTWITNESS = (byte)0x08;

    public static final byte PSBT_IN_TAP_BIP32_DERIVATION = (byte)0x16;
    public static final byte PSBT_IN_TAP_INTERNAL_KEY = (byte)0x17;
    public static final byte PSBT_IN_POR_COMMITMENT = (byte)0x09;
    public static final byte PSBT_IN_PROPRIETARY = (byte)0xFC;

    public static final byte PSBT_OUT_REDEEM_SCRIPT = (byte)0x00;
    public static final byte PSBT_OUT_WITNESS_SCRIPT = (byte)0x01;
    public static final byte PSBT_OUT_BIP32_DERIVATION = (byte)0x02;
    public static final byte PSBT_OUT_PROPRIETARY = (byte)0xFC;

    public static final String PSBT_MAGIC = "70736274";
    public static final byte PSBT_HEADER_SEPARATOR = (byte)0xFF;

    private static final int HARDENED = 0x80000000;

    /**
     * Inflated PSBT text. Matches the broadcast text envelope: a gzip
     * wrapper must not yield a larger payload than a direct paste.
     */
    static final int MAX_TEXT_CHARS = 1_000_000;

    private static final int STATE_START = 0;
    private static final int STATE_GLOBALS = 1;
    private static final int STATE_INPUTS = 2;
    private static final int STATE_OUTPUTS = 3;
    private static final int STATE_END = 4;

    private int currentState = 0;
    private int inputs = 0;
    private int outputs = 0;
    private int globals = 0;
    private boolean parseOK = false;

    private String strPSBT = null;
    private byte[] psbtBytes = null;
    private ByteBuffer psbtByteBuffer = null;
    private NetworkParameters params = null;
    private Transaction transaction = null;
    private List<PSBTEntry> psbtInputs = null;
    private List<PSBTEntry> psbtOutputs = null;

    private List<PSBTEntry> psbtGlobals = null;

    private StringBuilder sbLog = null;
    private static boolean bDebug = false;

    public PSBT(Transaction transaction)   {
        psbtInputs = new ArrayList<>();
        psbtOutputs = new ArrayList<>();
        psbtGlobals = new ArrayList<>();
        setTransaction(transaction);
        sbLog = new StringBuilder();
    }

    public static PSBT fromBytes(byte[] psbt, NetworkParameters params) throws Exception    {

        PSBT ret = null;

        String strPSBT = null;
        if(psbt.length > 2 && psbt[0] == (byte)0x1f && psbt[1] == (byte)0x8b)   {
            strPSBT = inflatePsbtText(psbt);
        }
        else   {
            strPSBT = Hex.toHexString(psbt);
        }

        if(!FormatsUtilGeneric.getInstance().isPSBT(strPSBT))    {
            throw new Exception("Invalid PSBT string");
        }

        if(FormatsUtilGeneric.getInstance().isBase64(strPSBT) && !FormatsUtilGeneric.getInstance().isHex(strPSBT))    {
            strPSBT = Hex.toHexString(Base64.decode(strPSBT));
        }
        else if(Z85.getInstance().isZ85(strPSBT) && !FormatsUtilGeneric.getInstance().isHex(strPSBT))   {
            strPSBT = Hex.toHexString(Z85.getInstance().decode(strPSBT));
        }
        else    {
            ;
        }

        ret = new PSBT(strPSBT, params);
        ret.setDebug(bDebug);
        ret.read();
        /*
        if(ret.isParseOK())    {
            return ret;
        }
        else    {
            return null;
        }

         */

        return ret;
    }

    public byte[] toBytes()    {

        try {
            return serialize();
        }
        catch(Exception e) {
            return null;
        }

    }

    private PSBT(String strPSBT, NetworkParameters params)   {

        if(!FormatsUtilGeneric.getInstance().isPSBT(strPSBT))    {
            return;
        }

        psbtInputs = new ArrayList<PSBTEntry>();
        psbtOutputs = new ArrayList<PSBTEntry>();
        psbtGlobals = new ArrayList<>();

        if(FormatsUtilGeneric.getInstance().isBase64(strPSBT) && !FormatsUtilGeneric.getInstance().isHex(strPSBT))    {
            this.strPSBT = Hex.toHexString(Base64.decode(strPSBT));
        }
        else if(Z85.getInstance().isZ85(strPSBT) && !FormatsUtilGeneric.getInstance().isHex(strPSBT))   {
            this.strPSBT = Hex.toHexString(Z85.getInstance().decode(strPSBT));
        }
        else    {
            this.strPSBT = strPSBT;
        }

        psbtBytes = Hex.decode(this.strPSBT);
        psbtByteBuffer = ByteBuffer.wrap(psbtBytes);

        sbLog = new StringBuilder();
        this.params = params;
    }

    private PSBT()   {
        ;
    }

    //
    // reader
    //
    public void read() throws Exception    {

        int seenInputs = 0;
        int seenOutputs = 0;

        parseOK = false;
        psbtBytes = Hex.decode(strPSBT);
        psbtByteBuffer = ByteBuffer.wrap(psbtBytes);

        Log("--- ***** START ***** ---", true);
        Log("---  PSBT length:" + psbtBytes.length + " ---", true);
        Log("--- parsing header ---", true);

        byte[] magicBuf = new byte[4];
        try {
            psbtByteBuffer.get(magicBuf);
        }
        catch(BufferUnderflowException e) {
            throw new Exception("Truncated PSBT", e);
        }
        if(!PSBT.PSBT_MAGIC.equalsIgnoreCase(Hex.toHexString(magicBuf)))    {
            throw new Exception("Invalid magic value");
        }

        byte sep;
        try {
            sep = psbtByteBuffer.get();
        }
        catch(BufferUnderflowException e) {
            throw new Exception("Truncated PSBT", e);
        }
        if(sep != PSBT_HEADER_SEPARATOR)    {
            throw new Exception("Bad 0xff separator:" + Hex.toHexString(new byte[] { sep }));
        }

        currentState = STATE_GLOBALS;

        while(psbtByteBuffer.hasRemaining()) {

            if(currentState == STATE_GLOBALS)    {
                Log("--- parsing globals ---", true);
            }
            else if(currentState == STATE_INPUTS)   {
                Log("--- parsing inputs ---", true);
            }
            else if(currentState == STATE_OUTPUTS)   {
                Log("--- parsing outputs ---", true);
            }
            else    {
                ;
            }

            PSBTEntry entry = parse();
            if(entry == null)    {
                throw new Exception("Truncated PSBT");
            }
            entry.setState(currentState);

            if(entry.getKey() == null)    {         // length == 0
                switch (currentState)   {
                    case STATE_GLOBALS:
                        // BIP-174 ends the global map at the first 0x00 separator.
                        psbtGlobals.add(entry);
                        currentState = STATE_INPUTS;
                        break;
                    case STATE_INPUTS:
                        psbtInputs.add(entry);
                        seenInputs++;
                        if(seenInputs == inputs)   {
                            currentState = STATE_OUTPUTS;
                        }
                        break;
                    case STATE_OUTPUTS:
                        psbtOutputs.add(entry);
                        seenOutputs++;
                        if(seenOutputs == outputs)   {
                            currentState = STATE_END;
                        }
                        break;
                    case STATE_END:
                        throw new Exception("Trailing bytes after PSBT");
                    default:
                        throw new Exception("Invalid PSBT state");
                }
            }
            else if(currentState == STATE_GLOBALS)    {
                switch(entry.getKeyType()[0])    {
                    case PSBT.PSBT_GLOBAL_UNSIGNED_TX:
                        Log("transaction", true);
                        transaction = new Transaction(params, entry.getData());
                        inputs = transaction.getInputs().size();
                        outputs = transaction.getOutputs().size();
                        Log("inputs:" + inputs, true);
                        Log("outputs:" + outputs, true);
                        Log(transaction.toString(), true);
                        break;
                    case PSBT.PSBT_GLOBAL_XPUB:
                        if (entry.getKeyData() != null)
                            Log("xpub: " + serializeXPUB(entry.getKeyData()), true);
                        break;
                    case PSBT.PSBT_GLOBAL_VERSION:
                        // The version is the value. This key has no key data.
                        Log("version:" + Hex.toHexString(entry.getData() == null ? new byte[0] : entry.getData()), true);
                        break;
                    case PSBT.PSBT_GLOBAL_PROPRIETARY:
                        Log("proprietary global data", true);
                        break;
                    default:
                        Log("not recognized key type:" + entry.getKeyType()[0], true);
                        break;
                }
            }
            else if(currentState == STATE_INPUTS)    {
                if(entry.getKeyType()[0] >= PSBT_IN_NON_WITNESS_UTXO && entry.getKeyType()[0] <= PSBT_IN_POR_COMMITMENT)    {
                    psbtInputs.add(entry);

                    if(entry.getKeyType()[0] == PSBT_IN_BIP32_DERIVATION)    {
                        Log("fingerprint:" + Hex.toHexString(getFingerprintFromDerivationData(entry.getData())), true);
                    }
                }
                else if(entry.getKeyType()[0] >= PSBT_IN_PROPRIETARY)    {
                    Log("proprietary input data", true);
                }
                else    {
                    Log("not recognized key type:" + entry.getKeyType()[0], true);
                }
            }
            else if(currentState == STATE_OUTPUTS)    {
                if(entry.getKeyType()[0] >= PSBT_OUT_REDEEM_SCRIPT && entry.getKeyType()[0] <= PSBT_OUT_BIP32_DERIVATION)    {
                    psbtOutputs.add(entry);

                    if(entry.getKeyType()[0] == PSBT_OUT_BIP32_DERIVATION)    {
                        Log("fingerprint:" + Hex.toHexString(getFingerprintFromDerivationData(entry.getData())), true);
                    }
                }
                else if(entry.getKeyType()[0] >= PSBT_OUT_PROPRIETARY)    {
                    Log("proprietary output data", true);
                }
                else    {
                    Log("not recognized key type:" + entry.getKeyType()[0], true);
                }
            }
            else if(currentState == STATE_END)    {
                throw new Exception("Trailing bytes after PSBT");
            }
            else    {
                throw new Exception("Invalid PSBT state");
            }

            // Reaching the end state is not success while unread bytes remain.
            if(currentState == STATE_END && psbtByteBuffer.hasRemaining())    {
                throw new Exception("Trailing bytes after PSBT");
            }

        }

        if(currentState != STATE_END || transaction == null)   {
            throw new Exception("Truncated PSBT");
        }

        Log("--- ***** END ***** ---", true);
        parseOK = true;

        Log("", true);

    }

    private PSBTEntry parse() throws Exception    {

        PSBTEntry entry = new PSBTEntry();

        try {
            int keyLen = PSBT.readCompactInt(psbtByteBuffer);
            Log("key length:" + keyLen, true);

            if(keyLen == 0x00)    {
                Log("separator 0x00", true);
                return entry;
            }
            if(keyLen > psbtByteBuffer.remaining())    {
                throw new Exception("Truncated PSBT");
            }

            byte[] key = new byte[keyLen];
            psbtByteBuffer.get(key);
            Log("key:" + Hex.toHexString(key), true);

            byte[] keyType = new byte[1];
            keyType[0] = key[0];
            Log("key type:" + Hex.toHexString(keyType), true);

            byte[] keyData = null;
            if(key.length > 1)    {
                keyData = new byte[key.length - 1];
                System.arraycopy(key, 1, keyData, 0, keyData.length);
                Log("key data:" + Hex.toHexString(keyData), true);
            }

            int dataLen = PSBT.readCompactInt(psbtByteBuffer);
            Log("data length:" + dataLen, true);
            if(dataLen > psbtByteBuffer.remaining())    {
                throw new Exception("Truncated PSBT");
            }

            byte[] data = new byte[dataLen];
            psbtByteBuffer.get(data);
            Log("data:" + Hex.toHexString(data), true);

            entry.setKey(key);
            entry.setKeyType(keyType);
            entry.setKeyData(keyData);
            entry.setData(data);

            return entry;

        }
        catch(BufferUnderflowException e) {
            throw new Exception("Truncated PSBT", e);
        }

    }

    //
    // writer
    //

    public void addGlobalXpubRecord (byte[] xpub, byte[] fingerprint, int purpose, int type, int account) throws Exception {
        addGlobal(PSBT_GLOBAL_XPUB, xpub, writeBIP32Derivation(fingerprint, purpose, type, account));
    }

    public void addGlobalUnsignedTx (String transaction) throws Exception {
        addGlobal(PSBT_GLOBAL_UNSIGNED_TX, null, Hex.decode(transaction));
    }

    public void addGlobal(byte type, byte[] keydata, byte[] data) throws Exception {
        psbtGlobals.add(populateEntry(type, keydata, data));
    }

    public void addInput(NetworkParameters params, byte[] fingerprint, ECKey eckey, long amount, int purpose, int type, int account, int chain, int index, String transactionData, int utxoIndex) throws Exception    {
        // #477: the prev tx arrives verified (hash, value and - on the
        // compose path - script held against the wallet's own derivation).
        // Both utxo fields are written from that one transaction so a signer
        // reads the same truth from either, and the two can never disagree:
        // the witness utxo is the referenced output itself, and its value
        // must equal the amount the caller verified.
        Transaction prevTx = new Transaction(params, Hex.decode(transactionData));
        if(utxoIndex < 0 || utxoIndex >= prevTx.getOutputs().size())    {
            throw new Exception("utxoIndex out of range:" + utxoIndex);
        }
        TransactionOutput referenced = prevTx.getOutput((long)utxoIndex);
        if(referenced.getValue().value != amount)    {
            throw new Exception("witness utxo amount disagrees with prev tx:" + amount + " != " + referenced.getValue().value);
        }

        addInput(PSBT_IN_NON_WITNESS_UTXO, null, Hex.decode(transactionData));
        addInput(PSBT_IN_WITNESS_UTXO, null, referenced.bitcoinSerialize());
        addInput(PSBT_IN_BIP32_DERIVATION, eckey.getPubKey(), writeBIP32Derivation(fingerprint, purpose, type, account, chain, index));
        addInputSeparator();
    }

    public void addInputCompatibility (NetworkParameters params, byte[] fingerprint, ECKey eckey, long amount, int purpose, int type, int account, int chain, int index, String transactionData, int utxoIndex) throws Exception    {
        SegwitAddress compatibleSegwit = new SegwitAddress(eckey, params, 0);

        byte[] redeemScript = Hex.decode(compatibleSegwit.segwitRedeemScriptToString());


        // #477: parse with the caller's network, not a hardcoded TestNet3,
        // and hold the arm to the same agreement rule: the witness utxo is
        // read from the referenced output, so the amount must match it.
        Transaction tx = new Transaction(params, Hex.decode(transactionData));
        if(utxoIndex < 0 || utxoIndex >= tx.getOutputs().size())    {
            throw new Exception("utxoIndex out of range:" + utxoIndex);
        }
        if(tx.getOutput((long)utxoIndex).getValue().value != amount)    {
            throw new Exception("witness utxo amount disagrees with prev tx:" + amount);
        }

        addInput(PSBT_IN_NON_WITNESS_UTXO, null, Hex.decode(transactionData));
        addInput(PSBT_IN_WITNESS_UTXO, null, tx.getOutput(utxoIndex).bitcoinSerialize());
        addInput(PSBT_IN_SIGHASH_TYPE, null, Hex.decode("01000000"));
        addInput(PSBT_IN_REDEEM_SCRIPT, null, redeemScript);
        addInput(PSBT_IN_BIP32_DERIVATION, eckey.getPubKey(), writeBIP32Derivation(fingerprint, purpose, type, account, chain, index));
        addInputSeparator();
    }

    public void addInputLegacy (NetworkParameters params, byte[] fingerprint, ECKey eckey, long amount, int purpose, int type, int account, int chain, int index, String transactionData, int utxoIndex) throws Exception {
        // #477: the legacy arm carried the whole prev tx but never checked
        // the amount against it - the amount parameter was simply unused.
        // The referenced output's value must now agree with what the caller
        // verified, the same rule as the SegWit arms.
        Transaction prevTx = new Transaction(params, Hex.decode(transactionData));
        if(utxoIndex < 0 || utxoIndex >= prevTx.getOutputs().size())    {
            throw new Exception("utxoIndex out of range:" + utxoIndex);
        }
        if(prevTx.getOutput((long)utxoIndex).getValue().value != amount)    {
            throw new Exception("non-witness utxo amount disagrees with prev tx:" + amount);
        }

        addInput(PSBT_IN_NON_WITNESS_UTXO, null, Hex.decode(transactionData));
        addInput(PSBT_IN_SIGHASH_TYPE, null, Hex.decode("01000000"));
        addInput(PSBT_IN_BIP32_DERIVATION, eckey.getPubKey(), writeBIP32Derivation(fingerprint, purpose, type, account, chain, index));
        addInputSeparator();
    }

    /*
        public void addInputTaproot (NetworkParameters params, byte[] fingerprint, ECKey eckey, long amount, int purpose, int type, int account, int chain, int index, String transactionData) throws Exception {
            SegwitAddress taprootAddress = new SegwitAddress(eckey, params);
            byte[] redeemScriptBuf = new byte[1 + taprootAddress.taprootRedeemScript().getProgram().length];
            redeemScriptBuf[0] = (byte)0x16;
            System.arraycopy(taprootAddress.taprootRedeemScript().getProgram(), 0, redeemScriptBuf, 1, taprootAddress.taprootRedeemScript().getProgram().length);
            byte[] utxoBuf = writeSegwitInputUTXO(amount, redeemScriptBuf);

            byte[] derivation = writeBIP32Derivation(fingerprint, purpose, type, account, chain, index);

            byte[] combined = new byte[1 + derivation.length];
            combined[0] = 0x00;
            System.arraycopy(derivation,0, combined, 1, derivation.length);

            addInput(PSBT_IN_NON_WITNESS_UTXO, null, Hex.decode(transactionData));
            addInput(PSBT_IN_WITNESS_UTXO, null, utxoBuf);
            addInput(PSBT_IN_SIGHASH_TYPE, null, Hex.decode("00000000"));
            addInput(PSBT_IN_BIP32_DERIVATION, eckey.getPubKey(), writeBIP32Derivation(fingerprint, purpose, type, account, chain, index));
            addInput(PSBT_IN_TAP_BIP32_DERIVATION, BIP340Util.getInternalPubkey(eckey).getX().toByteArray(), combined);
            addInput(PSBT_IN_TAP_INTERNAL_KEY, null, BIP340Util.getInternalPubkey(eckey).getX().toByteArray());
        }


     */
    public void addInput(byte type, byte[] keydata, byte[] data) throws Exception {
        psbtInputs.add(populateEntry(type, keydata, data));
    }

    public void addOutput(NetworkParameters params, byte[] fingerprint, ECKey eckey, int purpose, int type, int account, int chain, int index) throws Exception    {
        if (purpose != 44 && purpose != 86) { // DON'T ADD THIS FLAG IF IT'S LEGACY OR TAPROOT
            SegwitAddress compatibleSegwit = new SegwitAddress(eckey, params);
            byte[] redeemScript = Hex.decode(compatibleSegwit.segwitRedeemScriptToString());
            addOutput(PSBT_OUT_REDEEM_SCRIPT, null, redeemScript);
        }

        addOutput(PSBT_OUT_BIP32_DERIVATION, eckey.getPubKey(), writeBIP32Derivation(fingerprint, purpose, type, account, chain, index));
        addOutputSeparator();
    }

    public void addOutput(byte type, byte[] keydata, byte[] data) throws Exception {
        psbtOutputs.add(populateEntry(type, keydata, data));
    }

    public void addInputSeparator()  {
        psbtInputs.add(new PSBTEntry());
    }

    public void addOutputSeparator()    {
        psbtOutputs.add(new PSBTEntry());
    }

    public void addGlobalSeparator() {
        psbtGlobals.add(new PSBTEntry());
    }

    private PSBTEntry populateEntry(byte type, byte[] keydata, byte[] data) throws Exception {

        PSBTEntry entry = new PSBTEntry();
        entry.setKeyType(new byte[] { type });
        if(keydata != null)    {
            byte[] kd = new byte[1 + keydata.length];
            kd[0] = type;
            System.arraycopy(keydata, 0, kd, 1, keydata.length);
            entry.setKey(kd);
            entry.setKeyData(keydata);
        }
        else    {
            entry.setKey(new byte[] { type });
        }
        entry.setData(data);

        return entry;
    }

    private byte[] serialize() throws IOException {

        byte[] serialized = transaction.bitcoinSerialize();
        byte[] txLen = PSBT.writeCompactInt(serialized.length);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        // magic
        baos.write(Hex.decode(PSBT.PSBT_MAGIC), 0, Hex.decode(PSBT.PSBT_MAGIC).length);

        // separator
        baos.write(PSBT_HEADER_SEPARATOR);

        // One unsigned-transaction global, then the BIP-174 0x00 separator.
        baos.write(writeCompactInt(1L));                                // key length
        baos.write((byte)0x00);                                             // key
        baos.write(txLen, 0, txLen.length);                             // value length
        baos.write(serialized, 0, serialized.length);                   // value
        baos.write((byte)0x00);

        // inputs
        for(PSBTEntry entry : psbtInputs)   {
            if(entry.getKey() == null)   {
                baos.write((byte)0x00);
            }
            else   {
                int keyLen = 1;
                if(entry.getKeyData() != null)    {
                    keyLen += entry.getKeyData().length;
                }
                baos.write(writeCompactInt(keyLen));
                baos.write(entry.getKey());
                baos.write(writeCompactInt(entry.getData().length));
                baos.write(entry.getData());
            }
        }

        // outputs
        for(PSBTEntry entry : psbtOutputs)   {
            if(entry.getKey() == null)   {
                baos.write((byte)0x00);
            }
            else   {
                int keyLen = 1;
                if(entry.getKeyData() != null)    {
                    keyLen += entry.getKeyData().length;
                }
                baos.write(writeCompactInt(keyLen));
                baos.write(entry.getKey());
                baos.write(writeCompactInt(entry.getData().length));
                baos.write(entry.getData());
            }
        }

        psbtBytes = baos.toByteArray();
        strPSBT = Hex.toHexString(psbtBytes);
//        Log("psbt:" + strPSBT, true);

        return psbtBytes;
    }

    //
    //
    //

    public List<PSBTEntry> getPsbtInputs() {
        return psbtInputs;
    }

    public void setPsbtInputs(List<PSBTEntry> psbtInputs) {
        this.psbtInputs = psbtInputs;
    }

    public List<PSBTEntry> getPsbtOutputs() {
        return psbtOutputs;
    }

    public void setPsbtOutputs(List<PSBTEntry> psbtOutputs) {
        this.psbtOutputs = psbtOutputs;
    }

    public Transaction getTransaction() {
        return transaction;
    }

    public void setTransaction(Transaction transaction) {
        this.transaction = transaction;
        this.params = transaction.getParams();
    }

    public int getInputCount() {
        return inputs;
    }

    public int getOutputCount() {
        return outputs;
    }

    public void clear()  {
        transaction = new Transaction(params);
        psbtInputs.clear();
        psbtOutputs.clear();
        strPSBT = null;
        psbtBytes = null;
        psbtByteBuffer.clear();
    }

    //
    // utils
    //
    public String toString()    {
        return Hex.toHexString(toBytes());
    }

    public String toBase64String()  {
        return Base64.toBase64String(toBytes());
    }

    public String toZ85String() {
        return Z85.getInstance().encode(toBytes());
    }

    public byte[] toGZIP() throws IOException {

        String data = toString();

        ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length());
        GZIPOutputStream gzip = new GZIPOutputStream(bos);
        gzip.write(data.getBytes());
        gzip.close();
        byte[] compressed = bos.toByteArray();
        bos.close();
        return compressed;
    }

    public static int readCompactInt(ByteBuffer psbtByteBuffer) throws Exception  {

        byte b = psbtByteBuffer.get();

        switch(b)    {
            case (byte)0xfd: {
                byte[] buf = new byte[2];
                psbtByteBuffer.get(buf);
                return (buf[0] & 0xff) | ((buf[1] & 0xff) << 8);
            }
            case (byte)0xfe: {
                byte[] buf = new byte[4];
                psbtByteBuffer.get(buf);
                long value = (buf[0] & 0xffL)
                        | ((buf[1] & 0xffL) << 8)
                        | ((buf[2] & 0xffL) << 16)
                        | ((buf[3] & 0xffL) << 24);
                if(value > Integer.MAX_VALUE)    {
                    throw new Exception("Data too long:" + value);
                }
                return (int)value;
            }
            case (byte)0xff: {
                byte[] buf = new byte[8];
                psbtByteBuffer.get(buf);
                ByteBuffer byteBuffer = ByteBuffer.wrap(buf);
                byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
                throw new Exception("Data too long:" + byteBuffer.getLong());
            }
            default:
                return (int)(b & 0xff);
        }

    }

    /**
     * Inflates a gzip PSBT and drops CR/LF, the same way the old line
     * reader did. Stops once the inflated text passes {@link #MAX_TEXT_CHARS}.
     */
    private static String inflatePsbtText(byte[] psbt) throws Exception {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(psbt);
             GZIPInputStream gis = new GZIPInputStream(bis)) {
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int total = 0;
            int n;
            while ((n = gis.read(buf)) >= 0) {
                if (n > MAX_TEXT_CHARS - total) {
                    throw new Exception("Data too long:" + (total + (long) n));
                }
                total += n;
                raw.write(buf, 0, n);
            }
            return raw.toString("UTF-8").replace("\r", "").replace("\n", "");
        }
    }

    public static byte[] writeCompactInt(long val)   {

        ByteBuffer bb = null;

        if(val < 0xfdL)    {
            bb = ByteBuffer.allocate(1);
            bb.order(ByteOrder.LITTLE_ENDIAN);
            bb.put((byte)val);
        }
        else if(val < 0xffffL)   {
            bb = ByteBuffer.allocate(3);
            bb.order(ByteOrder.LITTLE_ENDIAN);
            bb.put((byte)0xfd);
            /*
            bb.put((byte)(val & 0xff));
            bb.put((byte)((val >> 8) & 0xff));
            */
            bb.putShort((short)val);
        }
        else if(val < 0xffffffffL)   {
            bb = ByteBuffer.allocate(5);
            bb.order(ByteOrder.LITTLE_ENDIAN);
            bb.put((byte)0xfe);
            bb.putInt((int)val);
        }
        else    {
            bb = ByteBuffer.allocate(9);
            bb.order(ByteOrder.LITTLE_ENDIAN);
            bb.put((byte)0xff);
            bb.putLong(val);
        }

        return bb.array();
    }

    public static Pair<Long, Byte[]> readSegwitInputUTXO(byte[] utxo)    {
        byte[] val = new byte[8];
        byte[] scriptPubKey = new byte[utxo.length - val.length];

        System.arraycopy(utxo, 0, val, 0, val.length);
        System.arraycopy(utxo, val.length, scriptPubKey, 0, scriptPubKey.length);

        ArrayUtils.reverse(val);
        long lval = Long.parseLong(Hex.toHexString(val), 16);

        int i = 0;
        Byte[] scriptPubKeyBuf = new Byte[scriptPubKey.length];
        for(byte b : scriptPubKey)   {
            scriptPubKeyBuf[i++] = b;
        }

        return Pair.of(Long.valueOf(lval), scriptPubKeyBuf);
    }

    public static byte[] writeSegwitInputUTXO(long value, byte[] scriptPubKey)    {

        byte[] ret = new byte[scriptPubKey.length + Long.BYTES];

        // long to byte array
        ByteBuffer xlat = ByteBuffer.allocate(Long.BYTES);
        xlat.order(ByteOrder.LITTLE_ENDIAN);
        xlat.putLong(0, value);
        byte[] val = new byte[Long.BYTES];
        xlat.get(val);

        System.arraycopy(val, 0, ret, 0, Long.BYTES);
        System.arraycopy(scriptPubKey, 0, ret, Long.BYTES, scriptPubKey.length);

        return ret;
    }

    public static String readBIP32Derivation(byte[] path) {

        byte[] dbuf = new byte[path.length];
        System.arraycopy(path, 0, dbuf, 0, path.length);
        ByteBuffer bb = ByteBuffer.wrap(dbuf);
        byte[] buf = new byte[4];

        // fingerprint
        bb.get(buf);
        byte[] fingerprint = new byte[4];
        System.arraycopy(buf, 0, fingerprint, 0, fingerprint.length);
        // purpose
        bb.get(buf);
        ArrayUtils.reverse(buf);
        ByteBuffer pbuf = ByteBuffer.wrap(buf);
        int purpose = pbuf.getInt();
        if(purpose >= HARDENED)    {
            purpose -= HARDENED;
        }

        // coin type
        bb.get(buf);
        ArrayUtils.reverse(buf);
        ByteBuffer tbuf = ByteBuffer.wrap(buf);
        int type = tbuf.getInt();
        if(type >= HARDENED)    {
            type -= HARDENED;
        }
//        System.out.println("type:" + type);

        // account
        bb.get(buf);
        ArrayUtils.reverse(buf);
        ByteBuffer abuf = ByteBuffer.wrap(buf);
        int account = abuf.getInt();
        if(account >= HARDENED)    {
            account -= HARDENED;
        }

        // chain
        bb.get(buf);
        ArrayUtils.reverse(buf);
        ByteBuffer cbuf = ByteBuffer.wrap(buf);
        int chain = cbuf.getInt();

        // index
        bb.get(buf);
        ArrayUtils.reverse(buf);
        ByteBuffer ibuf = ByteBuffer.wrap(buf);
        int index = ibuf.getInt();

        String ret = "m/" + purpose + "'/" + type + "'/" + account + "'/" + chain + "/" + index;

        return ret;
    }

    public static byte[] writeBIP32Derivation(byte[] fingerprint, int purpose, int type, int account, int chain, int index) {

        // fingerprint and integer values to BIP32 derivation buffer
        byte[] bip32buf = new byte[24];

        System.arraycopy(fingerprint, 0, bip32buf, 0, fingerprint.length);

        ByteBuffer xlat = ByteBuffer.allocate(Integer.BYTES);
        xlat.order(ByteOrder.LITTLE_ENDIAN);
        xlat.putInt(0, purpose + HARDENED);
        byte[] out = new byte[Integer.BYTES];
        xlat.get(out);
        System.arraycopy(out, 0, bip32buf, fingerprint.length, out.length);

        xlat.clear();
        xlat.order(ByteOrder.LITTLE_ENDIAN);
        xlat.putInt(0, type + HARDENED);
        xlat.get(out);
        System.arraycopy(out, 0, bip32buf, fingerprint.length + out.length, out.length);

        xlat.clear();
        xlat.order(ByteOrder.LITTLE_ENDIAN);
        xlat.putInt(0, account + HARDENED);
        xlat.get(out);
        System.arraycopy(out, 0, bip32buf, fingerprint.length + (out.length * 2), out.length);

        xlat.clear();
        xlat.order(ByteOrder.LITTLE_ENDIAN);
        xlat.putInt(0, chain);
        xlat.get(out);
        System.arraycopy(out, 0, bip32buf, fingerprint.length + (out.length * 3), out.length);

        xlat.clear();
        xlat.order(ByteOrder.LITTLE_ENDIAN);
        xlat.putInt(0, index);
        xlat.get(out);
        System.arraycopy(out, 0, bip32buf, fingerprint.length + (out.length * 4), out.length);

        return bip32buf;
    }

    public static byte[] writeBIP32Derivation(byte[] fingerprint, int purpose, int type, int account) {

        // fingerprint and integer values to BIP32 derivation buffer
        byte[] bip32buf = new byte[16];

        System.arraycopy(fingerprint, 0, bip32buf, 0, fingerprint.length);

        ByteBuffer xlat = ByteBuffer.allocate(Integer.BYTES);
        xlat.order(ByteOrder.LITTLE_ENDIAN);
        xlat.putInt(0, purpose + HARDENED);
        byte[] out = new byte[Integer.BYTES];
        xlat.get(out);
        System.arraycopy(out, 0, bip32buf, fingerprint.length, out.length);

        xlat.clear();
        xlat.order(ByteOrder.LITTLE_ENDIAN);
        xlat.putInt(0, type + HARDENED);
        xlat.get(out);
        System.arraycopy(out, 0, bip32buf, fingerprint.length + out.length, out.length);

        xlat.clear();
        xlat.order(ByteOrder.LITTLE_ENDIAN);
        xlat.putInt(0, account + HARDENED);
        xlat.get(out);
        System.arraycopy(out, 0, bip32buf, fingerprint.length + (out.length * 2), out.length);

        return bip32buf;
    }

    public static String serializeXPUB(byte[] buf)   {
        // append checksum
        byte[] checksum = Arrays.copyOfRange(Sha256Hash.hashTwice(buf), 0, 4);
        byte[] xlatXpub = new byte[buf.length + checksum.length];
        System.arraycopy(buf, 0, xlatXpub, 0, buf.length);
        System.arraycopy(checksum, 0, xlatXpub, xlatXpub.length - 4, checksum.length);

        String ret = Base58.encode(xlatXpub);
        return ret;
    }

    public static byte[] deserializeXPUB(String xpub)   {
        byte[] decoded  = Base58.decode(xpub);
        if (decoded.length < 4)
            throw new AddressFormatException("Input too short");
        byte[] data = Arrays.copyOfRange(decoded, 0, decoded.length - 4);
        byte[] checksum = Arrays.copyOfRange(decoded, decoded.length - 4, decoded.length);
        byte[] actualChecksum = Arrays.copyOfRange(Sha256Hash.hashTwice(data), 0, 4);
        if (!Arrays.equals(checksum, actualChecksum))
            throw new AddressFormatException("Checksum does not validate");

        return data;
    }

    private static byte[] getFingerprintFromDerivationData(byte[] buf) throws AddressFormatException {

        byte[] fingerprint = new byte[4];
        System.arraycopy(buf, 0, fingerprint, 0, fingerprint.length);

        return fingerprint;
    }

    public boolean isParseOK() {
        return parseOK;
    }

    public static boolean getDebug() {
        return bDebug;
    }

    public static void setDebug(boolean debug) {
        bDebug = debug;
    }

    public String dump()    {
        if(bDebug)  {
            return sbLog.toString();
        }
        else  {
            return null;
        }
    }

    private void Log(String s, boolean eol)  {

        if(bDebug)  {
            sbLog.append(s);
            if(eol)    {
                sbLog.append("\n");
            }
        }

    }

}