package com.ff6locke.wordex

import android.database.Cursor
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry

private enum class DocumentFormat(val extension: String, val mime: String, val label: String) {
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "Word document (.docx)"),
    RTF("rtf", "application/rtf", "Rich Text (.rtf)"),
    HTML("html", "text/html", "Web page (.html)"),
    TXT("txt", "text/plain", "Plain text (.txt)"),
    MD("md", "text/markdown", "Markdown (.md)")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WordExApp(
                getDisplayName = { uri -> queryDisplayName(uri) },
                openText = { uri, name -> readDocument(uri, name) },
                saveText = { uri, text, format -> writeDocument(uri, text, format) },
                exportPdf = { uri, text -> writePdf(uri, text) }
            )
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        var name = uri.lastPathSegment ?: "Document"
        val cursor: Cursor? = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        cursor?.use { if (it.moveToFirst()) name = it.getString(0) ?: name }
        return name
    }

    private fun readDocument(uri: Uri, displayName: String): String {
        val extension = displayName.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "docx" -> contentResolver.openInputStream(uri)?.use { input ->
                val zip = ZipInputStream(input)
                var xml = ""
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == "word/document.xml") {
                        xml = zip.bufferedReader(Charsets.UTF_8).readText()
                        break
                    }
                }
                extractDocxText(xml)
            } ?: ""
            "doc" -> throw IllegalArgumentException("Legacy .doc files are not supported yet. Please save as .docx in Word.")
            "rtf" -> contentResolver.openInputStream(uri)?.use { decodeRtf(it.bufferedReader().readText()) } ?: ""
            "html", "htm" -> contentResolver.openInputStream(uri)?.use { decodeHtml(it.bufferedReader().readText()) } ?: ""
            "txt", "md", "csv", "log" -> contentResolver.openInputStream(uri)?.use {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText()
            } ?: ""
            else -> {
                val mime = contentResolver.getType(uri).orEmpty()
                if (mime.startsWith("text/")) contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() } ?: ""
                else throw IllegalArgumentException("This file type is not supported yet.")
            }
        }
    }

    private fun writeDocument(uri: Uri, text: String, format: DocumentFormat) {
        contentResolver.openOutputStream(uri)?.use { output ->
            when (format) {
                DocumentFormat.DOCX -> ZipOutputStream(output).use { zip ->
                    addZipEntry(zip, "[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""")
                    addZipEntry(zip, "_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""")
                    val paragraphs = text.split("\n").joinToString("") { line ->
                        "<w:p><w:r><w:t xml:space=\"preserve\">${escapeXml(line)}</w:t></w:r></w:p>"
                    }
                    val document = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$paragraphs<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr></w:body></w:document>"""
                    addZipEntry(zip, "word/document.xml", document)
                }
                DocumentFormat.RTF -> output.write(encodeRtf(text).toByteArray(Charsets.UTF_8))
                DocumentFormat.HTML -> output.write(("<!doctype html><html><head><meta charset=\"utf-8\"><title>WordEX document</title></head><body><pre>" +
                    escapeHtml(text) + "</pre></body></html>").toByteArray(Charsets.UTF_8))
                DocumentFormat.TXT, DocumentFormat.MD -> output.write(text.toByteArray(Charsets.UTF_8))
            }
        } ?: throw IllegalStateException("Unable to create output file")
    }

    private fun addZipEntry(zip: ZipOutputStream, name: String, value: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(value.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun extractDocxText(xml: String): String {
        if (xml.isBlank()) return ""
        return Regex("<w:p(?:\\s[^>]*)?>(.*?)</w:p>", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).joinToString("\n") { paragraph ->
                Regex("<w:t(?:\\s[^>]*)?>(.*?)</w:t>", RegexOption.DOT_MATCHES_ALL)
                    .findAll(paragraph.groupValues[1])
                    .joinToString("") { unescapeXml(it.groupValues[1]) }
            }
    }

    private fun escapeXml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    private fun unescapeXml(value: String) = value.replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    private fun escapeHtml(value: String) = escapeXml(value).replace("\n", "<br>")

    private fun decodeHtml(html: String): String {
        val withBreaks = html.replace(Regex("(?i)<\\s*br\\s*/?>"), "\n")
            .replace(Regex("(?i)</\\s*(p|div|h[1-6]|li)\\s*>"), "\n")
        return unescapeXml(withBreaks.replace(Regex("<[^>]*>"), ""))
            .replace("&nbsp;", " ").replace("&#39;", "'").trim()
    }

    private fun decodeRtf(rtf: String): String {
        return rtf.replace(Regex("(?s)^\\s*\\{\\\\rtf[0-9]*"), "")
            .replace(Regex("\\\\'([0-9a-fA-F]{2})")) { it.groupValues[1].toInt(16).toChar().toString() }
            .replace(Regex("\\\\par[d]?\\s?"), "\n")
            .replace(Regex("\\\\tab\\s?"), "\t")
            .replace(Regex("\\\\[a-zA-Z]+-?[0-9]* ?"), "")
            .replace(Regex("[{}]"), "").replace("\\~", " ").trim()
    }

    private fun encodeRtf(text: String): String {
        val body = text.map { ch ->
            when (ch) {
                '\\' -> "\\\\"
                '{' -> "\\{"
                '}' -> "\\}"
                '\n' -> "\\par\n"
                else -> if (ch.code > 127) "\\u${ch.code}?" else ch.toString()
            }
        }.joinToString("")
        return "{\\rtf1\\ansi\\deff0 {\\fonttbl {\\f0 Arial;}}\\f0\\fs24 $body}"
    }

    private fun writePdf(uri: Uri, text: String) {
        val pdf = PdfDocument()
        val paint = Paint().apply { textSize = 12f; color = android.graphics.Color.BLACK }
        val pageWidth = 595
        val pageHeight = 842
        val margin = 48f
        val lines = text.lines().flatMap { line -> if (line.isEmpty()) listOf("") else line.chunked(85) }
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
            y += 18f
        }
        pdf.finishPage(page)
        contentResolver.openOutputStream(uri)?.use { pdf.writeTo(it) }
        pdf.close()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WordExApp(
    getDisplayName: (Uri) -> String,
    openText: (Uri, String) -> String,
    saveText: (Uri, String, DocumentFormat) -> Unit,
    exportPdf: (Uri, String) -> Unit
) {
    var title by remember { mutableStateOf("Untitled document") }
    var content by remember { mutableStateOf(TextFieldValue("")) }
    var darkMode by remember { mutableStateOf(false) }
    var bold by remember { mutableStateOf(false) }
    var italic by remember { mutableStateOf(false) }
    var fontSize by remember { mutableFloatStateOf(18f) }
    var message by remember { mutableStateOf<String?>(null) }
    var selectedFormat by remember { mutableStateOf(DocumentFormat.DOCX) }
    var formatMenu by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let { snackbarHostState.showSnackbar(it); message = null }
    }

    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val name = getDisplayName(uri)
            content = TextFieldValue(openText(uri, name))
            title = name
            message = "Opened $name"
        }.onFailure { message = it.message ?: "Unable to open this file" }
    }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(selectedFormat.mime)) { uri ->
        if (uri != null) runCatching {
            saveText(uri, content.text, selectedFormat)
            title = "WordEX document.${selectedFormat.extension}"
            message = "Saved as ${selectedFormat.extension.uppercase()}"
        }.onFailure { message = it.message ?: "Unable to save document" }
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
                        IconButton(onClick = { openLauncher.launch(arrayOf("*/*")) }) {
                            Icon(Icons.Default.FolderOpen, "Open document")
                        }
                        Box {
                            IconButton(onClick = { formatMenu = true }) { Icon(Icons.Default.Save, "Save as") }
                            DropdownMenu(expanded = formatMenu, onDismissRequest = { formatMenu = false }) {
                                DocumentFormat.entries.forEach { format ->
                                    DropdownMenuItem(
                                        text = { Text(format.label) },
                                        leadingIcon = { if (selectedFormat == format) Icon(Icons.Default.Check, null) },
                                        onClick = {
                                            selectedFormat = format
                                            formatMenu = false
                                            saveLauncher.launch("WordEX.${format.extension}")
                                        }
                                    )
                                }
                            }
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
            snackbarHost = { SnackbarHost(snackbarHostState) }
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
                        Text("Start typing your document…", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = fontSize.sp)
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
