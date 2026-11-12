package com.example.naturalcam.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbConstants
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Risultato di un comando SCSI eseguito tramite USB Mass Storage BOT.
 *
 * cswStatus:
 * 0 = comando completato
 * 1 = comando rifiutato dal dispositivo
 * 2 = errore di fase BOT
 */
data class ScsiCommandResult(
    val data: ByteArray,
    val cswStatus: Int,
    val residue: Long,
    val tag: Long
) {
    val successful: Boolean
        get() = cswStatus == 0
}

/**
 * Trasporto USB Mass Storage Bulk-Only Transport.
 *
 * Non esegue operazioni di scrittura sulla memoria:
 * i metodi SCSI esposti in questa prima versione sono di sola lettura.
 *
 * La UsbDeviceConnection deve essere già aperta e l'interfaccia USB
 * deve essere già stata acquisita con claimInterface().
 */
class MassStorageTransport(
    private val connection: UsbDeviceConnection,
    private val bulkIn: UsbEndpoint,
    private val bulkOut: UsbEndpoint,
    private val timeoutMs: Int = 5000
) {

    companion object {
        private const val CBW_SIGNATURE = 0x43425355
        private const val CSW_SIGNATURE = 0x53425355

        private const val CBW_LENGTH = 31
        private const val CSW_LENGTH = 13

        private const val FLAG_DATA_IN = 0x80
        private const val LUN = 0
    }

    private val tagCounter = AtomicInteger(1)

    init {
        require(bulkIn.direction == UsbConstants.USB_DIR_IN) {
            "L'endpoint bulkIn deve essere IN"
        }

        require(bulkOut.direction == UsbConstants.USB_DIR_OUT) {
            "L'endpoint bulkOut deve essere OUT"
        }

        require(bulkIn.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
            "bulkIn non è un endpoint Bulk"
        }

        require(bulkOut.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
            "bulkOut non è un endpoint Bulk"
        }
    }

    /**
     * Esegue un comando SCSI attraverso il protocollo BOT.
     *
     * @param cdb comando SCSI
     * @param dataInLength numero di byte attesi nella fase Data-In
     */
    @Synchronized
    @Throws(IOException::class)
    fun executeScsi(
        cdb: ByteArray,
        dataInLength: Int = 0
    ): ScsiCommandResult {

        require(cdb.isNotEmpty()) {
            "Il CDB non può essere vuoto"
        }

        require(cdb.size <= 16) {
            "Il CDB non può superare 16 byte"
        }

        require(dataInLength >= 0) {
            "Lunghezza dati non valida"
        }

        val tag = tagCounter.getAndIncrement().toLong() and 0xFFFFFFFFL

        // 1. Costruzione del Command Block Wrapper (CBW)
        val cbw = ByteArray(CBW_LENGTH)

        putIntLE(cbw, 0, CBW_SIGNATURE)
        putIntLE(cbw, 4, tag.toInt())
        putIntLE(cbw, 8, dataInLength)

        cbw[12] = if (dataInLength > 0) FLAG_DATA_IN.toByte() else 0
        cbw[13] = LUN.toByte()
        cbw[14] = cdb.size.toByte()

        System.arraycopy(
            cdb,
            0,
            cbw,
            15,
            cdb.size
        )

        // 2. Invio del CBW sull'endpoint OUT
        val cbwSent = connection.bulkTransfer(
            bulkOut,
            cbw,
            cbw.size,
            timeoutMs
        )

        if (cbwSent != CBW_LENGTH) {
            throw IOException(
                "Invio CBW fallito: $cbwSent byte inviati, attesi $CBW_LENGTH"
            )
        }

        // 3. Eventuale fase Data-In
        val data = if (dataInLength > 0) {
            readData(dataInLength)
        } else {
            ByteArray(0)
        }

        // 4. Lettura del Command Status Wrapper (CSW)
        val csw = readExact(CSW_LENGTH)

        val signature = getIntLE(csw, 0)
        val receivedTag = getIntLE(csw, 4).toLong() and 0xFFFFFFFFL
        val residue = getIntLE(csw, 8).toLong() and 0xFFFFFFFFL
        val status = csw[12].toInt() and 0xFF

        if (signature != CSW_SIGNATURE) {
            throw IOException(
                "Firma CSW non valida: 0x${signature.toUInt().toString(16)}"
            )
        }

        if (receivedTag != tag) {
            throw IOException(
                "Tag CSW non corrispondente: ricevuto $receivedTag, atteso $tag"
            )
        }

        if (status !in 0..2) {
            throw IOException("Stato CSW sconosciuto: $status")
        }

        return ScsiCommandResult(
            data = data,
            cswStatus = status,
            residue = residue,
            tag = tag
        )
    }

    /**
     * SCSI INQUIRY (0x12)
     * Richiede le informazioni identificative del dispositivo.
     */
    @Throws(IOException::class)
    fun inquiry(): ScsiCommandResult {
        val cdb = ByteArray(6)

        cdb[0] = 0x12
        cdb[4] = 36

        return executeScsi(
            cdb = cdb,
            dataInLength = 36
        )
    }

    /**
     * SCSI TEST UNIT READY (0x00)
     * Verifica che l'unità di archiviazione sia pronta.
     */
    @Throws(IOException::class)
    fun testUnitReady(): ScsiCommandResult {
        val cdb = ByteArray(6)

        cdb[0] = 0x00

        return executeScsi(cdb)
    }

    /**
     * SCSI READ CAPACITY (10) (0x25)
     * Legge l'ultimo LBA e la dimensione del blocco.
     *
     * Il comando non scrive nulla sulla memoria.
     */
    @Throws(IOException::class)
    fun readCapacity10(): ScsiCommandResult {
        val cdb = ByteArray(10)

        cdb[0] = 0x25

        return executeScsi(
            cdb = cdb,
            dataInLength = 8
        )
    }

    /**
     * Legge la risposta Data-In.
     * Gestisce anche trasferimenti suddivisi in più chiamate USB.
     */
    @Throws(IOException::class)
    private fun readData(expectedLength: Int): ByteArray {
        val buffer = ByteArray(expectedLength)
        var totalRead = 0

        while (totalRead < expectedLength) {
            val remaining = expectedLength - totalRead
            val chunkSize = minOf(remaining, 16 * 1024)
            val temporary = ByteArray(chunkSize)

            val received = connection.bulkTransfer(
                bulkIn,
                temporary,
                chunkSize,
                timeoutMs
            )

            if (received < 0) {
                throw IOException(
                    "Errore durante Data-In: bulkTransfer ha restituito $received"
                )
            }

            if (received == 0) {
                break
            }

            System.arraycopy(
                temporary,
                0,
                buffer,
                totalRead,
                received
            )

            totalRead += received
        }

        return buffer.copyOf(totalRead)
    }

    /**
     * Legge esattamente il numero di byte richiesto.
     * Utilizzato per il CSW.
     */
    @Throws(IOException::class)
    private fun readExact(length: Int): ByteArray {
        val buffer = ByteArray(length)
        var totalRead = 0

        while (totalRead < length) {
            val remaining = length - totalRead
            val temporary = ByteArray(remaining)

            val received = connection.bulkTransfer(
                bulkIn,
                temporary,
                remaining,
                timeoutMs
            )

            if (received <= 0) {
                throw IOException(
                    "Lettura CSW incompleta: ricevuti $totalRead di $length byte"
                )
            }

            System.arraycopy(
                temporary,
                0,
                buffer,
                totalRead,
                received
            )

            totalRead += received
        }

        return buffer
    }

    private fun putIntLE(
        buffer: ByteArray,
        offset: Int,
        value: Int
    ) {
        buffer[offset] = (value and 0xFF).toByte()
        buffer[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        buffer[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        buffer[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun getIntLE(
        buffer: ByteArray,
        offset: Int
    ): Int {
        return (buffer[offset].toInt() and 0xFF) or
                ((buffer[offset + 1].toInt() and 0xFF) shl 8) or
                ((buffer[offset + 2].toInt() and 0xFF) shl 16) or
                ((buffer[offset + 3].toInt() and 0xFF) shl 24)
    }

    /**
     * SCSI READ(10) - opcode 0x28.
     *
     * Legge uno o più settori dalla memoria.
     * Non modifica in alcun modo i dati.
     */
    @Throws(IOException::class)
    fun readSectors(
        lba: Long,
        sectorCount: Int,
        sectorSize: Int = 512
    ): ScsiCommandResult {

        require(lba in 0..0xFFFFFFFFL) {
            "LBA fuori intervallo"
        }

        require(sectorCount in 1..65535) {
            "Numero di settori non valido"
        }

        require(sectorSize > 0) {
            "Dimensione settore non valida"
        }

        val cdb = ByteArray(10)

        cdb[0] = 0x28 // READ(10)

        // LBA: 4 byte big-endian
        cdb[2] = ((lba ushr 24) and 0xFF).toByte()
        cdb[3] = ((lba ushr 16) and 0xFF).toByte()
        cdb[4] = ((lba ushr 8) and 0xFF).toByte()
        cdb[5] = (lba and 0xFF).toByte()

        // Numero settori: 2 byte big-endian
        cdb[7] = ((sectorCount ushr 8) and 0xFF).toByte()
        cdb[8] = (sectorCount and 0xFF).toByte()

        val expectedLength = Math.multiplyExact(
            sectorCount,
            sectorSize
        )

        return executeScsi(
            cdb = cdb,
            dataInLength = expectedLength
        )
    }
}