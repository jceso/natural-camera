package com.example.naturalcam

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

import android.hardware.usb.UsbConstants
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import com.example.naturalcam.usb.MassStorageTransport

import androidx.core.content.ContextCompat
import com.example.naturalcam.usb.MbrParser

import android.graphics.BitmapFactory
import android.view.ViewGroup
import android.widget.ImageView
import com.example.naturalcam.usb.Fat32File
import com.example.naturalcam.usb.Fat32Reader

class FirstFragment : AppCompatActivity() {

    companion object {
        private const val ACTION_USB_PERMISSION = "com.example.naturalcam.USB_PERMISSION"
        private const val ROLLEI_VENDOR_ID = 0x1B3F
        private const val ROLLEI_PRODUCT_ID = 0x8301
    }

    private lateinit var root: LinearLayout

    private lateinit var usbManager: UsbManager
    private lateinit var statusText: TextView
    private lateinit var deviceText: TextView
    private lateinit var protocolText: TextView
    private lateinit var logText: TextView
    private lateinit var scanButton: Button
    private lateinit var infoButton: Button

    private var selectedDevice: UsbDevice? = null
    private var receiverRegistered = false

    private lateinit var photoListContainer: LinearLayout
    private lateinit var photoPreview: ImageView

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return

            val device = getUsbDeviceFromIntent(intent)
            val granted = intent.getBooleanExtra(
                UsbManager.EXTRA_PERMISSION_GRANTED,
                false
            )

            if (granted && device != null) {
                selectedDevice = device
                statusText.text = "Permesso USB concesso"
                showUsbInformation(device)
            } else {
                statusText.text = "Permesso USB negato"
                logText.text =
                    "Android non ha concesso l'accesso alla periferica."
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        createInterface()
        addPhotoBrowser(root)
        scanUsbDevices()
    }

    override fun onStart() {
        super.onStart()

        val filter = IntentFilter(ACTION_USB_PERMISSION)

        ContextCompat.registerReceiver(
            this,
            usbPermissionReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        receiverRegistered = true
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(usbPermissionReceiver)
            receiverRegistered = false
        }

        super.onStop()
    }

    private fun createInterface() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(24))
        }

        val scrollView = ScrollView(this).apply {
            addView(root)
        }

        val title = TextView(this).apply {
            text = "Natural Camera"
            textSize = 28f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        root.addView(title)

        root.addView(textView("Diagnostica connessione USB", 16f))
        root.addView(spacer(24))

        root.addView(sectionTitle("CONNESSIONE FOTOCAMERA"))

        statusText = textView("In attesa di connessione", 18f)
        root.addView(statusText)

        root.addView(
            textView(
                "Collega la Rollei tramite USB OTG e avvia la scansione.",
                14f
            )
        )

        root.addView(spacer(20))

        scanButton = Button(this).apply {
            text = "Scansiona dispositivi USB"
            setOnClickListener { scanUsbDevices() }
        }
        root.addView(scanButton)

        root.addView(spacer(24))
        root.addView(sectionTitle("DISPOSITIVO"))

        deviceText = textView("Nessuna fotocamera rilevata", 15f)
        root.addView(deviceText)

        root.addView(spacer(20))
        root.addView(sectionTitle("PROTOCOLLO USB"))

        protocolText = textView("Informazioni non disponibili", 15f)
        root.addView(protocolText)

        root.addView(spacer(16))

        infoButton = Button(this).apply {
            text = "Leggi informazioni USB"
            isEnabled = false
            setOnClickListener { requestUsbInformation() }
        }
        root.addView(infoButton)

        root.addView(spacer(24))
        root.addView(sectionTitle("LOG DIAGNOSTICO"))

        logText = textView("In attesa di un dispositivo USB.", 13f)
        root.addView(logText)

        root.addView(spacer(24))
        root.addView(sectionTitle("TEST COMUNICAZIONE"))

        addScsiTestButton(root)

        root.addView(spacer(24))
        root.addView(sectionTitle("PROSSIME FUNZIONALITÀ"))

        root.addView(textView("✓ Rilevamento dispositivo USB", 14f))
        root.addView(textView("○ Lettura memoria FAT32", 14f))
        root.addView(textView("○ Importazione fotografie", 14f))
        root.addView(textView("○ Profilo Natural", 14f))

        setContentView(scrollView)
    }

    private fun scanUsbDevices() {
        val devices = usbManager.deviceList.values.toList()

        selectedDevice = null
        infoButton.isEnabled = false

        if (devices.isEmpty()) {
            statusText.text = "Nessun dispositivo USB rilevato"
            deviceText.text = "Nessuna fotocamera rilevata"
            protocolText.text = "Non disponibile"
            logText.text = "Collega la Rollei tramite OTG e riprova."
            return
        }

        val rollei = devices.firstOrNull {
            it.vendorId == ROLLEI_VENDOR_ID &&
                    it.productId == ROLLEI_PRODUCT_ID
        }

        val device = rollei ?: devices.first()

        selectedDevice = device
        infoButton.isEnabled = true

        statusText.text = if (rollei != null) {
            "Rollei rilevata!"
        } else {
            "Dispositivo USB rilevato"
        }

        deviceText.text = buildString {
            appendLine("Produttore: ${device.manufacturerName ?: "Non disponibile"}")
            appendLine("Prodotto: ${device.productName ?: "Non disponibile"}")
            appendLine("VID: 0x${device.vendorId.toString(16).uppercase()}")
            appendLine("PID: 0x${device.productId.toString(16).uppercase()}")
        }

        protocolText.text = "Premi «Leggi informazioni USB»."

        logText.text = buildString {
            appendLine("Dispositivi USB rilevati: ${devices.size}")
            appendLine("Nome sistema: ${device.deviceName}")
            appendLine("Interfacce: ${device.interfaceCount}")
            appendLine("Permesso già disponibile: ${usbManager.hasPermission(device)}")
        }
    }

    private fun requestUsbInformation() {
        val device = selectedDevice ?: run {
            scanUsbDevices()
            return
        }

        if (usbManager.hasPermission(device)) {
            showUsbInformation(device)
            return
        }

        statusText.text = "Richiesta autorizzazione USB..."

        val permissionIntent = Intent(ACTION_USB_PERMISSION).apply {
            setPackage(packageName)
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 31) {
                    PendingIntent.FLAG_MUTABLE
                } else {
                    0
                }

        val pendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            permissionIntent,
            flags
        )

        usbManager.requestPermission(device, pendingIntent)
    }

    private fun showUsbInformation(device: UsbDevice) {
        val interfaces = (0 until device.interfaceCount).map {
            device.getInterface(it)
        }

        val isRollei =
            device.vendorId == ROLLEI_VENDOR_ID &&
                    device.productId == ROLLEI_PRODUCT_ID

        statusText.text = if (isRollei) {
            "Rollei riconosciuta e autorizzata"
        } else {
            "Dispositivo USB autorizzato"
        }

        protocolText.text = buildString {
            interfaces.forEachIndexed { index, usbInterface ->
                appendLine("Interfaccia $index")
                appendLine("Classe: ${usbInterface.interfaceClass}")
                appendLine("Sottoclasse: ${usbInterface.interfaceSubclass}")
                appendLine("Protocollo: ${usbInterface.interfaceProtocol}")
                appendLine("Endpoint: ${usbInterface.endpointCount}")

                for (endpointIndex in 0 until usbInterface.endpointCount) {
                    val endpoint = usbInterface.getEndpoint(endpointIndex)

                    val direction = when (endpoint.direction) {
                        android.hardware.usb.UsbConstants.USB_DIR_IN -> "IN"
                        android.hardware.usb.UsbConstants.USB_DIR_OUT -> "OUT"
                        else -> "Altro"
                    }

                    appendLine(
                        "  - 0x${endpoint.address.toString(16).uppercase()} " +
                                "$direction, tipo ${endpoint.type}, " +
                                "max packet ${endpoint.maxPacketSize}"
                    )
                }
                appendLine()
            }
        }

        logText.text = buildString {
            appendLine("Accesso USB autorizzato.")
            appendLine("Device ID: ${device.deviceId}")
            appendLine("USB version: ${device.version}")
            appendLine("Numero configurazioni: ${device.configurationCount}")
            appendLine("Interfacce: ${device.interfaceCount}")
            appendLine()
            appendLine("NOTA: non sono ancora stati inviati comandi SCSI.")
            appendLine("Questa versione legge soltanto i descrittori USB.")
        }

        infoButton.text = "Aggiorna informazioni USB"
    }

    private fun getUsbDeviceFromIntent(intent: Intent): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(
                UsbManager.EXTRA_DEVICE,
                UsbDevice::class.java
            )
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }

    private fun textView(text: String, size: Float): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = size
            setPadding(0, dp(5), 0, dp(5))
        }
    }

    private fun sectionTitle(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, dp(5), 0, dp(5))
        }
    }

    private fun spacer(height: Int): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                1,
                dp(height)
            )
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun testScsiCommunication(device: UsbDevice) {

        lifecycleScope.launch {
            scsiResultText.text = "Test SCSI in corso..."

            try {
                val result = withContext(Dispatchers.IO) {

                    val usbManager = getSystemService(USB_SERVICE)
                            as android.hardware.usb.UsbManager

                    if (!usbManager.hasPermission(device)) {
                        throw IOException("Permesso USB non disponibile")
                    }

                    val usbInterface = device.getInterface(0)

                    val bulkIn = (0 until usbInterface.endpointCount)
                        .map { usbInterface.getEndpoint(it) }
                        .firstOrNull {
                            it.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                                    it.direction == UsbConstants.USB_DIR_IN
                        } ?: throw IOException("Endpoint Bulk IN non trovato")

                    val bulkOut = (0 until usbInterface.endpointCount)
                        .map { usbInterface.getEndpoint(it) }
                        .firstOrNull {
                            it.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                                    it.direction == UsbConstants.USB_DIR_OUT
                        } ?: throw IOException("Endpoint Bulk OUT non trovato")

                    val connection = usbManager.openDevice(device)
                        ?: throw IOException("Impossibile aprire il dispositivo USB")

                    try {
                        if (!connection.claimInterface(usbInterface, true)) {
                            throw IOException("Impossibile acquisire l'interfaccia USB")
                        }

                        try {
                            val transport = MassStorageTransport(
                                connection = connection,
                                bulkIn = bulkIn,
                                bulkOut = bulkOut
                            )

                            val output = StringBuilder()

                            // INQUIRY
                            output.appendLine("=== SCSI INQUIRY ===")

                            val inquiry = transport.inquiry()

                            output.appendLine(
                                "Stato: ${inquiry.cswStatus}"
                            )
                            output.appendLine(
                                "Byte ricevuti: ${inquiry.data.size}"
                            )

                            if (inquiry.successful && inquiry.data.size >= 36) {
                                val vendor = String(
                                    inquiry.data,
                                    8,
                                    8,
                                    Charsets.US_ASCII
                                ).trim()

                                val product = String(
                                    inquiry.data,
                                    16,
                                    16,
                                    Charsets.US_ASCII
                                ).trim()

                                output.appendLine("Vendor: $vendor")
                                output.appendLine("Product: $product")
                            }

                            // TEST UNIT READY
                            output.appendLine()
                            output.appendLine("=== TEST UNIT READY ===")

                            val ready = transport.testUnitReady()

                            output.appendLine(
                                "Stato: ${ready.cswStatus}"
                            )
                            output.appendLine(
                                if (ready.successful) {
                                    "Unità pronta"
                                } else {
                                    "Unità non pronta o comando rifiutato"
                                }
                            )

                            // READ CAPACITY
                            output.appendLine()
                            output.appendLine("=== READ CAPACITY (10) ===")

                            val capacity = transport.readCapacity10()

                            output.appendLine(
                                "Stato: ${capacity.cswStatus}"
                            )
                            output.appendLine(
                                "Byte ricevuti: ${capacity.data.size}"
                            )

                            if (capacity.successful && capacity.data.size >= 8) {

                                val lastLba = readUInt32BE(capacity.data, 0)
                                val blockSize = readUInt32BE(capacity.data, 4)

                                val totalBytes = (lastLba + 1) * blockSize

                                output.appendLine("Ultimo LBA: $lastLba")
                                output.appendLine("Dimensione settore: $blockSize byte")
                                output.appendLine("Capacità: $totalBytes byte")
                                output.appendLine(
                                    "Capacità: %.2f GB".format(
                                        totalBytes / 1_000_000_000.0
                                    )
                                )
                            }

                            output.toString()

                        } finally {
                            connection.releaseInterface(usbInterface)
                        }

                    } finally {
                        connection.close()
                    }
                }
                // Il risultato viene mostrato sul thread principale.
                showScsiResult(result)
            } catch (e: Exception) {

                scsiResultText.text =
                    "ERRORE DURANTE IL TEST SCSI\n\n" + (e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun readUInt32BE(
        data: ByteArray,
        offset: Int
    ): Long {
        return ((data[offset].toLong() and 0xFF) shl 24) or
                ((data[offset + 1].toLong() and 0xFF) shl 16) or
                ((data[offset + 2].toLong() and 0xFF) shl 8) or
                (data[offset + 3].toLong() and 0xFF)
    }

    private lateinit var scsiResultText: TextView

    private fun addScsiTestButton(container: LinearLayout) {



        val button = Button(this).apply {
            text = "Test comunicazione SCSI"
            setOnClickListener {
                val device = selectedDevice

                if (device == null) {
                    Toast.makeText(
                        this@FirstFragment,
                        "Prima scansiona la fotocamera USB",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    testScsiCommunication(device)
                }
            }
        }

        scsiResultText = TextView(this).apply {
            text = "In attesa del test SCSI..."
            textSize = 14f
            setPadding(16, 16, 16, 16)
        }

        val button2 = Button(this).apply {
            text = "Leggi MBR / FAT32"

            setOnClickListener {
                selectedDevice?.let {
                    testMbrCommunication(it)
                } ?: run {
                    scsiResultText.text = "Prima collega e seleziona la fotocamera"
                }
            }
        }

        container.addView(button)
        container.addView(button2)
        container.addView(scsiResultText)
    }

    private fun showScsiResult(result: String) {
        scsiResultText.text = result
    }

    private fun testMbrCommunication(device: UsbDevice) {

        lifecycleScope.launch {

            scsiResultText.text = "Lettura MBR in corso..."

            try {

                val result = withContext(Dispatchers.IO) {

                    val usbManager = getSystemService(USB_SERVICE)
                            as android.hardware.usb.UsbManager

                    if (!usbManager.hasPermission(device)) {
                        throw IOException("Permesso USB non disponibile")
                    }

                    val usbInterface = device.getInterface(0)

                    val bulkIn = (0 until usbInterface.endpointCount)
                        .map { usbInterface.getEndpoint(it) }
                        .first {
                            it.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                                    it.direction == UsbConstants.USB_DIR_IN
                        }

                    val bulkOut = (0 until usbInterface.endpointCount)
                        .map { usbInterface.getEndpoint(it) }
                        .first {
                            it.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                                    it.direction == UsbConstants.USB_DIR_OUT
                        }

                    val connection = usbManager.openDevice(device)
                        ?: throw IOException("Impossibile aprire la fotocamera")

                    try {

                        if (!connection.claimInterface(usbInterface, true)) {
                            throw IOException("Impossibile acquisire interfaccia USB")
                        }

                        try {

                            val transport = MassStorageTransport(
                                connection,
                                bulkIn,
                                bulkOut
                            )

                            val output = StringBuilder()

                            // Lettura del settore 0: MBR
                            val mbrResult = transport.readSectors(
                                lba = 0,
                                sectorCount = 1
                            )

                            if (!mbrResult.successful) {
                                throw IOException(
                                    "READ(10) MBR fallito: stato ${mbrResult.cswStatus}"
                                )
                            }

                            if (mbrResult.data.size != 512) {
                                throw IOException(
                                    "Settore MBR incompleto: ${mbrResult.data.size} byte"
                                )
                            }

                            output.appendLine("=== MBR ===")
                            output.appendLine("LBA letto: 0")
                            output.appendLine("Byte ricevuti: ${mbrResult.data.size}")

                            val partitions = MbrParser.parse(mbrResult.data)

                            output.appendLine("Partizioni trovate: ${partitions.size}")

                            partitions.forEach { partition ->

                                output.appendLine()
                                output.appendLine("PARTIZIONE ${partition.index}")
                                output.appendLine(
                                    "Tipo: 0x${partition.type.toString(16).uppercase()}"
                                )
                                output.appendLine("LBA iniziale: ${partition.startLba}")
                                output.appendLine("Settori: ${partition.sectorCount}")

                                val sizeBytes = partition.sectorCount * 512L

                                output.appendLine(
                                    "Dimensione: %.2f GB".format(
                                        sizeBytes / 1_000_000_000.0
                                    )
                                )

                                // Leggiamo il boot sector della partizione.
                                output.appendLine()
                                output.appendLine("=== FAT32 BOOT SECTOR ===")

                                val bootResult = transport.readSectors(
                                    lba = partition.startLba,
                                    sectorCount = 1
                                )

                                if (!bootResult.successful ||
                                    bootResult.data.size != 512
                                ) {
                                    output.appendLine(
                                        "Lettura boot sector non riuscita"
                                    )
                                } else {
                                    output.appendLine(
                                        MbrParser.parseFat32BootSector(
                                            bootResult.data
                                        )
                                    )
                                }
                            }

                            output.toString()

                        } finally {
                            connection.releaseInterface(usbInterface)
                        }

                    } finally {
                        connection.close()
                    }
                }

                scsiResultText.text = result

            } catch (e: Exception) {

                scsiResultText.text =
                    "ERRORE LETTURA MBR\n\n" +
                            (e.message ?: e.javaClass.simpleName)
            }
        }
    }


    private fun addPhotoBrowser(container: LinearLayout) {

        val title = TextView(this).apply {
            text = "FOTOGRAFIE ROLLEI"
            textSize = 20f
            setPadding(16, 24, 16, 12)
        }

        val loadButton = Button(this).apply {
            text = "Cerca fotografie nella fotocamera"

            setOnClickListener {
                selectedDevice?.let {
                    loadPhotos(it)
                } ?: run {
                    scsiResultText.text = "Collega prima la fotocamera"
                }
            }
        }

        photoPreview = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = ImageView.GONE

            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        photoListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        container.addView(title)
        container.addView(loadButton)
        container.addView(photoPreview)
        container.addView(photoListContainer)
    }

    private fun loadPhotos(device: UsbDevice) {

        lifecycleScope.launch {

            scsiResultText.text = "Ricerca delle fotografie..."

            try {
                val files = withContext(Dispatchers.IO) {

                    val usbManager = getSystemService(USB_SERVICE)
                            as android.hardware.usb.UsbManager

                    if (!usbManager.hasPermission(device)) {
                        throw IOException("Permesso USB non disponibile")
                    }

                    val usbInterface = device.getInterface(0)

                    val bulkIn = (0 until usbInterface.endpointCount)
                        .map { usbInterface.getEndpoint(it) }
                        .first {
                            it.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                                    it.direction == UsbConstants.USB_DIR_IN
                        }

                    val bulkOut = (0 until usbInterface.endpointCount)
                        .map { usbInterface.getEndpoint(it) }
                        .first {
                            it.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                                    it.direction == UsbConstants.USB_DIR_OUT
                        }

                    val connection = usbManager.openDevice(device)
                        ?: throw IOException("Impossibile aprire la Rollei")

                    try {

                        if (!connection.claimInterface(usbInterface, true)) {
                            throw IOException("Interfaccia USB non disponibile")
                        }

                        try {

                            val transport = MassStorageTransport(
                                connection,
                                bulkIn,
                                bulkOut
                            )

                            // Individua la partizione leggendo l'MBR.
                            val mbr = transport.readSectors(0, 1)

                            if (!mbr.successful || mbr.data.size != 512) {
                                throw IOException("Impossibile leggere MBR")
                            }

                            val partition = MbrParser.parse(mbr.data)
                                .firstOrNull()
                                ?: throw IOException("Nessuna partizione trovata")

                            val reader = Fat32Reader(
                                transport,
                                partition.startLba
                            )

                            reader.listPhotos("PHOTO")

                        } finally {
                            connection.releaseInterface(usbInterface)
                        }

                    } finally {
                        connection.close()
                    }
                }

                displayPhotoList(device, files)

                scsiResultText.text =
                    "Ricerca completata. Fotografie trovate: ${files.size}"

            } catch (e: Exception) {

                scsiResultText.text =
                    "ERRORE LETTURA FOTOGRAFIE\n\n" +
                            (e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun displayPhotoList(
        device: UsbDevice,
        files: List<Fat32File>
    ) {

        photoListContainer.removeAllViews()
        photoPreview.visibility = ImageView.GONE

        if (files.isEmpty()) {
            photoListContainer.addView(
                TextView(this).apply {
                    text = "Nessuna fotografia JPEG trovata nella cartella PHOTO."
                    setPadding(16, 16, 16, 16)
                }
            )
            return
        }

        files.forEach { file ->

            val button = Button(this).apply {

                val sizeKb = file.size / 1024

                text = "${file.name}  •  ${sizeKb} KB"

                setOnClickListener {
                    displayPhoto(device, file)
                }
            }

            photoListContainer.addView(button)
        }
    }

    private fun displayPhoto(
        device: UsbDevice,
        file: Fat32File
    ) {

        lifecycleScope.launch {

            scsiResultText.text = "Caricamento ${file.name}..."

            try {

                val imageBytes = withContext(Dispatchers.IO) {

                    val usbManager = getSystemService(USB_SERVICE)
                            as android.hardware.usb.UsbManager

                    if (!usbManager.hasPermission(device)) {
                        throw IOException("Permesso USB non disponibile")
                    }

                    val usbInterface = device.getInterface(0)

                    val bulkIn = (0 until usbInterface.endpointCount)
                        .map { usbInterface.getEndpoint(it) }
                        .first {
                            it.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                                    it.direction == UsbConstants.USB_DIR_IN
                        }

                    val bulkOut = (0 until usbInterface.endpointCount)
                        .map { usbInterface.getEndpoint(it) }
                        .first {
                            it.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                                    it.direction == UsbConstants.USB_DIR_OUT
                        }

                    val connection = usbManager.openDevice(device)
                        ?: throw IOException("Impossibile aprire la Rollei")

                    try {

                        if (!connection.claimInterface(usbInterface, true)) {
                            throw IOException("Interfaccia USB non disponibile")
                        }

                        try {

                            val transport = MassStorageTransport(
                                connection,
                                bulkIn,
                                bulkOut
                            )

                            val mbr = transport.readSectors(0, 1)

                            if (!mbr.successful || mbr.data.size != 512) {
                                throw IOException("Impossibile leggere MBR")
                            }

                            val partition = MbrParser.parse(mbr.data)
                                .firstOrNull()
                                ?: throw IOException("Partizione non trovata")

                            val reader = Fat32Reader(
                                transport,
                                partition.startLba
                            )

                            reader.readFile(file)

                        } finally {
                            connection.releaseInterface(usbInterface)
                        }

                    } finally {
                        connection.close()
                    }
                }

                val bitmap = BitmapFactory.decodeByteArray(
                    imageBytes,
                    0,
                    imageBytes.size
                ) ?: throw IOException("Impossibile decodificare JPEG")

                photoPreview.setImageBitmap(bitmap)
                photoPreview.visibility = ImageView.VISIBLE

                scsiResultText.text =
                    "${file.name} • ${file.size / 1024} KB"

            } catch (e: Exception) {

                scsiResultText.text =
                    "ERRORE APERTURA FOTO\n\n" +
                            (e.message ?: e.javaClass.simpleName)
            }
        }
    }
}