package com.jonoshields.driftwood.ui.addcontact

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.jonoshields.driftwood.core.store.DisplayName
import com.jonoshields.driftwood.ui.common.AuthorName
import com.jonoshields.driftwood.ui.common.ContactControls
import com.jonoshields.driftwood.ui.common.OrDivider
import java.util.concurrent.Executors

@Composable
fun AddContactScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AddContactViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    AddContactContent(
        state = state,
        onBack = onBack,
        onStartScanning = viewModel::startScanning,
        onCancelScanning = viewModel::cancelScanning,
        onQrScanned = viewModel::onQrScanned,
        onSetNickname = viewModel::setNickname,
        onToggleFollow = viewModel::toggleFollow,
        onConfirm = viewModel::confirm,
        // Stays on this screen, back to showing your own code — a real exit is via top bar Back.
        onDone = viewModel::done,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddContactContent(
    state: AddContactUiState,
    onBack: () -> Unit,
    onStartScanning: () -> Unit,
    onCancelScanning: () -> Unit,
    onQrScanned: (String) -> Unit,
    onSetNickname: (String) -> Unit,
    onToggleFollow: () -> Unit,
    onConfirm: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Cancel (not Back) once scanned — a plain Back would silently abandon the confirmation.
    val midFlow = state is AddContactUiState.Confirming || (state as? AddContactUiState.Ready)?.scanning == true

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Quick verify") },
                navigationIcon = {
                    if (!midFlow) TextButton(onClick = onBack) { Text("Back") }
                },
                actions = {
                    if (midFlow) {
                        TextButton(onClick = { onCancelScanning(); onBack() }) { Text("Cancel") }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            // Scrollable so the nickname field/save button stay reachable behind the keyboard.
            Modifier.padding(padding).padding(24.dp).fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            when (state) {
                is AddContactUiState.Ready ->
                    if (state.scanning) {
                        ScanningContent(onQrScanned = onQrScanned, onCancel = onCancelScanning)
                    } else {
                        ReadyContent(myQrPayload = state.myQrPayload, onStartScanning = onStartScanning)
                    }

                is AddContactUiState.Confirming -> ConfirmContent(
                    displayName = state.displayName,
                    isFollowing = state.isFollowing,
                    onSetNickname = onSetNickname,
                    onToggleFollow = onToggleFollow,
                    onConfirm = onConfirm,
                )

                is AddContactUiState.Added -> AddedContent(displayName = state.displayName, onDone = onDone)
            }
        }
    }
}

@Composable
private fun ReadyContent(myQrPayload: String?, onStartScanning: () -> Unit) {
    Text("Your code", style = MaterialTheme.typography.titleMedium)
    Text(
        "Someone adding you scans this — it's your fingerprint, not a live connection.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (myQrPayload != null) {
        val qrBitmap = remember(myQrPayload) { encodeQrBitmap(myQrPayload, sizePx = 512) }
        Image(
            bitmap = qrBitmap,
            contentDescription = "Your identity as a QR code",
            modifier = Modifier.size(240.dp),
        )
    }

    OrDivider()

    Button(
        onClick = onStartScanning,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.secondary,
            contentColor = MaterialTheme.colorScheme.onSecondary,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Scan their code")
    }
}

@Composable
private fun ScanningContent(onQrScanned: (String) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    var permissionGranted by rememberSaveable {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val requestPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> permissionGranted = granted }
    LaunchedEffect(Unit) { if (!permissionGranted) requestPermission.launch(Manifest.permission.CAMERA) }

    if (!permissionGranted) {
        Text(
            "Scanning needs the camera permission.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(onClick = { requestPermission.launch(Manifest.permission.CAMERA) }) {
            Text("Grant camera access")
        }
        return
    }

    Text("Point the camera at their code", style = MaterialTheme.typography.titleMedium)
    CameraPreview(onQrScanned = onQrScanned, modifier = Modifier.fillMaxWidth().size(320.dp))
}

/** Wraps CameraX's [PreviewView] with an [ImageAnalysis] use case decoding frames via ZXing directly. */
@Composable
private fun CameraPreview(onQrScanned: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val onQrScannedState = rememberUpdatedState(onQrScanned)
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) { onDispose { analysisExecutor.shutdown() } }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener(
                {
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().apply {
                        surfaceProvider = previewView.surfaceProvider
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .apply { setAnalyzer(analysisExecutor, QrAnalyzer { onQrScannedState.value(it) }) }
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                },
                ContextCompat.getMainExecutor(ctx),
            )
            previewView
        },
    )
}

/** Decodes the Y (luminance) plane directly — QR decoding needs no colour, so skip YUV-to-RGB. */
private class QrAnalyzer(private val onDecoded: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
    }

    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes[0]
            val bytes = ByteArray(plane.buffer.remaining())
            plane.buffer.get(bytes)
            val source = PlanarYUVLuminanceSource(
                bytes, image.width, image.height, 0, 0, image.width, image.height, false,
            )
            val result = reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
            onDecoded(result.text)
        } catch (e: NotFoundException) {
            // No QR code in this frame — the ordinary case, not a failure worth reacting to.
        } finally {
            image.close()
        }
    }
}

private fun encodeQrBitmap(payload: String, sizePx: Int): androidx.compose.ui.graphics.ImageBitmap {
    val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, sizePx, sizePx)
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
    for (x in 0 until sizePx) {
        for (y in 0 until sizePx) {
            bitmap.setPixel(x, y, if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
    return bitmap.asImageBitmap()
}

@Composable
private fun ConfirmContent(
    displayName: DisplayName,
    isFollowing: Boolean,
    onSetNickname: (String) -> Unit,
    onToggleFollow: () -> Unit,
    onConfirm: () -> Unit,
) {
    // Saved on confirm, not via its own button — nothing needs the nickname before then.
    var nicknameDraft by rememberSaveable { mutableStateOf("") }

    Text("Verify this contact?", style = MaterialTheme.typography.titleMedium)
    AuthorName(displayName)
    Text(
        "Only verify someone whose code you scanned yourself, in person.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
    ContactControls(
        currentNickname = null,
        isFollowing = isFollowing,
        onSetNickname = onSetNickname,
        onToggleFollow = onToggleFollow,
        showSaveButton = false,
        onDraftChange = { nicknameDraft = it },
    )
    Button(
        onClick = {
            if (nicknameDraft.isNotBlank()) onSetNickname(nicknameDraft)
            onConfirm()
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Verify") }
}

@Composable
private fun AddedContent(displayName: DisplayName, onDone: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Added", style = MaterialTheme.typography.headlineSmall)
        AuthorName(displayName)
        Text(
            "Now show them your code so they can add you back.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        Button(onClick = onDone, modifier = Modifier.padding(top = 16.dp)) { Text("Show my code") }
    }
}
