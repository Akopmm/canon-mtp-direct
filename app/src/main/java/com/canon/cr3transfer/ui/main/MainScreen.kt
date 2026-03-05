package com.canon.cr3transfer.ui.main

import android.content.Intent
import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.canon.cr3transfer.R
import com.canon.cr3transfer.domain.model.TransferState
import com.canon.cr3transfer.ui.components.CameraSetupGuide
import com.canon.cr3transfer.ui.components.FileProgressItem
import com.canon.cr3transfer.ui.components.OverallProgressBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onStartTransfer: () -> Unit,
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.app_name)) })
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (val currentState = state) {
                is TransferState.Idle -> IdleContent()
                is TransferState.CameraConnected -> CameraConnectedContent(
                    fileCount = viewModel.files.size,
                    onScan = { viewModel.scanCamera() },
                    onStartTransfer = onStartTransfer,
                )
                is TransferState.Scanning -> ScanningContent()
                is TransferState.Transferring -> TransferringContent(currentState)
                is TransferState.Done -> DoneContent(currentState)
                is TransferState.Error -> ErrorContent(currentState)
            }
        }
    }
}

@Composable
private fun IdleContent() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Canon EOS R8",
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.connect_prompt),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CameraConnectedContent(
    fileCount: Int,
    onScan: () -> Unit,
    onStartTransfer: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Camera Connected",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(modifier = Modifier.height(16.dp))
        if (fileCount == 0) {
            Button(onClick = onScan) {
                Text("Scan for CR3 Files")
            }
        } else {
            Text(
                text = "$fileCount CR3 files found",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onStartTransfer) {
                Text(stringResource(R.string.start_transfer))
            }
        }
    }
}

@Composable
private fun ScanningContent() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.scanning),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun TransferringContent(state: TransferState.Transferring) {
    Column(modifier = Modifier.fillMaxSize()) {
        OverallProgressBar(
            completed = state.completedFiles,
            total = state.totalFiles,
            currentFileName = state.currentFileName,
        )
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(state.fileStatuses) { fileStatus ->
                FileProgressItem(fileStatus = fileStatus)
            }
        }
    }
}

@Composable
private fun DoneContent(state: TransferState.Done) {
    val context = LocalContext.current
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.files_transferred, state.transferred, state.skipped),
                style = MaterialTheme.typography.headlineSmall,
            )
            if (state.failed > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${state.failed} files failed",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = {
                val importDir = java.io.File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                    "CanonImports"
                )
                val cr3Files = importDir.walkTopDown()
                    .filter { it.isFile && it.name.endsWith(".CR3", ignoreCase = true) }
                    .toList()

                if (cr3Files.isEmpty()) {
                    Toast.makeText(context, "No CR3 files found", Toast.LENGTH_SHORT).show()
                    return@Button
                }

                val authority = "${context.packageName}.fileprovider"
                val uris = ArrayList(cr3Files.map { file ->
                    FileProvider.getUriForFile(context, authority, file)
                })

                val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "image/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    setPackage("com.adobe.lrmobile")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    context.startActivity(intent)
                } catch (_: Exception) {
                    // Lightroom not installed, fall back to share chooser
                    try {
                        val chooser = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                            type = "image/*"
                            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(chooser, "Import ${cr3Files.size} CR3 files"))
                    } catch (_: Exception) {
                        Toast.makeText(context, "No app available to import files", Toast.LENGTH_SHORT).show()
                    }
                }
            }) {
                Text("Import to Lightroom")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = {
                val uri = android.net.Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADCIM%2FCanonImports")
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "vnd.android.document/directory")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                try {
                    context.startActivity(intent)
                } catch (_: Exception) {
                    try {
                        val fallback = Intent(Intent.ACTION_VIEW).apply {
                            data = android.net.Uri.parse("content://com.android.externalstorage.documents/root/primary")
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(fallback)
                    } catch (_: Exception) { }
                }
            }) {
                Text(stringResource(R.string.open_folder))
            }
        }
    }
}

@Composable
private fun ErrorContent(state: TransferState.Error) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = state.message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
        )
        if (state.isCameraSetupError) {
            Spacer(modifier = Modifier.height(16.dp))
            CameraSetupGuide()
        }
    }
}
