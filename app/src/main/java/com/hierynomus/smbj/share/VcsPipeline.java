package com.hierynomus.smbj.share;

import com.hierynomus.mserref.NtStatus;
import com.hierynomus.mssmb2.messages.SMB2ReadResponse;
import com.hierynomus.smbj.common.SMBRuntimeException;

import java.util.concurrent.Future;

/**
 * smbj sa mandare una lettura senza aspettare la risposta (readAsync), ma non la rende pubblica: questo ponte, nello
 * stesso pacchetto, permette a ViewerCraftStar di tenere più letture "in volo" sulla stessa connessione (a casa, in
 * Wi-Fi, da 28 a 97 MB/s: misurato il 25/11/2026).
 */
public final class VcsPipeline {
    private VcsPipeline() {}

    public static Future<SMB2ReadResponse> readAsync(File file, long offset, int length) {
        return file.readAsync(offset, length);
    }

    /** La dimensione massima di una lettura su questa connessione (quella che usa smbj per le sue). */
    public static int chunk(File file) {
        return file.getDiskShare().getReadBufferSize();
    }

    /** I byte di una risposta: vuoti a fine file, eccezione se il NAS ha risposto con un errore. */
    public static byte[] data(SMB2ReadResponse r) {
        long status = r.getHeader().getStatusCode();
        if (status == NtStatus.STATUS_END_OF_FILE.getValue()) return new byte[0];
        if (!NtStatus.isSuccess(status)) throw new SMBRuntimeException("lettura non riuscita: 0x" + Long.toHexString(status));
        return r.getData();
    }
}
