package com.ff6locke.wordex

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WordExApp(
            openText = { uri -> contentResolver.openInputStream(uri)?.use { input ->
                BufferedReader(InputStreamReader(input)).readText()
            } ?: "" },
            saveText = { uri, text -> contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) } },
            exportPdf = { uri, text -> writePdf(uri, text) }
        ) }
    }

    private fun writePdf(uri: Uri, text: String) {
        val pdf = PdfDocument()
        val paint = Paint().apply { textSize = 12f; color = android.graphics.Color.BLACK }
        val pageWidth = 595
        val pageHeight = 842
        val margin = 48f
        val lineHeight = 18f
        val lines = text.lines().flatMap { line ->
            if (line.isEmpty()) listOf("") else line.chunked(85)
        }
        var pageNumber = 1
        var y = margin
        var page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
        lines.forEach { line ->
            if (y > pageHeight - margin) {
                pdf.finishPage(page)
                pageNumber++
                page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
                y = margin
            }
            page.canvas.drawText(line, margin, y, paint)
            y += lineHeight
        }
        pdf.finishPage(page)
        contentResolver.openOutputStream(uri)?.use { pdf.writeTo(it) }
        pdf.close()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WordExApp(
    openText: (Uri) -> String,
    saveText: (Uri, String) -> Unit,
    exportPdf: (Uri, String) -> Unit
) {
    var title by remember { mutableStateOf("Untitled document") }
    var content by remember { mutableStateOf(TextFieldValue("")) }
    var darkMode by remember { mutableStateOf(false) }
    var bold by remember { mutableStateOf(false) }
    var italic by remember { mutableStateOf(false) }
    var fontSize by remember { mutableFloatStateOf(18f) }
    var message by remember { mutableStateOf<String?>(null) }

    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { content.text = openText(uri); title = uri.lastPathSegment?.substringAfterLast('/') ?: "Document" }
                .onFailure { message = "Unable to open this file" }
        }
    }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) runCatching { saveText(uri, content.text); message = "Document saved" }
            .onFailure { message = "Unable to save document" }
    }
    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) runCatching { exportPdf(uri, content.text); message = "PDF exported" }
            .onFailure { message = "PDF export failed" }
    }

    MaterialTheme(colorScheme = if (darkMode) darkColorScheme() else lightColorScheme()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("WordEX", fontWeight = FontWeight.Bold)
                            Text(title, style = MaterialTheme.typography.labelSmall)
                        }
                    },
                    actions = {
                        IconButton(onClick = { darkMode = !darkMode }) {
                            Icon(if (darkMode) Icons.Default.LightMode else Icons.Default.DarkMode, "Toggle theme")
                        }
                        IconButton(onClick = { openLauncher.launch(arrayOf("text/plain")) }) {
                            Icon(Icons.Default.FolderOpen, "Open")
                        }
                        IconButton(onClick = { saveLauncher.launch("WordEX.txt") }) {
                            Icon(Icons.Default.Save, "Save")
                        }
                    }
                )
            },
            bottomBar = {
                Surface(tonalElevation = 3.dp) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("${content.text.length} characters", style = MaterialTheme.typography.labelSmall)
                        TextButton(onClick = { pdfLauncher.launch("WordEX.pdf") }) {
                            Icon(Icons.Default.PictureAsPdf, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Export PDF")
                        }
                    }
                }
            },
            snackbarHost = { SnackbarHost(remember { SnackbarHostState() }) }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    IconButton(onClick = { bold = !bold }) {
                        Icon(Icons.Default.FormatBold, "Bold", tint = if (bold) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { italic = !italic }) {
                        Icon(Icons.Default.FormatItalic, "Italic", tint = if (italic) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { fontSize = (fontSize - 1f).coerceAtLeast(12f) }) { Icon(Icons.Default.Remove, "Decrease font size") }
                    Text("${fontSize.toInt()} sp", style = MaterialTheme.typography.labelMedium)
                    IconButton(onClick = { fontSize = (fontSize + 1f).coerceAtMost(32f) }) { Icon(Icons.Default.Add, "Increase font size") }
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Default.Edit, contentDescription = "Editing")
                }
                Box(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp)) {
                    if (content.text.isEmpty()) {
                        Text("Start typing your document…", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = fontSize.sp)
                    }
                    BasicTextField(
                        value = content,
                        onValueChange = { content = it },
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        textStyle = TextStyle(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = fontSize.sp,
                            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                            fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
                            lineHeight = (fontSize * 1.55f).sp
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }
    }
}
