package com.example.naturalcam

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
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
import android.util.AttributeSet
import android.widget.GridLayout
import android.widget.ImageButton

class MainActivity : AppCompatActivity() {

    companion object {
        private const val ACTION_USB_PERMISSION = "com.example.naturalcam.USB_PERMISSION"

        private const val ROLLEI_VENDOR_ID = 0x1B3F
        private const val ROLLEI_PRODUCT_ID = 0x8301
    }

    private val accentColor get() = ContextCompat.getColor(this, R.color.accent_color)

    private lateinit var usbManager: UsbManager

    private lateinit var statusText: TextView
    private lateinit var statusDescription: TextView
    private lateinit var searchButton: Button
    private lateinit var messageText: TextView
    private lateinit var mediaListContainer: GridLayout
    private lateinit var galleryLoading: ProgressBar
    private lateinit var galleryEmpty: TextView

    private var selectedDevice: UsbDevice? = null
    private var receiverRegistered = false
    private var isLoading = false

    private var mediaFiles: List<Fat32File> = emptyList()
    private var isGridView = true
    private var thumbnailBitmaps: Map<String, Bitmap> = emptyMap()
    private lateinit var listModeButton: ImageButton
    private lateinit var gridModeButton: ImageButton
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
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        statusDescription = findViewById(R.id.statusDescription)
        searchButton = findViewById(R.id.searchButton)
        messageText = findViewById(R.id.messageText)
        listModeButton = findViewById(R.id.listModeButton)
        gridModeButton = findViewById(R.id.gridModeButton)

        mediaListContainer = findViewById(R.id.mediaListContainer)
        galleryLoading = findViewById(R.id.galleryLoading)
        galleryEmpty = findViewById(R.id.galleryEmpty)

        findViewById<LinearLayout>(R.id.connectionCard).apply {
            elevation = dp(2).toFloat()
        }

        searchButton.apply {
            setTextColor(Color.BLACK)

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

        listModeButton.setOnClickListener {
            isGridView = false
            updateGalleryModeButtons()
            selectedDevice?.let { displayMediaList(it, mediaFiles) }
        }

        gridModeButton.setOnClickListener {
            isGridView = true
            updateGalleryModeButtons()
            selectedDevice?.let { displayMediaList(it, mediaFiles) }
        }

        updateGalleryModeButtons()
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
            statusText.setTextColor(ContextCompat.getColor(this, R.color.success_color))
        } else {
            statusText.text = getString(R.string.status_conn_failed)
            statusDescription.text = getString(R.string.conn_loading_desc)
            statusText.setTextColor(ContextCompat.getColor(this, R.color.failure_color))
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

                galleryLoading.isVisible = false
                mediaListContainer.removeAllViews()
                mediaListContainer.isVisible = false
                galleryEmpty.isVisible = true
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
        galleryEmpty.isVisible = false
        galleryLoading.isVisible = true
    }

    private fun displayMediaList(device: UsbDevice, files: List<Fat32File>) {
        mediaListContainer.removeAllViews()
        galleryLoading.isVisible = false

        mediaFiles = files.filter { it.isImage }
        galleryEmpty.isVisible = mediaFiles.isEmpty()
        mediaListContainer.isVisible = mediaFiles.isNotEmpty()

        if (mediaFiles.isEmpty()) return

        mediaListContainer.columnCount = if (isGridView) 2 else 1

        mediaFiles.forEachIndexed { index, file ->
            val layoutId = if (isGridView) {
                R.layout.item_media_grid
            } else {
                R.layout.item_media_list
            }

            val card = layoutInflater.inflate(layoutId, mediaListContainer, false)
            val thumbnail = card.findViewById<ImageView>(R.id.mediaThumbnail)
            val bitmap = thumbnailBitmaps[file.relativePath]

            if (bitmap != null) {
                thumbnail.setImageBitmap(bitmap)
            } else {
                thumbnail.setImageResource(android.R.drawable.ic_menu_gallery)
                thumbnail.setColorFilter(ContextCompat.getColor(this, R.color.text_tertiary))
            }

            card.findViewById<TextView>(R.id.mediaName).text = file.name
            card.findViewById<TextView>(R.id.mediaPath).text = file.relativePath
            card.findViewById<TextView>(R.id.mediaSize).text = formatFileSize(file.size)
            card.setOnClickListener { openMediaViewer(device, index) }

            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(0), dp(0), dp(6), dp(12))
            }

            mediaListContainer.addView(card, params)
        }
    }


    // ---------------------------------------------------------
    // UTILITÀ UI
    // ---------------------------------------------------------

    private fun roundedBackground(color: Int, radius: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            setStroke(dp(1), ColorUtils.setAlphaComponent(accentColor, 45))
        }
    }

    private fun formatFileSize(size: Long): String {
        return when {
            size >= 1024L * 1024L -> "%.2f MB".format(size / (1024.0 * 1024.0))
            size >= 1024L -> "%.1f KB".format(size / 1024.0)
            else -> "$size byte"
        }
    }

    private fun updateGalleryModeButtons() {
        listModeButton.isSelected = !isGridView
        gridModeButton.isSelected = isGridView
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
        val viewer = layoutInflater.inflate(R.layout.dialog_media_viewer, null)

        val imageAndFilter = viewer.findViewById<GestureLinearLayout>(R.id.viewerImageAndFilter)
        val image = viewer.findViewById<ImageView>(R.id.viewerImage)
        val video = viewer.findViewById<VideoView>(R.id.viewerVideo)
        val filterToggle = viewer.findViewById<ImageButton>(R.id.viewerFilterToggle)
        val presetScroll = viewer.findViewById<HorizontalScrollView>(R.id.viewerPresetScroll)
        val presetContainer = viewer.findViewById<LinearLayout>(R.id.viewerPresetContainer)
        val info = viewer.findViewById<TextView>(R.id.viewerInfo)

        viewerDialog = dialog
        viewerImage = image
        viewerVideo = video
        viewerInfo = info

        viewer.findViewById<TextView>(R.id.viewerCloseButton).setOnClickListener {
            closeMediaViewer()
        }

        viewer.findViewById<ImageButton>(R.id.viewerSaveButton).setOnClickListener {
            saveCurrentImageToGallery()
        }

        viewer.findViewById<TextView>(R.id.viewerLookButton).setOnClickListener {
            presetScroll.visibility = if (presetScroll.isVisible) View.INVISIBLE else View.VISIBLE
        }

        fun updateFilterToggle() {
            filterToggle.isSelected = viewerFilterEnabled
        }
        filterToggle.setOnClickListener {
            viewerFilterEnabled = !viewerFilterEnabled
            updateFilterToggle()
            renderViewerImage()
        }

        // PRESET
        val presetDefinitions = listOf(
            "NATURAL" to ColorPresets.natural,
            "VIVID" to ColorPresets.vivid,
            "B/W" to ColorPresets.blackAndWhite,
            "RETRO" to ColorPresets.retro,
            "POP" to ColorPresets.pop,
            "GOLDEN" to ColorPresets.goldenHour,
            "ARCTIC" to ColorPresets.arctic,
            "CREEPY" to ColorPresets.analogHorror
        )

        val presetChips = presetDefinitions.map { (label, preset) ->
            val chip = TextView(this).apply {
                text = label
                gravity = android.view.Gravity.CENTER
                setPadding(dp(14), 0, dp(14), 0)
                isClickable = true
                isFocusable = true
            }

            presetContainer.addView(
                chip,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                ).apply {
                    marginEnd = dp(8)
                }
            )

            chip to preset
        }

        fun updateLookChipStyles() {
            presetChips.forEach { (chip, preset) ->
                val selected = preset.id == viewerSelectedPreset.id
                chip.background = roundedBackground(
                    if (selected) accentColor else ContextCompat.getColor(this, R.color.card_secondary),
                    dp(20)
                )
                chip.setTextColor(ContextCompat.getColor(this, if (selected) R.color.black else R.color.text_primary))
            }
        }

        presetChips.forEach { (chip, preset) ->
            chip.setOnClickListener {
                viewerSelectedPreset = preset
                viewerFilterEnabled = true
                updateFilterToggle()
                updateLookChipStyles()
                renderViewerImage()
            }
        }

        updateFilterToggle()
        updateLookChipStyles()

        filterToggle.setOnClickListener {
            viewerFilterEnabled = !viewerFilterEnabled
            updateFilterToggle()
            renderViewerImage()
        }

        dialog.setContentView(viewer)
        dialog.setCancelable(false)
        dialog.show()

        dialog.window?.apply {
            setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0f)
        }

        val underlyingContent =
            window.decorView.findViewById<View>(android.R.id.content)

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

        val gestureDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    imageAndFilter.performClick()
                    return true
                }

                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float
                ): Boolean {
                    if (presetScroll.isVisible)
                        return false

                    val start = e1 ?: return false
                    val deltaX = e2.x - start.x
                    val deltaY = e2.y - start.y

                    if (
                        kotlin.math.abs(deltaX) > dp(70) &&
                        kotlin.math.abs(deltaX) > kotlin.math.abs(deltaY) &&
                        kotlin.math.abs(velocityX) > dp(100)
                    ) {
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

        imageAndFilter.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
        }

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

class GestureLinearLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}