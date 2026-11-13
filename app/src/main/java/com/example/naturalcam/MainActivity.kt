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
import androidx.core.graphics.ColorUtils
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        private const val ACTION_USB_PERMISSION = "com.example.naturalcam.USB_PERMISSION"

        private const val ROLLEI_VENDOR_ID = 0x1B3F
        private const val ROLLEI_PRODUCT_ID = 0x8301

        private val BG_COLOR = Color.rgb(11, 13, 20)
        private val CARD_COLOR = Color.rgb(21, 25, 37)
        private val CARD_SECONDARY = Color.rgb(27, 33, 48)

        private val TEXT_PRIMARY = Color.rgb(238, 235, 222)
        private val TEXT_SECONDARY = Color.rgb(158, 169, 181)
        private val TEXT_TERTIARY = Color.rgb(112, 128, 145)

        //private val ACCENT_COLOR = Color.rgb(69, 243, 224)    // ciano neon
        //private val ACCENT_COLOR = Color.rgb(95, 231, 255)    //Prima versione
        private val ACCENT_COLOR = Color.rgb(90, 210, 230)    //Vintage display
        //private val ACCENT_COLOR = Color.rgb(108, 220, 255)
        private val SUCCESS_COLOR = Color.rgb(69, 243, 170)
        private val FAILURE_COLOR = Color.rgb(255, 79, 163) // magenta neon

        private val AMBER_COLOR = Color.rgb(246, 193, 119)
        private val MUTED_CYAN = Color.rgb(133, 195, 190)
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
    private var isGridView = true
    private var thumbnailBitmaps: Map<String, Bitmap> = emptyMap()
    private lateinit var listModeButton: TextView
    private lateinit var gridModeButton: TextView

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
            setPadding(dp(20), dp(48), dp(20), dp(32))
            setBackgroundColor(BG_COLOR)
        }
        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }

        // Intestazione
        val title = TextView(this).apply {
            text = "Natural Camera"
            textSize = 29f
            setTextColor(TEXT_PRIMARY)
            setTypeface(null, Typeface.BOLD)
            letterSpacing = 0.04f
        }
        root.addView(title)

        val subtitle = TextView(this).apply {
            text = "La tua fotocamera, senza complicazioni."
            textSize = 14f
            setTextColor(MUTED_CYAN)
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
            textSize = 16f
            setTextColor(ACCENT_COLOR)
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
            setTextColor(TEXT_TERTIARY)
            setPadding(0, dp(8), 0, 0)
        }
        connectionCard.addView(statusDescription)
        root.addView(connectionCard)
        root.addView(spacer(16))

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

                if (usbManager.hasPermission(device))
                    loadMedia(device)
                else
                    requestUsbPermission(device)
            }
        }
        root.addView(
            searchButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58))
        )
        root.addView(spacer(28))

        // ---------------------------------------------------------
        // GALLERIA
        // ---------------------------------------------------------
        messageText = TextView(this).apply {
            text = "Collega la fotocamera e cerca i tuoi contenuti."
            textSize = 11f
            setTextColor(TEXT_SECONDARY)
        }
        root.addView(messageText)

        val galleryHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val galleryTitle = TextView(this).apply {
            text = "Contenuti recenti"
            textSize = 23f
            setTypeface(null, Typeface.BOLD)
            setTextColor(TEXT_PRIMARY)
        }
        galleryHeader.addView(
            galleryTitle,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        // Pulsante modalità lista/griglia
        listModeButton = createModeButton("☷") {
            isGridView = false
            updateGalleryModeButtons()

            selectedDevice?.let { displayMediaList(it, mediaFiles) }
        }
        gridModeButton = createModeButton("▦") {
            isGridView = true
            updateGalleryModeButtons()

            selectedDevice?.let { displayMediaList(it, mediaFiles) }
        }
        galleryHeader.addView(listModeButton)
        galleryHeader.addView(spacer(8))
        galleryHeader.addView(gridModeButton)

        root.addView(galleryHeader)
        updateGalleryModeButtons()
        root.addView(spacer(16))

        mediaListContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
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
                val scanResult = withContext(Dispatchers.IO) {
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

                            // Per il momento manteniamo solamente le fotografie.
                            val images = reader.listMedia()
                                .filter { it.isImage }
                                .sortedBy { it.relativePath.lowercase() }

                            // Generazione delle miniature durante la connessione USB.
                            // Limitiamo le miniature iniziali per contenere i tempi di scansione.
                            val thumbnails = LinkedHashMap<String, Bitmap>()

                            images.take(60).forEach { file ->

                                try {
                                    val bytes = reader.readFile(file)
                                    val bitmap = decodeThumbnail(bytes)

                                    if (bitmap != null) {
                                        thumbnails[file.relativePath] = bitmap
                                    }

                                } catch (_: Exception) {
                                    // Una miniatura non riuscita non deve interrompere la scansione.
                                }
                            }

                            Pair(images, thumbnails)
                        } finally {
                            connection.releaseInterface(usbInterface)
                        }

                    } finally {
                        connection.close()
                    }
                }

                val files = scanResult.first
                thumbnailBitmaps = scanResult.second
                displayMediaList(device, files)
                mediaFiles = files

                // Vengono prese solo le foto
                //val imageCount = files.count { it.isImage }
                //val videoCount = files.count { it.isVideo }

                updateMessage("${files.size} contenuti trovati · ${files.size} foto")

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
        mediaFiles = files.filter { it.isImage }    // Filtro solo fotografie.

        val images = mediaFiles
        if (images.isEmpty()) {
            mediaListContainer.addView(
                TextView(this).apply {
                    text = "Nessuna fotografia trovata."
                    textSize = 14f
                    setTextColor(TEXT_SECONDARY)
                    gravity = Gravity.CENTER
                    setPadding(dp(16), dp(30), dp(16), dp(30))
                }
            )
            return
        }

        // GRIGLIA: due card per riga.
        if (isGridView) {
            var index = 0

            while (index < images.size) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.TOP
                }

                for (column in 0..1) {
                    val currentIndex = index + column

                    if (currentIndex < images.size) {
                        val file = images[currentIndex]
                        val card = createGridMediaCard(file, thumbnailBitmaps[file.relativePath])

                        card.setOnClickListener { openMediaViewer(device, currentIndex) }

                        row.addView(
                            card,
                            LinearLayout.LayoutParams(
                                0,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                1f
                            ).apply {
                                if (column == 0)
                                    rightMargin = dp(6)
                                else
                                    leftMargin = dp(6)

                                bottomMargin = dp(12)
                            }
                        )

                    } else {
                        // Mantiene la larghezza della seconda colonna.
                        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
                    }
                }

                mediaListContainer.addView(row)
                index += 2
            }
        } else {
            // LISTA: visualizzazione precedente.
            images.forEachIndexed { index, file ->
                val card = createMediaCard(file)

                card.setOnClickListener { openMediaViewer(device, index) }

                mediaListContainer.addView(
                    card,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(12) }
                )
            }
        }
    }

    private fun createMediaCard(file: Fat32File): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp( 12))

            background = roundedBackground(CARD_COLOR, dp(18))
            elevation = dp(1).toFloat()
            isClickable = true
            isFocusable = true
        }

        val thumbnail = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = roundedBackground(CARD_SECONDARY, dp(13))
            clipToOutline = true

            val bitmap = thumbnailBitmaps[file.relativePath]

            if (bitmap != null)
                setImageBitmap(bitmap)
            else {
                setImageResource(android.R.drawable.ic_menu_gallery)
                setColorFilter(TEXT_TERTIARY)
            }

            layoutParams = LinearLayout.LayoutParams(dp(66), dp(66))
        }

        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), 0, 0, 0)
        }

        val name = TextView(this).apply {
            text = file.name
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(TEXT_PRIMARY)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        val path = TextView(this).apply {
            text = file.relativePath
            textSize = 11f
            setTextColor(TEXT_TERTIARY)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setPadding(0, dp(3), 0, 0)
        }

        val size = TextView(this).apply {
            text = formatFileSize(file.size)
            textSize = 11f
            setTextColor(AMBER_COLOR)
            setPadding(0, dp(5), 0, 0)
        }

        details.addView(name)
        details.addView(path)
        details.addView(size)

        card.addView(thumbnail)

        card.addView(
            details,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        return card
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
            layoutParams = LinearLayout.LayoutParams(1, dp(height))
        }
    }

    private fun roundedBackground(color: Int, radius: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            setStroke(dp(1), ColorUtils.setAlphaComponent(ACCENT_COLOR, 45))
        }
    }

    private fun formatFileSize(size: Long): String {
        return when {
            size >= 1024L * 1024L -> "%.2f MB".format(size / (1024.0 * 1024.0))
            size >= 1024L -> "%.1f KB".format(size / 1024.0)
            else -> "$size byte"
        }
    }

    private fun createModeButton(symbol: String, action: () -> Unit): TextView {
        return TextView(this).apply {
            text = symbol
            textSize = 25f
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(5), dp(10), dp(5))
            isClickable = true
            isFocusable = true

            setOnClickListener { action() }
        }
    }

    private fun createGridMediaCard(file: Fat32File, preview: Bitmap?): View {
        val card = SquareCardLayout(this).apply {
            background = roundedBackground(CARD_COLOR, dp(18))
            clipToOutline = true
            elevation = dp(2).toFloat()
            isClickable = true
            isFocusable = true
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }

        // Anteprima: occupa tutto lo spazio verticale disponibile.
        val thumbnail = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(CARD_SECONDARY)

            if (preview != null)
                setImageBitmap(preview)
            else {
                setImageResource(android.R.drawable.ic_menu_gallery)
                setColorFilter(TEXT_TERTIARY)
                scaleType = ImageView.ScaleType.CENTER
            }

            clipToOutline = true
            background = roundedBackground(CARD_SECONDARY, dp(12))
        }

        content.addView(
            thumbnail,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                                 .apply { bottomMargin = dp(9) }
        )

        // Nome file: stessa dimensione della modalità elenco.
        val name = TextView(this).apply {
            text = file.name
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(TEXT_PRIMARY)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        // Percorso: stessa dimensione della modalità elenco.
        val path = TextView(this).apply {
            text = file.relativePath
            textSize = 11f
            setTextColor(TEXT_TERTIARY)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }

        // Dimensione: stessa dimensione della modalità elenco.
        val size = TextView(this).apply {
            text = formatFileSize(file.size)
            textSize = 11f
            setTextColor(AMBER_COLOR)
            setPadding(0, dp(3), 0, 0)
        }

        content.addView(name)
        content.addView(path)
        content.addView(size)

        card.addView(
            content,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        return card
    }


    private fun updateGalleryModeButtons() {
        listModeButton.apply {
            setTextColor(if (!isGridView) Color.BLACK else TEXT_TERTIARY)

            background = roundedBackground(if (!isGridView) ACCENT_COLOR else CARD_COLOR, dp(10))
        }

        gridModeButton.apply {
            setTextColor(if (isGridView) Color.BLACK else TEXT_TERTIARY)

            background = roundedBackground(if (isGridView) ACCENT_COLOR else CARD_COLOR, dp(10))
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
        container.setOnTouchListener { _, event -> gestureDetector.onTouchEvent(event) }

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


    private fun calculateSampleSize(width: Int, height: Int, maxWidth: Int, maxHeight: Int): Int {
        var sampleSize = 1

        while (width / (sampleSize * 2) > maxWidth ||
            height / (sampleSize * 2) > maxHeight) {
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

    private fun decodeThumbnail(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }

        BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            bounds
        )

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0)
            return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateSampleSize(
                bounds.outWidth,
                bounds.outHeight,
                450,
                450
            )

            inPreferredConfig = Bitmap.Config.RGB_565
        }

        return BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            options
        )
    }
}


// Square card class
private class SquareCardLayout(context: Context) : FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val squareSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
        super.onMeasure(widthMeasureSpec, squareSpec)
    }
}