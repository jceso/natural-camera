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
import android.view.WindowManager
import android.widget.ProgressBar
import androidx.core.graphics.ColorUtils
import com.example.naturalcam.photoProcessing.ColorPreset
import com.example.naturalcam.photoProcessing.ColorPresets
import com.example.naturalcam.photoProcessing.VibranceProcessor
import kotlinx.coroutines.Job
import java.io.File

import android.widget.HorizontalScrollView
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.isVisible

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
    private var blurredContentView: View? = null

    private var autoScanDeviceId: Int? = null
    private var permissionRequestedDeviceId: Int? = null

    private var viewerDialog: Dialog? = null
    private var viewerPosition = 0
    private var viewerImage: ImageView? = null
    private var viewerVideo: VideoView? = null
    private var viewerInfo: TextView? = null


    // Per i presets
    private var viewerOriginalBitmap: Bitmap? = null
    private var viewerSelectedPreset: ColorPreset = ColorPresets.natural
    private var viewerFilterEnabled = false

    private var viewerRenderToken = 0
    private var viewerLoadJob: Job? = null


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
                statusDescription.text = getString(R.string.status_permiss_success)

                loadMedia(device)
            } else {
                permissionRequestedDeviceId = null
                statusDescription.text = getString(R.string.status_auth_failed)

                updateMessage("Accesso alla fotocamera non autorizzato.")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        usbManager = getSystemService(USB_SERVICE) as UsbManager

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
            text = getString(R.string.app_name)
            textSize = 29f
            setTextColor(TEXT_PRIMARY)
            setTypeface(null, Typeface.BOLD)
            letterSpacing = 0.04f
        }
        root.addView(title)

        val subtitle = TextView(this).apply {
            text = getString(R.string.app_subtitle)
            textSize = 14f
            setTextColor(MUTED_CYAN)
        }
        root.addView(subtitle)
        root.addView(spacer(30))

        // Diagnostica USB
        root.addView(sectionTitle())
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
            text = getString(R.string.conn_loading)
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(TEXT_SECONDARY)
            setPadding(dp(10), 0, 0, 0)
        }
        connectionHeader.addView(connectionIndicator)
        connectionHeader.addView(statusText)

        connectionCard.addView(connectionHeader)
        statusDescription = TextView(this).apply {
            text = getString(R.string.conn_loading_desc)
            textSize = 14f
            setTextColor(TEXT_TERTIARY)
            setPadding(0, dp(8), 0, 0)
        }
        connectionCard.addView(statusDescription)
        root.addView(connectionCard)
        root.addView(spacer(16))

        // Pulsante ricerca
        searchButton = Button(this).apply {
            text = getString(R.string.update_button)
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
            text = getString(R.string.content_summary)
            textSize = 11f
            setTextColor(TEXT_SECONDARY)
        }
        root.addView(messageText)

        val galleryHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val galleryTitle = TextView(this).apply {
            text = getString(R.string.content_title)
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
            statusDescription.text = getString(R.string.status_permiss_success)

            // Evita di avviare la stessa scansione più volte.
            if (autoScanDeviceId != deviceId) {
                autoScanDeviceId = deviceId
                loadMedia(rollei)
            }

        } else {
            statusDescription.text = getString(R.string.status_permiss_failed)

            // Chiediamo il permesso automaticamente una sola volta.
            if (permissionRequestedDeviceId != deviceId) {
                permissionRequestedDeviceId = deviceId
                requestUsbPermission(rollei)
            }
        }
    }

    private fun updateConnectionStatus(connected: Boolean) {
        if (connected) {
            statusText.text = getString(R.string.status_conn_success)
            statusDescription.text = getString(R.string.status_conn_detected)
            statusText.setTextColor(SUCCESS_COLOR)
        } else {
            statusText.text = getString(R.string.status_conn_failed)
            statusDescription.text = getString(R.string.conn_loading_desc)
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

        updateMessage("La Rollei sta preparando le fotografie...")
        showGalleryLoading()

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
                mediaListContainer.removeAllViews()

                updateMessage("Errore durante la lettura: " + (e.message ?: e.javaClass.simpleName))
                Toast.makeText(this@MainActivity, "Impossibile leggere la memoria della fotocamera", Toast.LENGTH_LONG).show()
            } finally {
                isLoading = false
                searchButton.isEnabled = selectedDevice != null
            }
        }
    }

    // ---------------------------------------------------------
    // GALLERIA
    // ---------------------------------------------------------
    private fun showGalleryLoading() {
        mediaListContainer.removeAllViews()

        val loadingCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(30), dp(24), dp(30))
            background = roundedBackground(CARD_SECONDARY, dp(18))
        }

        // Indicatore animato
        val progress = ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleLarge
        ).apply {
            isIndeterminate = true
            indeterminateTintList =
                android.content.res.ColorStateList.valueOf(ACCENT_COLOR)
        }

        loadingCard.addView(
            progress,
            LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                bottomMargin = dp(20)
            }
        )

        val title = TextView(this).apply {
            text = getString(R.string.content_load_title)
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(TEXT_PRIMARY)
            gravity = Gravity.CENTER
        }

        val description = TextView(this).apply {
            text = getString(R.string.content_load_desc)
            textSize = 12f
            setTextColor(TEXT_SECONDARY)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }

        loadingCard.addView(title)
        loadingCard.addView(description)

        mediaListContainer.addView(
            loadingCard,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun displayMediaList(device: UsbDevice, files: List<Fat32File>) {
        mediaListContainer.removeAllViews()
        mediaFiles = files.filter { it.isImage }    // Filtro solo fotografie.

        val images = mediaFiles
        if (images.isEmpty()) {
            mediaListContainer.addView(
                TextView(this).apply {
                    text = getString(R.string.content_empty)
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
    private fun sectionTitle(): TextView {
        return TextView(this).apply {
            text = getString(R.string.conn_title)
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


    private fun openMediaViewer(device: UsbDevice, position: Int) {
        if (mediaFiles.isEmpty())
            return

        viewerPosition = position
        val dialog = Dialog(this)

        // Contenitore principale
        val container = FrameLayout(this).apply { setBackgroundColor(Color.TRANSPARENT) }
        // Sfondo nero con opacità del 70%
        val darkOverlay = View(this).apply { setBackgroundColor(Color.argb(179, 0, 0, 0)) }

        container.addView(
            darkOverlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // Layout principale
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(18))
        }

        container.addView(
            content,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // -----------------------------------------------------
        // HEADER CON PULSANTE CHIUSURA
        // -----------------------------------------------------
        val header = FrameLayout(this)

        val closeButton = TextView(this).apply {
            text = "×"
            textSize = 34f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundedBackground(CARD_SECONDARY, dp(30))
            isClickable = true
            isFocusable = true
            setOnClickListener { closeMediaViewer() }
        }
        header.addView(
            closeButton,
            FrameLayout.LayoutParams(dp(44), dp(44), Gravity.END or Gravity.CENTER_VERTICAL)
        )
        content.addView(
            header,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50))
        )

        // -----------------------------------------------------
        // AREA FOTOGRAFIA
        // -----------------------------------------------------
        val middleArea = FrameLayout(this)
        content.addView(
            middleArea,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        // Foto + toggle, centrati nell'area elastica
        val imageAndFilter = GestureLinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        middleArea.addView(
            imageAndFilter,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )

        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
        }
        imageAndFilter.addView(
            image,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        // -----------------------------------------------------
        // TOGGLE FILTRO - SUBITO SOTTO LA FOTO
        // -----------------------------------------------------
        val filterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        val filterToggle = TextView(this).apply {
            text = ""
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            isClickable = true
            isFocusable = true
            contentDescription = "Attiva o disattiva filtro"
        }

        fun updateFilterToggle() {
            filterToggle.text = if (viewerFilterEnabled) "✓" else ""

            filterToggle.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(6).toFloat()
                setColor(if (viewerFilterEnabled) ACCENT_COLOR else Color.TRANSPARENT)
                setStroke(dp(1), ACCENT_COLOR)
            }
        }

        updateFilterToggle()

        filterToggle.setOnClickListener {
            viewerFilterEnabled = !viewerFilterEnabled
            updateFilterToggle()
            renderViewerImage()
        }
        filterRow.addView(
            filterToggle,
            LinearLayout.LayoutParams(dp(25), dp(25))
        )

        // Aggiungi filterRow a imageAndFilter, non direttamente a content
        imageAndFilter.addView(
            filterRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(29)
            ).apply {
                topMargin = dp(4)
            }
        )

        // Pannello inferiore: contiene i pulsanti e le informazioni
        val bottomPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        content.addView(
            bottomPanel,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        // -----------------------------------------------------
        // CHIP PROFILI COLORE
        // -----------------------------------------------------
        val lookScrollView = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            visibility = View.INVISIBLE
        }
        val lookChips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        lookScrollView.addView(
            lookChips,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val presetChips = mutableListOf<Pair<TextView, ColorPreset>>()

        fun updateLookChipStyles() {
            presetChips.forEach { (chip, preset) ->
                val selected = preset.id == viewerSelectedPreset.id
                chip.background = roundedBackground(
                    if (selected) ACCENT_COLOR else CARD_SECONDARY,
                    dp(20)
                )
                chip.setTextColor(if (selected) Color.BLACK else TEXT_PRIMARY)
            }
        }

        fun createLookChip(label: String, preset: ColorPreset): TextView {
            val chip = TextView(this).apply {
                text = label
                textSize = 10f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(dp(13), dp(8), dp(13), dp(8))
                background = roundedBackground(CARD_SECONDARY, dp(20))
                isClickable = true
                isFocusable = true

                setOnClickListener {
                    viewerSelectedPreset = preset
                    viewerFilterEnabled = true

                    updateFilterToggle()
                    updateLookChipStyles()
                    renderViewerImage()
                }
            }

            presetChips.add(chip to preset)
            return chip
        }

        lookChips.addView(
            createLookChip("NATURAL", ColorPresets.natural),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34))
                                 .apply { marginEnd = dp(8) }
        )
        lookChips.addView(
            createLookChip("VIVID", ColorPresets.vivid),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34))
                                 .apply { marginEnd = dp(8) }
        )
        lookChips.addView(
            createLookChip("B/W", ColorPresets.blackAndWhite),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34))
                                 .apply { marginEnd = dp(8) }
        )
        lookChips.addView(
            createLookChip("RETRO", ColorPresets.retro),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34))
                                 .apply { marginEnd = dp(8) }
        )
        lookChips.addView(
            createLookChip("POP", ColorPresets.pop),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34))
                .apply { marginEnd = dp(8) }
        )
        lookChips.addView(
            createLookChip("GOLDEN", ColorPresets.goldenHour),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34))
                .apply { marginEnd = dp(8) }
        )
        lookChips.addView(
            createLookChip("ARCTIC", ColorPresets.arctic),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34))
                .apply { marginEnd = dp(8) }
        )
        lookChips.addView(
            createLookChip("CREEPY", ColorPresets.analogHorror),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34))
                .apply { marginEnd = dp(8) }
        )

        updateLookChipStyles()

        bottomPanel.addView(
            lookScrollView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42))
        )

        // -----------------------------------------------------
        // QUATTRO PULSANTI PRINCIPALI - SOLO ICONE
        // Ordine da sinistra a destra:
        // REGOLA - CONDIVIDI - SALVA - LOOK
        // -----------------------------------------------------
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        fun createViewerAction(symbol: String, description: String, onClick: (() -> Unit)? = null): TextView {
            return TextView(this).apply {
                text = symbol
                textSize = 24f
                gravity = Gravity.CENTER
                setTextColor(ACCENT_COLOR)
                background = roundedBackground(CARD_SECONDARY, dp(13))
                isClickable = true
                isFocusable = true
                contentDescription = description

                setOnClickListener { onClick?.invoke() }
            }
        }

        // LOOK: mostra/nasconde i profili colore
        val lookButton = createViewerAction("✦", "Profili colore") {
            lookScrollView.isVisible = !lookScrollView.isVisible
        }

        // SALVA
        val saveButton = createViewerAction("↓", "Salva") {
            saveCurrentImageToGallery()
        }

        // CONDIVIDI
        val shareButton = createViewerAction("↗", "Condividi")

        // REGOLA
        val adjustButton = createViewerAction("☷", "Regola")

        listOf(
            adjustButton,
            saveButton,
            shareButton,
            lookButton
        ).forEach { button ->
            actions.addView(
                button,
                LinearLayout.LayoutParams(0, dp(52), 1f)
                                     .apply {
                                                marginStart = dp(4)
                                                marginEnd = dp(4)
                                            }
            )
        }

        bottomPanel.addView(
            actions,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56))
        )


        // -----------------------------------------------------
        // INFORMAZIONI FOTOGRAFIA
        // -----------------------------------------------------
        val info = TextView(this).apply {
            textSize = 12f
            setTextColor(TEXT_PRIMARY)
            setPadding(dp(4), dp(12), dp(4), 0)
            maxLines = 2
        }

        bottomPanel.addView(
            info,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        // Riferimenti utilizzati dal viewer
        viewerDialog = dialog
        viewerImage = image
        viewerVideo = null
        viewerInfo = info

        // -----------------------------------------------------
        // CONFIGURAZIONE DIALOG
        // -----------------------------------------------------
        dialog.setContentView(container)
        dialog.setCancelable(false)

        dialog.window?.apply {
            setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0f)
        }

        dialog.show()
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        // -----------------------------------------------------
        // SFONDO BLUR (ANDROID 12+)
        // -----------------------------------------------------
        val underlyingContent = window.decorView.findViewById<View>(android.R.id.content)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            underlyingContent.setRenderEffect(
                android.graphics.RenderEffect.createBlurEffect(
                    22f,
                    22f,
                    android.graphics.Shader.TileMode.CLAMP
                )
            )

            blurredContentView = underlyingContent
        }

        dialog.setOnDismissListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                blurredContentView?.setRenderEffect(null)

            blurredContentView = null
            viewerDialog = null
            viewerImage = null
            viewerVideo = null
            viewerInfo = null
        }

        // -----------------------------------------------------
        // SWIPE PER CAMBIARE FOTOGRAFIA
        // -----------------------------------------------------
        val gestureDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    imageAndFilter.performClick()
                    return true
                }

                override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                    // Con i preset aperti, gli swipe non cambiano la foto.
                    if (lookScrollView.isVisible)
                        return false

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
            }
        )

        imageAndFilter.setOnTouchListener { _, event -> gestureDetector.onTouchEvent(event) }

        showViewerItem(device, position)
    }

    private fun renderViewerImage() {
        val source = viewerOriginalBitmap ?: return
        val imageView = viewerImage ?: return
        val positionAtStart = viewerPosition
        val renderToken = ++viewerRenderToken

        if (!viewerFilterEnabled) {
            imageView.setImageBitmap(source)
            return
        }

        val preset = viewerSelectedPreset

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    VibranceProcessor.apply(source, preset)
                }

                // Evita di mostrare il risultato se nel frattempo è stata aperta
                // un'altra foto o è stata richiesta una nuova elaborazione.
                if (renderToken == viewerRenderToken &&
                    positionAtStart == viewerPosition &&
                    viewerImage === imageView
                ) {
                    imageView.setImageBitmap(result)
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "Impossibile applicare il preset: ${e.localizedMessage}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun showViewerItem(device: UsbDevice, position: Int) {
        if (position !in mediaFiles.indices)
            return
        viewerPosition = position
        viewerOriginalBitmap = null
        viewerRenderToken++

        viewerLoadJob?.cancel()
//        viewerSelectedPreset = ColorPresets.natural
//        viewerFilterEnabled = false
        val file = mediaFiles[position]

        viewerVideo?.stopPlayback()
        viewerVideo?.visibility = View.GONE

        viewerImage?.visibility = View.GONE
        viewerImage?.setImageDrawable(null)

        viewerInfo?.text = getString(R.string.content_summary_count, file.name, position + 1, mediaFiles.size)

        viewerLoadJob = lifecycleScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { readMediaFile(device, file) }

                if (file.isImage) {
                    val bitmap = withContext(Dispatchers.Default) { decodeViewerBitmap(bytes) }
                    if (position != viewerPosition)
                        return@launch

                    viewerOriginalBitmap = bitmap
                    viewerImage?.visibility = View.VISIBLE
                    renderViewerImage()
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            blurredContentView?.setRenderEffect(null)

        blurredContentView = null

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


    private val storagePermissionRequestCode = 4731

    private fun saveCurrentImageToGallery() {
        val drawable = viewerImage?.drawable
        if (drawable == null) {
            Toast.makeText(this, "Nessuna immagine da salvare", Toast.LENGTH_SHORT).show()
            return
        }

        // Il permesso è necessario solo fino ad Android 9 (API 28).
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), storagePermissionRequestCode)
                return
        }

        try {
            val bitmap = drawableToBitmap(
                drawable,
                viewerImage?.width ?: 1,
                viewerImage?.height ?: 1
            )
            val fileName = "IMG_${java.text.SimpleDateFormat(
                "yyyyMMdd_HHmmss",
                java.util.Locale.US
            ).format(java.util.Date())}.jpg"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(
                        android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_PICTURES
                    )
                    put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
                }

                val uri = contentResolver.insert(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values
                ) ?: throw IOException("Impossibile creare il file")

                try {
                    val output = contentResolver.openOutputStream(uri)
                        ?: throw IOException("Impossibile aprire il file")

                    output.use {
                        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it))
                            throw IOException("Impossibile codificare l'immagine")
                    }

                    val completedValues = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                    }
                    contentResolver.update(uri, completedValues, null, null)
                } catch (e: Exception) {
                    contentResolver.delete(uri, null, null)
                    throw e
                }
            } else {
                val picturesDir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_PICTURES
                )
                if (!picturesDir.exists() && !picturesDir.mkdirs())
                    throw IOException("Impossibile creare la cartella Pictures")

                val file = File(picturesDir, fileName)
                try {
                    java.io.FileOutputStream(file).use {
                        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) {
                            throw IOException("Impossibile codificare l'immagine")
                        }
                    }
                } catch (e: Exception) {
                    file.delete()
                    throw e
                }

                android.media.MediaScannerConnection.scanFile(
                    this,
                    arrayOf(file.absolutePath),
                    arrayOf("image/jpeg"),
                    null
                )
            }

            Toast.makeText(this, "Immagine salvata in Pictures", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Salvataggio non riuscito: ${e.localizedMessage ?: "errore sconosciuto"}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == storagePermissionRequestCode) {
            if (grantResults.firstOrNull() ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                saveCurrentImageToGallery()
            } else {
                Toast.makeText(this, "Permesso di archiviazione negato", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun drawableToBitmap(
        drawable: android.graphics.drawable.Drawable,
        fallbackWidth: Int,
        fallbackHeight: Int
    ): Bitmap {
        if (drawable is android.graphics.drawable.BitmapDrawable) {
            val source = drawable.bitmap
            if (source.config != Bitmap.Config.HARDWARE) {
                return source
            }
            return source.copy(Bitmap.Config.ARGB_8888, false)
                ?: throw IOException("Impossibile leggere l'immagine")
        }

        val width = drawable.intrinsicWidth.takeIf { it > 0 }
            ?: fallbackWidth.coerceAtLeast(1)
        val height = drawable.intrinsicHeight.takeIf { it > 0 }
            ?: fallbackHeight.coerceAtLeast(1)

        return createBitmap(width, height).also { bitmap ->
            val canvas = android.graphics.Canvas(bitmap)
            drawable.setBounds(0, 0, width, height)
            drawable.draw(canvas)
        }
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

private class GestureLinearLayout(context: Context) : LinearLayout(context) {
    init { isClickable = true }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}