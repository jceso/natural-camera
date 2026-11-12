package com.example.naturalcam

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.naturalcam.usb.Fat32File
import com.example.naturalcam.usb.Fat32Reader
import com.example.naturalcam.usb.MassStorageTransport
import com.example.naturalcam.usb.MbrParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

import android.app.Dialog
import android.view.MotionEvent
import android.view.GestureDetector
import android.widget.FrameLayout
import android.widget.VideoView
import android.widget.MediaController
import android.graphics.Bitmap
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        private const val ACTION_USB_PERMISSION = "com.example.naturalcam.USB_PERMISSION"

        private const val ROLLEI_VENDOR_ID = 0x1B3F
        private const val ROLLEI_PRODUCT_ID = 0x8301

        private val BG_COLOR = Color.rgb(18, 20, 25)
        private val CARD_COLOR = Color.rgb(29, 32, 39)
        private val CARD_SECONDARY = Color.rgb(39, 43, 52)
        private val TEXT_PRIMARY = Color.rgb(240, 242, 246)
        private val TEXT_SECONDARY = Color.rgb(155, 162, 175)
        private val ACCENT_COLOR = Color.rgb(115, 160, 220)
        private val SUCCESS_COLOR = Color.rgb(85, 190, 135)
        private val FAILURE_COLOR = Color.rgb(184, 44, 44)
    }

    private lateinit var usbManager: UsbManager

    private lateinit var statusText: TextView
    private lateinit var statusDescription: TextView
    private lateinit var searchButton: Button
    private lateinit var messageText: TextView
    private lateinit var mediaListContainer: LinearLayout

    private var selectedDevice: UsbDevice? = null
    private var receiverRegistered = false
    private var isLoading = false

    private var mediaFiles: List<Fat32File> = emptyList()

    private var autoScanDeviceId: Int? = null
    private var permissionRequestedDeviceId: Int? = null

    private var viewerDialog: Dialog? = null
    private var viewerPosition = 0
    private var viewerImage: ImageView? = null
    private var viewerVideo: VideoView? = null
    private var viewerInfo: TextView? = null

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION)
                return

            val device = getUsbDeviceFromIntent(intent)
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)

            if (granted && device != null) {
                selectedDevice = device
                permissionRequestedDeviceId = null
                autoScanDeviceId = device.deviceId
                updateConnectionStatus(true)
                statusDescription.text = "Fotocamera autorizzata. Ricerca dei contenuti..."

                loadMedia(device)
            } else {
                permissionRequestedDeviceId = null
                statusDescription.text = "Autorizzazione USB negata. Premi AGGIORNA GALLERIA per riprovare."

                updateMessage("Accesso alla fotocamera non autorizzato.")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        createInterface()
        scanUsbDevices()
    }

    override fun onStart() {
        super.onStart()

        val filter = IntentFilter(ACTION_USB_PERMISSION)

        ContextCompat.registerReceiver(this, usbPermissionReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(usbPermissionReceiver)
            receiverRegistered = false
        }

        super.onStop()
    }

    // ---------------------------------------------------------
    // INTERFACCIA
    // ---------------------------------------------------------

    private fun createInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(32), dp(20), dp(32))
            setBackgroundColor(BG_COLOR)
        }

        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }

        // Intestazione
        val title = TextView(this).apply {
            text = "Natural Camera"
            textSize = 30f
            setTextColor(TEXT_PRIMARY)
            setTypeface(null, Typeface.BOLD)
        }
        root.addView(title)

        val subtitle = TextView(this).apply {
            text = "La tua fotocamera, senza complicazioni."
            textSize = 14f
            setTextColor(TEXT_SECONDARY)
        }
        root.addView(subtitle)
        root.addView(spacer(30))

        // Diagnostica USB
        root.addView(sectionTitle("DIAGNOSTICA CONNESSIONE USB"))
        root.addView(spacer(12))

        val connectionCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = roundedBackground(CARD_SECONDARY, dp(18))
            elevation = dp(2).toFloat()
        }

        val connectionHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val connectionIndicator = TextView(this).apply {
            text = "●"
            textSize = 18f
            setTextColor(Color.rgb(170, 175, 185))
        }

        statusText = TextView(this).apply {
            text = "In attesa di connessione"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(TEXT_SECONDARY)
            setPadding(dp(10), 0, 0, 0)
        }

        connectionHeader.addView(connectionIndicator)
        connectionHeader.addView(statusText)

        connectionCard.addView(connectionHeader)

        statusDescription = TextView(this).apply {
            text = "Collega la Rollei tramite USB OTG."
            textSize = 14f
            setTextColor(Color.rgb(115, 121, 132))
            setPadding(0, dp(8), 0, 0)
        }

        connectionCard.addView(statusDescription)

        root.addView(connectionCard)
        root.addView(spacer(26))

        // Pulsante ricerca

        searchButton = Button(this).apply {
            text = "AGGIORNA GALLERIA"
            textSize = 14f
            isAllCaps = false
            background = roundedBackground(ACCENT_COLOR, dp(14))
            setTextColor(Color.BLACK)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isEnabled = false

            setOnClickListener {
                val device = selectedDevice

                if (device == null) {
                    scanUsbDevices()
                    return@setOnClickListener
                }

                if (usbManager.hasPermission(device)) {
                    loadMedia(device)
                } else {
                    requestUsbPermission(device)
                }
            }
        }

        root.addView(
            searchButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(58)
            )
        )

        root.addView(spacer(30))

        // Galleria

        val galleryHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val galleryTitle = TextView(this).apply {
            text = "I tuoi contenuti"
            textSize = 21f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.rgb(30, 35, 44))
        }

        galleryHeader.addView(
            galleryTitle,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        root.addView(galleryHeader)
        root.addView(spacer(5))

        messageText = TextView(this).apply {
            text = "Collega la fotocamera e cerca i tuoi contenuti."
            textSize = 13f
            setTextColor(Color.rgb(125, 131, 141))
        }

        root.addView(messageText)
        root.addView(spacer(16))

        mediaListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        root.addView(mediaListContainer)

        setContentView(scrollView)
    }

    // ---------------------------------------------------------
    // CONNESSIONE USB
    // ---------------------------------------------------------

    private fun scanUsbDevices() {

        val devices = usbManager.deviceList.values.toList()

        val rollei = devices.firstOrNull {
            it.vendorId == ROLLEI_VENDOR_ID &&
                    it.productId == ROLLEI_PRODUCT_ID
        }

        selectedDevice = rollei

        if (rollei == null) {
            updateConnectionStatus(false)
            searchButton.isEnabled = false

            autoScanDeviceId = null
            permissionRequestedDeviceId = null

            return
        }

        updateConnectionStatus(true)
        searchButton.isEnabled = true

        val deviceId = rollei.deviceId

        if (usbManager.hasPermission(rollei)) {

            statusDescription.text =
                "Fotocamera collegata. Preparazione della galleria..."

            // Evita di avviare la stessa scansione più volte.
            if (autoScanDeviceId != deviceId) {
                autoScanDeviceId = deviceId
                loadMedia(rollei)
            }

        } else {

            statusDescription.text =
                "Richiesta autorizzazione per accedere alla fotocamera..."

            // Chiediamo il permesso automaticamente una sola volta.
            if (permissionRequestedDeviceId != deviceId) {
                permissionRequestedDeviceId = deviceId
                requestUsbPermission(rollei)
            }
        }
    }

    private fun updateConnectionStatus(connected: Boolean) {
        if (connected) {
            statusText.text = "Rollei rilevata!"
            statusDescription.text = "La fotocamera è collegata tramite USB OTG."
            statusText.setTextColor(SUCCESS_COLOR)
        } else {
            statusText.text = "Nessuna fotocamera rilevata"
            statusDescription.text = "Collega la Rollei tramite USB OTG."
            statusText.setTextColor(FAILURE_COLOR)
        }
    }

    private fun requestUsbPermission(device: UsbDevice) {

        updateMessage("In attesa dell'autorizzazione USB...")

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

    // ---------------------------------------------------------
    // LETTURA MEMORIA FOTOCAMERA
    // ---------------------------------------------------------

    private fun loadMedia(device: UsbDevice) {
        if (isLoading)
            return

        isLoading = true
        searchButton.isEnabled = false

        updateMessage("Lettura della memoria della fotocamera...")
        mediaListContainer.removeAllViews()

        lifecycleScope.launch {
            try {
                val files = withContext(Dispatchers.IO) {

                    if (!usbManager.hasPermission(device))
                        throw IOException("Permesso USB non disponibile")
                    if (device.interfaceCount == 0)
                        throw IOException("Interfaccia USB non trovata")

                    val usbInterface = device.getInterface(0)

                    val endpoints = (0 until usbInterface.endpointCount)
                        .map { usbInterface.getEndpoint(it) }

                    val bulkIn = endpoints.firstOrNull {
                        it.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                                it.direction == UsbConstants.USB_DIR_IN
                    } ?: throw IOException("Endpoint Bulk IN non trovato")

                    val bulkOut = endpoints.firstOrNull {
                        it.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                                it.direction == UsbConstants.USB_DIR_OUT
                    } ?: throw IOException("Endpoint Bulk OUT non trovato")

                    val connection = usbManager.openDevice(device)
                        ?: throw IOException("Impossibile aprire la Rollei")

                    try {
                        if (!connection.claimInterface(usbInterface, true))
                            throw IOException("Interfaccia USB non disponibile")

                        try {
                            val transport = MassStorageTransport(
                                connection,
                                bulkIn,
                                bulkOut
                            )

                            // Lettura MBR, esclusivamente in lettura.
                            val mbr = transport.readSectors(0, 1)

                            if (!mbr.successful || mbr.data.size != 512)
                                throw IOException("Impossibile leggere MBR")

                            val partition = MbrParser.parse(mbr.data)
                                .firstOrNull()
                                ?: throw IOException("Partizione non trovata")

                            val reader = Fat32Reader(
                                transport,
                                partition.startLba
                            )

                            // Cerca immagini e video in tutte le cartelle.
                            reader.listMedia()

                        } finally {
                            connection.releaseInterface(usbInterface)
                        }

                    } finally {
                        connection.close()
                    }
                }

                displayMediaList(device, files)
                mediaFiles = files

                val imageCount = files.count { it.isImage }
                val videoCount = files.count { it.isVideo }

                updateMessage(
                    "${files.size} contenuti trovati · " +
                            "$imageCount foto · $videoCount video"
                )

            } catch (e: Exception) {

                updateMessage(
                    "Errore durante la lettura: " +
                            (e.message ?: e.javaClass.simpleName)
                )

                Toast.makeText(
                    this@MainActivity,
                    "Impossibile leggere la memoria della fotocamera",
                    Toast.LENGTH_LONG
                ).show()

            } finally {
                isLoading = false
                searchButton.isEnabled = selectedDevice != null
            }
        }
    }

    // ---------------------------------------------------------
    // GALLERIA
    // ---------------------------------------------------------

    private fun displayMediaList(device: UsbDevice, files: List<Fat32File>) {
        mediaListContainer.removeAllViews()
        mediaFiles = files

        if (files.isEmpty()) {
            mediaListContainer.addView(
                TextView(this).apply {
                    text = "Nessuna fotografia o video trovato."
                    textSize = 14f
                    setTextColor(TEXT_SECONDARY)
                    gravity = Gravity.CENTER
                    setPadding(dp(16), dp(30), dp(16), dp(30))
                }
            )
            return
        }

        files.forEachIndexed { index, file ->
            val card = createMediaCard(file)

            card.setOnClickListener {
                openMediaViewer(device, index)
            }

//            card.setOnClickListener {
//
//                if (file.isImage)
//                    displayImage(device, file)
//                else if (file.isVideo)
//                    Toast.makeText(this, "Video selezionato: ${file.name}", Toast.LENGTH_SHORT).show()
//            }

            mediaListContainer.addView(
                card,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(12) }
            )
        }
    }

    private fun createMediaCard(file: Fat32File): View {

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = roundedBackground(CARD_COLOR, dp(16))
            elevation = dp(1).toFloat()
            isClickable = true
            isFocusable = true
        }

        val thumbnail = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = roundedBackground(
                if (file.isVideo) {
                    Color.rgb(235, 239, 247)
                } else {
                    Color.rgb(240, 242, 245)
                },
                dp(12)
            )

            layoutParams = LinearLayout.LayoutParams(
                dp(66),
                dp(66)
            )
        }

        val icon = TextView(this).apply {
            text = if (file.isVideo) "▶" else "▧"
            textSize = 25f
            setTextColor(
                if (file.isVideo) {
                    Color.rgb(75, 105, 160)
                } else {
                    Color.rgb(95, 105, 120)
                }
            )
        }

        thumbnail.addView(icon)

        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), 0, 0, 0)
        }

        val name = TextView(this).apply {
            text = file.name
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.rgb(35, 40, 48))
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        val type = TextView(this).apply {
            text = if (file.isVideo) "VIDEO" else "FOTOGRAFIA"
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            setTextColor(
                if (file.isVideo) {
                    Color.rgb(75, 105, 160)
                } else {
                    Color.rgb(45, 135, 95)
                }
            )
            setPadding(0, dp(4), 0, dp(2))
        }

        val path = TextView(this).apply {
            text = file.relativePath
            textSize = 11f
            setTextColor(Color.rgb(135, 140, 150))
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }

        val size = TextView(this).apply {
            text = formatFileSize(file.size)
            textSize = 11f
            setTextColor(Color.rgb(110, 116, 126))
            setPadding(0, dp(4), 0, 0)
        }

        details.addView(name)
        details.addView(type)
        details.addView(path)
        details.addView(size)

        card.addView(thumbnail)

        card.addView(
            details,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        return card
    }

    // ---------------------------------------------------------
    // ANTEPRIMA FOTOGRAFIE
    // ---------------------------------------------------------

    private fun displayImage(
        device: UsbDevice,
        file: Fat32File
    ) {

        updateMessage("Caricamento ${file.name}...")

        lifecycleScope.launch {

            try {

                val imageBytes = withContext(Dispatchers.IO) {

                    if (!usbManager.hasPermission(device)) {
                        throw IOException("Permesso USB non disponibile")
                    }

                    val usbInterface = device.getInterface(0)

                    val endpoints = (0 until usbInterface.endpointCount)
                        .map { usbInterface.getEndpoint(it) }

                    val bulkIn = endpoints.firstOrNull {
                        it.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                                it.direction == UsbConstants.USB_DIR_IN
                    } ?: throw IOException("Endpoint Bulk IN non trovato")

                    val bulkOut = endpoints.firstOrNull {
                        it.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                                it.direction == UsbConstants.USB_DIR_OUT
                    } ?: throw IOException("Endpoint Bulk OUT non trovato")

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
                ) ?: throw IOException("Formato immagine non supportato")

                showImageDialog(file, bitmap)

                updateMessage("Fotografia aperta: ${file.name}")

            } catch (e: Exception) {

                updateMessage(
                    "Errore apertura fotografia: " +
                            (e.message ?: e.javaClass.simpleName)
                )
            }
        }
    }

    private fun showImageDialog(
        file: Fat32File,
        bitmap: android.graphics.Bitmap
    ) {

        val imageView = ImageView(this).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(
                imageView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(420)
                )
            )
        }

        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setView(container)
            .setPositiveButton("Chiudi", null)
            .show()
    }

    // ---------------------------------------------------------
    // UTILITÀ UI
    // ---------------------------------------------------------

    private fun sectionTitle(text: String): TextView {

        return TextView(this).apply {
            this.text = text
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.rgb(135, 141, 152))
            letterSpacing = 0.08f
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

    private fun roundedBackground(
        color: Int,
        radius: Int
    ): GradientDrawable {

        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
        }
    }

    private fun formatFileSize(size: Long): String {

        return when {
            size >= 1024L * 1024L ->
                "%.2f MB".format(size / (1024.0 * 1024.0))

            size >= 1024L ->
                "%.1f KB".format(size / 1024.0)

            else -> "$size byte"
        }
    }

    private fun updateMessage(message: String) {
        messageText.text = message
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }


    private fun openMediaViewer(
        device: UsbDevice,
        position: Int
    ) {

        if (mediaFiles.isEmpty()) return

        viewerPosition = position

        val dialog = Dialog(this)

        val container = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
        }

        val video = VideoView(this)

        val info = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(dp(18), dp(14), dp(18), dp(14))
            setBackgroundColor(Color.argb(170, 0, 0, 0))
        }

        container.addView(
            image,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        container.addView(
            video,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val infoParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        )

        container.addView(info, infoParams)

        viewerDialog = dialog
        viewerImage = image
        viewerVideo = video
        viewerInfo = info

        dialog.setContentView(container)
        dialog.window?.setBackgroundDrawableResource(android.R.color.black)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        dialog.show()

        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onFling(e1: MotionEvent?,
                                     e2: MotionEvent,
                                     velocityX: Float,
                                     velocityY: Float): Boolean {
                    val start = e1 ?: return false

                    val deltaX = e2.x - start.x
                    val deltaY = e2.y - start.y

                    if (kotlin.math.abs(deltaX) > dp(70) &&
                        kotlin.math.abs(deltaX) > kotlin.math.abs(deltaY) &&
                        kotlin.math.abs(velocityX) > dp(100)) {
                            if (deltaX < 0)
                                showViewerItem(device, viewerPosition + 1)
                            else
                                showViewerItem(device, viewerPosition - 1)

                            return true
                    }

                    return false
                }
        })

        // Lo swipe funziona sia sulle foto che sul video.
        container.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }

        showViewerItem(device, position)
    }

    private fun showViewerItem(device: UsbDevice, position: Int) {
        if (position !in mediaFiles.indices)
            return
        viewerPosition = position
        val file = mediaFiles[position]

        viewerVideo?.stopPlayback()
        viewerVideo?.visibility = View.GONE

        viewerImage?.visibility = View.GONE
        viewerImage?.setImageDrawable(null)

        viewerInfo?.text = "${file.name}\n${file.relativePath}\n" +
                           "${position + 1} / ${mediaFiles.size}"

        lifecycleScope.launch {

            try {
                val bytes = withContext(Dispatchers.IO) { readMediaFile(device, file) }

                if (file.isImage) {
                    val bitmap = decodeViewerBitmap(bytes)

                    viewerImage?.setImageBitmap(bitmap)
                    viewerImage?.visibility = View.VISIBLE
                } else if (file.isVideo) {
                    val videoFile = withContext(Dispatchers.IO) {
                        val cacheFile = File(cacheDir, "viewer_${position}.${file.extension}")

                        cacheFile.writeBytes(bytes)
                        cacheFile
                    }

                    viewerVideo?.apply {
                        visibility = View.VISIBLE

                        setMediaController(MediaController(this@MainActivity).apply { setAnchorView(this@apply) })
                        setVideoPath(videoFile.absolutePath)
                        start()
                    }
                }

            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Impossibile aprire ${file.name}: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }


    private fun readMediaFile(device: UsbDevice, file: Fat32File): ByteArray {
        if (!usbManager.hasPermission(device))
            throw IOException("Permesso USB non disponibile")

        val usbInterface = device.getInterface(0)
        val endpoints = (0 until usbInterface.endpointCount).map { usbInterface.getEndpoint(it) }

        val bulkIn = endpoints.firstOrNull {
            it.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
            it.direction == UsbConstants.USB_DIR_IN
        } ?: throw IOException("Endpoint Bulk IN non trovato")

        val bulkOut = endpoints.firstOrNull {
            it.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
            it.direction == UsbConstants.USB_DIR_OUT
        } ?: throw IOException("Endpoint Bulk OUT non trovato")

        val connection = usbManager.openDevice(device) ?: throw IOException("Impossibile aprire la Rollei")

        try {
            if (!connection.claimInterface(usbInterface, true))
                throw IOException("Interfaccia USB non disponibile")

            try {
                val transport = MassStorageTransport(connection, bulkIn, bulkOut)
                val mbr = transport.readSectors(0, 1)

                if (!mbr.successful || mbr.data.size != 512)
                    throw IOException("Impossibile leggere MBR")

                val partition = MbrParser.parse(mbr.data).firstOrNull() ?: throw IOException("Partizione non trovata")
                val reader = Fat32Reader(transport, partition.startLba)

                return reader.readFile(file)

            } finally {
                connection.releaseInterface(usbInterface)
            }

        } finally {
            connection.close()
        }
    }


    private fun decodeViewerBitmap(bytes: ByteArray): Bitmap {

        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }

        BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            bounds
        )

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateSampleSize(
                bounds.outWidth,
                bounds.outHeight,
                1800,
                1800
            )
        }

        return BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            options
        ) ?: throw IOException("Impossibile decodificare la fotografia")
    }


    private fun calculateSampleSize(
        width: Int,
        height: Int,
        maxWidth: Int,
        maxHeight: Int
    ): Int {

        var sampleSize = 1

        while (
            width / (sampleSize * 2) >= maxWidth &&
            height / (sampleSize * 2) >= maxHeight
        ) {
            sampleSize *= 2
        }

        return sampleSize
    }

    private fun closeMediaViewer() {
        viewerVideo?.stopPlayback()
        viewerDialog?.dismiss()

        viewerDialog = null
        viewerImage = null
        viewerVideo = null
        viewerInfo = null
    }
}