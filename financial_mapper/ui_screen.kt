package com.example.financify.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.example.financify.Data.Models.AnalysisResult
import com.example.financify.Data.Models.RatioItem
import com.example.financify.Data.Models.YearData
import com.example.financify.Presentation.ViewModels.FinancialViewModel
import com.google.gson.GsonBuilder
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import kotlin.math.abs

@Composable
fun Scan(navController: NavController) {
    val viewModel: FinancialViewModel = hiltViewModel()
    val state by viewModel.financialData.collectAsState()
    val context = LocalContext.current

    var selectedFileUri by remember { mutableStateOf<Uri?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var showRawJson by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        selectedFileUri = uri
        errorMessage = null
        if (uri != null) {
            Log.d("FilePicker", "File selected: ${getFileName(context, uri)}")
        } else {
            Log.d("FilePicker", "No file selected")
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            Button(
                onClick = {
                    launcher.launch(
                        arrayOf(
                            "text/csv",
                            "application/json",
                            "application/vnd.ms-excel",
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("📁 Pick File")
            }
        }

        item { Spacer(modifier = Modifier.height(12.dp)) }

        selectedFileUri?.let {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        "✓ Selected: ${getFileName(context, it)}",
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }

        item {
            Button(
                onClick = {
                    selectedFileUri?.let { uri ->
                        isLoading = true
                        errorMessage = null

                        val multipart = uriToMultipart(context, uri)
                        if (multipart == null) {
                            errorMessage = "Failed to process file"
                            isLoading = false
                            return@Button
                        }

                        viewModel.uploadFile(multipart)
                        isLoading = false
                    } ?: run {
                        Toast.makeText(context, "Please select a file", Toast.LENGTH_SHORT).show()
                    }
                },
                enabled = selectedFileUri != null && !isLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isLoading) "⏳ Uploading..." else "📤 Upload")
            }
        }

        item { Spacer(modifier = Modifier.height(20.dp)) }

        errorMessage?.let {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFFFEBEE)
                    )
                ) {
                    Text(
                        "❌ $it",
                        color = Color.Red,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }

        // Toggle raw JSON viewer
        item {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text("Show Raw JSON", modifier = Modifier.weight(1f))
                Switch(checked = showRawJson, onCheckedChange = { showRawJson = it })
            }
        }

        item { Spacer(modifier = Modifier.height(12.dp)) }

        state?.let { result ->
            if (showRawJson) {
                item {
                    val pretty = GsonBuilder().setPrettyPrinting().create().toJson(result)
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(pretty, modifier = Modifier.padding(12.dp), fontSize = 8.sp)
                    }
                }
            }

            when (result) {
                is AnalysisResult.SingleYear -> {
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    "✓ Single Year Analysis",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                result.extracted_values.forEach { (k, v) ->
                                    Row(modifier = Modifier.fillMaxWidth()) {
                                        Text(k, modifier = Modifier.weight(1f))
                                        Text(
                                            formatNullableDouble(v),
                                            modifier = Modifier.weight(1f),
                                            textAlign = TextAlign.End
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                is AnalysisResult.MultiYear -> {
                    val years = result.years.sortedDescending()
                    Log.d("Scan", "MultiYear response years: ${result.years}")
                    if (years.size >= 2) {
                        val year1 = years[0]
                        val year2 = years[1]
                        val y1 = result.multi_year_data[year1]
                        val y2 = result.multi_year_data[year2]

                        Log.d("Scan", "Using years: $year1 and $year2")
                        Log.d("Scan", "Year1 categories: ${y1?.ratios?.keys}")
                        Log.d("Scan", "Year2 categories: ${y2?.ratios?.keys}")

                        if (y1 != null && y2 != null) {
                            item {
                                Text(
                                    "✓ Multi-Year Analysis: ${result.years.joinToString(", ")}",
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }

                            // Balance Sheet with Hierarchical View + Forecasts
                            item { SectionHeader("Balance Sheet Analysis (with 2-Year Forecast)") }

                            item { HeaderRowExtended(year1, year2) }

                            // Helper data class to organize balance sheet items hierarchically
                            data class BSItem(
                                val name: String,
                                val value1: Double?,
                                val value2: Double?,
                                val isHeader: Boolean = false,
                                val indentLevel: Int = 0
                            )

                            // Build hierarchical structure from extracted values
                            fun buildBalanceSheetItems(extracted: Map<String, Double>): List<BSItem> {
                                val items = mutableListOf<BSItem>()

                                // I. EQUITY AND LIABILITIES
                                items.add(BSItem("I. EQUITY AND LIABILITIES", null, null, isHeader = true))

                                // 1. Shareholders' Funds
                                val shareCapital = extracted["Share Capital"]
                                val reserves = extracted["Reserves & Surplus"] ?: extracted["Reserves \u0026 Surplus"]

                                items.add(BSItem("1. Shareholders' Funds", shareCapital, shareCapital, isHeader = true, indentLevel = 1))
                                items.add(BSItem("a) Share Capital", shareCapital, shareCapital, indentLevel = 2))
                                items.add(BSItem("b) Reserves and Surplus", reserves, reserves, indentLevel = 2))

                                // 3. Non-Current Liabilities
                                val ltBorrowings = extracted["Long-term Borrowings"]
                                val ltLiabilities = extracted["Long-term Liabilities"]
                                val ltProvisions = extracted["Long-term Provisions"]
                                val totalNCL = (ltBorrowings ?: 0.0) + (ltLiabilities ?: 0.0) + (ltProvisions ?: 0.0)

                                items.add(BSItem("3. Non-Current Liabilities", totalNCL, totalNCL, isHeader = true, indentLevel = 1))
                                items.add(BSItem("a) Long-term Borrowings", ltBorrowings, ltBorrowings, indentLevel = 2))
                                items.add(BSItem("b) Long-term Liabilities", ltLiabilities, ltLiabilities, indentLevel = 2))
                                items.add(BSItem("c) Long-term Provisions", ltProvisions, ltProvisions, indentLevel = 2))

                                // 4. Current Liabilities
                                val stBorrowings = extracted["Short-term Borrowings"]
                                val tradePayables = extracted["Trade Payables"]
                                val currentLiabilities = extracted["Current Liabilities"]

                                items.add(BSItem("4. Current Liabilities", currentLiabilities, currentLiabilities, isHeader = true, indentLevel = 1))
                                items.add(BSItem("a) Short-term Borrowings", stBorrowings, stBorrowings, indentLevel = 2))
                                items.add(BSItem("b) Trade Payables", tradePayables, tradePayables, indentLevel = 2))

                                // Total Liabilities
                                val totalLiabilities = extracted["Total Liabilities"]
                                items.add(BSItem("Total Liabilities", totalLiabilities, totalLiabilities, isHeader = true, indentLevel = 1))

                                // II. ASSETS
                                items.add(BSItem("II. ASSETS", null, null, isHeader = true))

                                // 1. Non-Current Assets
                                val tangibleAssets = extracted["Tangible Assets"]
                                val intangibleAssets = extracted["Intangible Assets"]
                                val totalNCA = (tangibleAssets ?: 0.0) + (intangibleAssets ?: 0.0)

                                items.add(BSItem("1. Non-Current Assets", totalNCA, totalNCA, isHeader = true, indentLevel = 1))
                                items.add(BSItem("a) Tangible Assets", tangibleAssets, tangibleAssets, indentLevel = 2))
                                items.add(BSItem("b) Intangible Assets", intangibleAssets, intangibleAssets, indentLevel = 2))

                                // 2. Current Assets
                                val inventory = extracted["Inventory"] ?: extracted["Closing Inventory"]
                                val tradeReceivables = extracted["Trade Receivables"]
                                val cash = extracted["Cash and Cash Equivalents"]
                                val currentAssets = extracted["Current Assets"]

                                items.add(BSItem("2. Current Assets", currentAssets, currentAssets, isHeader = true, indentLevel = 1))
                                items.add(BSItem("a) Inventory", inventory, inventory, indentLevel = 2))
                                items.add(BSItem("b) Trade Receivables", tradeReceivables, tradeReceivables, indentLevel = 2))
                                items.add(BSItem("c) Cash and Cash Equivalents", cash, cash, indentLevel = 2))

                                // Total Assets
                                val totalAssets = extracted["Total Assets"]
                                items.add(BSItem("Total Assets", totalAssets, totalAssets, isHeader = true, indentLevel = 1))

                                return items
                            }

                            val bsItemsY1 = buildBalanceSheetItems(y1.extracted_values)
                            val bsItemsY2 = buildBalanceSheetItems(y2.extracted_values)

                            // Render balance sheet
                            bsItemsY1.indices.forEach { idx ->
                                val itemY1 = bsItemsY1[idx]
                                val itemY2 = if (idx < bsItemsY2.size) bsItemsY2[idx] else itemY1

                                item {
                                    BalanceSheetRow(
                                        label = itemY1.name,
                                        v1 = itemY1.value1,
                                        v2 = itemY2.value1,
                                        isHeader = itemY1.isHeader,
                                        indentLevel = itemY1.indentLevel
                                    )
                                }
                            }

                            item { Spacer(modifier = Modifier.height(20.dp)) }

                            // Ratio Analysis
                            item { SectionHeader("Ratio Analysis") }

                            // Robust category ordering and matching
                            fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

                            val allCategoryKeys = (y1.ratios.keys + y2.ratios.keys).distinct()
                            val canonicalOrder = listOf("liquidity", "profitability", "leverage", "efficiency", "coverage")

                            val matched = mutableSetOf<String>()
                            val orderedCategories = mutableListOf<String>()

                            // First, try to match canonical names (contains)
                            for (canon in canonicalOrder) {
                                val matches = allCategoryKeys.filter { k -> norm(k).contains(canon) }
                                if (matches.isNotEmpty()) {
                                    matches.sorted().forEach {
                                        if (orderedCategories.add(it)) matched.add(it)
                                    }
                                }
                            }

                            // Then include any categories that approximately match canonical keywords (startsWith/contains parts)
                            for (canon in canonicalOrder) {
                                if (orderedCategories.any { norm(it).contains(canon) }) continue
                                val matches = allCategoryKeys.filter { k ->
                                    val nk = norm(k)
                                    // match by token overlap
                                    canon.split(Regex("\\s+")).any { token -> nk.contains(token) }
                                }
                                matches.sorted().forEach {
                                    if (!matched.contains(it)) { orderedCategories.add(it); matched.add(it) }
                                }
                            }

                            // Finally add any remaining categories (sorted)
                            allCategoryKeys.sorted().forEach { k ->
                                if (!matched.contains(k)) orderedCategories.add(k)
                            }

                            // Iterate orderedCategories to render
                            orderedCategories.forEach { category ->
                                val cat1 = y1.ratios[category] ?: emptyMap()
                                val cat2 = y2.ratios[category] ?: emptyMap()

                                item {
                                    Text(
                                        text = category,
                                        style = MaterialTheme.typography.labelLarge,
                                        modifier = Modifier.padding(vertical = 8.dp)
                                    )
                                }

                                val ratioNames = (cat1.keys + cat2.keys).distinct().sorted()

                                if (ratioNames.isEmpty()) {
                                    item {
                                        Text(
                                            text = "No ratios available for this category",
                                            color = Color.Gray,
                                            modifier = Modifier.padding(8.dp)
                                        )
                                    }
                                } else {
                                    ratioNames.forEach { name ->
                                        item {
                                            // compute missing values using formula + extracted_values, forcing compute when formula present
                                            val computedR1 = computeRatioIfMissing(cat1[name], cat1[name]?.formula, y1.extracted_values)
                                            val computedR2 = computeRatioIfMissing(cat2[name], cat2[name]?.formula, y2.extracted_values)

                                            val finalR1 = cat1[name] ?: computedR1
                                            val finalR2 = cat2[name] ?: computedR2

                                            RatioComparisonRow(
                                                name = name,
                                                r1 = finalR1,
                                                r2 = finalR2
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            item {
                                Text(
                                    "Data missing for selected years",
                                    color = Color.Red,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                        }
                    } else {
                        item {
                            Text(
                                "Not enough years for multi-year comparison",
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }

                is AnalysisResult.Error -> {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0xFFFFEBEE)
                            )
                        ) {
                            Text(
                                "❌ ${result.message}",
                                color = Color.Red,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HeaderRow(year1: String, year2: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(8.dp)
    ) {
        Text("Financial Item", Modifier.weight(1.5f), fontWeight = FontWeight.Bold)
        Text(year1.take(10), Modifier.weight(1f), fontWeight = FontWeight.Bold)
        Text(year2.take(10), Modifier.weight(1f), fontWeight = FontWeight.Bold)
        Text("% Chg", Modifier.weight(0.8f), fontWeight = FontWeight.Bold)
    }
}

@Composable
fun HeaderRowExtended(year1: String, year2: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(8.dp)
    ) {
        Text("Financial Item", Modifier.weight(2f), fontWeight = FontWeight.Bold, fontSize = 11.sp)
        Text(year1.take(10), Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 10.sp, textAlign = TextAlign.End)
        Text(year2.take(10), Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 10.sp, textAlign = TextAlign.End)
        Text("% Chg", Modifier.weight(0.8f), fontWeight = FontWeight.Bold, fontSize = 10.sp, textAlign = TextAlign.End)
        Text("est 2027", Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 10.sp, textAlign = TextAlign.End)
        Text("est 2028", Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 10.sp, textAlign = TextAlign.End)
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 12.dp)
    )
}

@SuppressLint("DefaultLocale")
@Composable
fun BalanceSheetRow(
    label: String,
    v1: Double?,
    v2: Double?,
    isHeader: Boolean = false,
    indentLevel: Int = 0
) {
    // Calculate percentage change and compound growth forecasts
    // v1 is the most recent year (e.g., 2025), v2 is the previous year (e.g., 2024)
    val change: Double? = if (v1 != null && v2 != null && v2 != 0.0) {
        ((v1 - v2) / v2) * 100
    } else null

    // Calculate forecast using compound growth: est_year = current_year × (1 + change_percentage/100)
    val (est2027, est2028) = if (v1 != null && change != null && v2 != null && v2 != 0.0) {
        val growthRate = 1.0 + (change / 100.0)
        val est2027Value = v1 * growthRate
        val est2028Value = est2027Value * growthRate
        Pair(est2027Value, est2028Value)
    } else if (v1 != null) {
        Pair(v1, v1)
    } else {
        Pair(null, null)
    }

    val bgColor = if (isHeader) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent
    val textWeight = if (isHeader) FontWeight.Bold else FontWeight.Normal

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .padding(vertical = 4.dp, horizontal = 8.dp)
    ) {
        val indent = when (indentLevel) {
            1 -> "  "
            2 -> "    "
            else -> ""
        }
        Text(
            "$indent$label",
            Modifier.weight(2f),
            fontWeight = textWeight,
            fontSize = 11.sp
        )
        Text(
            formatNullableDouble(v2),
            Modifier.weight(1f),
            fontWeight = textWeight,
            textAlign = TextAlign.End,
            fontSize = 10.sp
        )
        Text(
            formatNullableDouble(v1),
            Modifier.weight(1f),
            fontWeight = textWeight,
            textAlign = TextAlign.End,
            fontSize = 10.sp
        )
        Text(
            when {
                change != null -> String.format("%+.1f%%", change)
                v1 != null && v2 != null && v2 == 0.0 -> String.format("%+.2f", (v1 - v2))
                else -> "-"
            },
            Modifier.weight(0.8f),
            fontWeight = textWeight,
            color = if (change != null && change >= 0) Color(0xFF4CAF50) else if (change != null) Color(0xFFF44336) else Color.Unspecified,
            textAlign = TextAlign.End,
            fontSize = 10.sp
        )
        Text(
            formatNullableDouble(est2027),
            Modifier.weight(1f),
            fontWeight = textWeight,
            textAlign = TextAlign.End,
            fontSize = 10.sp,
            color = Color(0xFF2196F3)
        )
        Text(
            formatNullableDouble(est2028),
            Modifier.weight(1f),
            fontWeight = textWeight,
            textAlign = TextAlign.End,
            fontSize = 10.sp,
            color = Color(0xFF2196F3)
        )
    }

    if (isHeader) Divider()
}

@SuppressLint("DefaultLocale")
@Composable
fun FinancialRowNullable(label: String, v1: Double?, v2: Double?) {
    val change: Double? = if (v1 != null && v1 != 0.0 && v2 != null) {
        ((v2 - v1) / v1) * 100
    } else null

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Text(label, Modifier.weight(1.5f))
        Text(formatNullableDouble(v1), Modifier.weight(1f))
        Text(formatNullableDouble(v2), Modifier.weight(1f))
        Text(
            when {
                change != null -> String.format("%+.1f%%", change)
                v1 != null && v2 != null && v1 == 0.0 -> String.format("%+.2f", (v2 - v1))
                else -> "-"
            },
            Modifier.weight(0.8f),
            color = if (change != null && change >= 0)
                Color(0xFF4CAF50)
            else if (change != null)
                Color(0xFFF44336)
            else Color.Unspecified
        )
    }

    Divider()
}

@SuppressLint("DefaultLocale")
@Composable
fun RatioComparisonRow(
    name: String,
    r1: RatioItem?,
    r2: RatioItem?
) {

    fun format(item: RatioItem?): String {
        item ?: return "-"
        return when {
            item.percentage != null -> String.format("%.1f%%", item.percentage)
            item.days != null -> String.format("%.0f d", item.days)
            item.value != null -> String.format("%.2f x", item.value)
            else -> "-"
        }
    }

    Column(modifier = Modifier.padding(vertical = 8.dp)) {

        Row(modifier = Modifier.fillMaxWidth()) {
            Text(name, Modifier.weight(1.5f))
            Text(format(r1), Modifier.weight(1f), textAlign = TextAlign.End)
            Text(format(r2), Modifier.weight(1f), textAlign = TextAlign.End)
            Spacer(Modifier.weight(0.8f))
        }

        r1?.formula?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray,
                fontSize = 10.sp,
                modifier = Modifier.padding(start = 8.dp, top = 4.dp)
            )
        }

        Divider(modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
fun SingleYearView(data: Map<String, YearData>) {
    LazyColumn {
        data.forEach { (year, yearData) ->
            item {
                Text(
                    text = "Year: $year",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 16.dp)
                )

                yearData.extracted_values.forEach { (k, v) ->
                    Text("$k: ${String.format("%,.2f", v)}")
                }
            }
        }
    }
}

fun uriToMultipart(context: Context, uri: Uri): MultipartBody.Part? {
    return try {
        val fileName = getFileName(context, uri)
        Log.d("FileConvert", "Converting: $fileName")

        val file = File(context.cacheDir, fileName)

        val inputStream = context.contentResolver.openInputStream(uri)
            ?: run {
                Log.e("FileConvert", "Cannot open input stream")
                return null
            }

        inputStream.use { input ->
            file.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        Log.d("FileConvert", "File copied: ${file.length()} bytes")

        val mimeType = context.contentResolver.getType(uri)
            ?: when {
                fileName.endsWith(".csv", ignoreCase = true) -> "text/csv"
                fileName.endsWith(".json", ignoreCase = true) -> "application/json"
                fileName.endsWith(".xlsx", ignoreCase = true) -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                fileName.endsWith(".xls", ignoreCase = true) -> "application/vnd.ms-excel"
                else -> "application/octet-stream"
            }

        Log.d("FileConvert", "MIME type: $mimeType")

        val requestBody = file.asRequestBody(mimeType.toMediaTypeOrNull())
        MultipartBody.Part.createFormData("file", file.name, requestBody)

    } catch (e: Exception) {
        Log.e("FileConvert", "Error: ${e.message}", e)
        null
    }
}

fun getFileName(context: Context, uri: Uri): String {
    return try {
        var name = "file"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1) {
                    name = cursor.getString(index)
                }
            }
        }
        name
    } catch (e: Exception) {
        Log.e("GetFileName", "Error: ${e.message}")
        "file"
    }
}

fun formatNullableDouble(v: Double?): String {
    return v?.let { String.format("%,.2f", it) } ?: "-"
}

/*
 * Formula evaluation helpers + derived-field heuristics
 */

private fun computeRatioIfMissing(item: RatioItem?, formula: String?, extracted: Map<String, Double>): RatioItem? {
    if (item != null && (item.value != null || item.percentage != null || item.days != null)) return item
    if (formula.isNullOrBlank()) return null

    return try {
        val cleaned = formula.replace("\u00D7", "*") // ×
            .replace("×", "*")
            .replace("–", "-")
            .replace("\u2013", "-")
            .replace("\u2212", "-")
        val (resultValue, isPercent) = evaluateFormulaWithLookup(cleaned, extracted)
        when {
            resultValue == null -> null
            isPercent -> RatioItem(value = null, percentage = resultValue, days = null, formula = formula)
            else -> RatioItem(value = resultValue, percentage = null, days = null, formula = formula)
        }
    } catch (e: Exception) {
        Log.d("ComputeRatio", "Failed to compute for formula='$formula' : ${e.message}")
        null
    }
}

/**
 * Replace named fields in `expr` by looking them up in `extracted` and evaluate the numeric expression.
 * Uses derived-field heuristics when direct tokens are missing (Revenue, Net Profit, EBITDA, Equity, Total Debt, Working Capital, Receivables, Payables, Fixed Assets).
 * Returns Pair(value, isPercentageFlag).
 */
private fun evaluateFormulaWithLookup(expr: String, extracted: Map<String, Double>): Pair<Double?, Boolean> {
    val isPercent = expr.contains(Regex("\\*\\s*100")) || expr.contains("× 100") || expr.contains("x 100", ignoreCase = true)

    // operators to split tokens
    val operators = setOf('+', '-', '*', '/', '(', ')')

    val sb = StringBuilder()
    var token = StringBuilder()
    fun flushToken() {
        val t = token.toString().trim()
        if (t.isNotEmpty()) {
            val num = t.toDoubleOrNull()
            if (num != null) {
                sb.append(num.toString())
            } else {
                // lookup numeric value (direct or derived)
                val v = findValueForNameWithDerived(t, extracted)
                if (v != null) {
                    sb.append(v.toString())
                } else {
                    sb.append("0") // keep expression valid; later we check whether lookup happened
                }
            }
            token = StringBuilder()
        }
    }

    for (ch in expr) {
        if (operators.contains(ch)) {
            flushToken()
            sb.append(ch)
        } else {
            token.append(ch)
        }
    }
    flushToken()
    val exprReplaced = sb.toString()

    // determine if any meaningful tokens were present
    var foundMatch = false
    for (k in extracted.keys) {
        if (expr.contains(k, ignoreCase = true) || expr.contains(normalize(k))) {
            foundMatch = true
            break
        }
    }

    val value = evaluateSimpleExpression(exprReplaced)
    if (!foundMatch && (value == 0.0 || value == null)) {
        return Pair(null, isPercent)
    }

    return Pair(value, isPercent)
}

/** Normalize a string for matching */
private fun normalize(s: String): String {
    return s.lowercase().replace(Regex("[^a-z0-9]"), "")
}

/** Try direct lookup then derived heuristics */
private fun findValueForNameWithDerived(name: String, extracted: Map<String, Double>): Double? {
    // direct and fuzzy lookup first
    val direct = findValueForName(name, extracted)
    if (direct != null) return direct

    // normalized key to check derived patterns
    val n = normalize(name)
    // Revenue
    if (listOf("revenue", "totalincome", "net sales", "netsales", "sales").any { n.contains(normalize(it)) }) {
        // prefer Total Income, Net Sales, or sum
        val candidates = listOf("Total Income", "Net Sales", "Sales", "Revenue", "Net Sales & Other Income", "Total revenue")
        candidates.forEach { c -> findValueForName(c, extracted)?.let { return it } }
        // try sum of net sales + other operating income
        val s1 = findValueForName("Net Sales", extracted)
        val s2 = findValueForName("Other Operating Income", extracted)
        if (s1 != null || s2 != null) return (s1 ?: 0.0) + (s2 ?: 0.0)
    }

    // Net Profit
    if (n.contains("netprofit") || n.contains("net profit") || n.contains("profitmargin") || n.contains("netprofitmargin")) {
        findValueForName("Net Profit", extracted)?.let { return it }
        // Try: Total Income - Total Expenses - Tax - Interest
        val totIncome = findValueForName("Total Income", extracted)
        val totExp = findValueForName("Total Expenses", extracted)
        val tax = findValueForName("Tax", extracted)
        val interest = findValueForName("Interest", extracted)
        if (totIncome != null && totExp != null) {
            var candidate = totIncome - totExp
            if (tax != null) candidate -= tax
            if (interest != null) candidate -= interest
            return candidate
        }
    }

    // EBITDA
    if (n.contains("ebitda")) {
        findValueForName("EBITDA", extracted)?.let { return it }
        // approximate: Net Profit + Interest + Tax (if available)
        val net = findValueForName("Net Profit", extracted)
        val interest = findValueForName("Interest", extracted)
        val tax = findValueForName("Tax", extracted)
        if (net != null) {
            var candidate = net
            if (interest != null) candidate += interest
            if (tax != null) candidate += tax
            return candidate
        }
    }

    // Equity
    if (n.contains("equity") && !n.contains("equityratio")) {
        findValueForName("Equity", extracted)?.let { return it }
        val ta = findValueForName("Total Assets", extracted)
        val tl = findValueForName("Total Liabilities", extracted)
        if (ta != null && tl != null) return ta - tl
    }

    // Total Debt
    if (n.contains("totaldebt") || n.contains("debt")) {
        findValueForName("Total Debt", extracted)?.let { return it }
        val lt = findValueForName("Long-term Borrowings", extracted)
        val st = findValueForName("Short-term Borrowings", extracted) ?: findValueForName("Current Borrowings", extracted)
        if (lt != null || st != null) return (lt ?: 0.0) + (st ?: 0.0)
    }

    // Working Capital
    if (n.contains("workingcapital")) {
        val ca = findValueForName("Current Assets", extracted)
        val cl = findValueForName("Current Liabilities", extracted)
        if (ca != null && cl != null) return ca - cl
    }

    // Receivables / Payables / Inventory / Fixed Assets
    if (n.contains("receivables")) {
        findValueForName("Trade Receivables", extracted) ?: findValueForName("Receivables", extracted)
    }
    if (n.contains("payables")) {
        findValueForName("Trade Payables", extracted) ?: findValueForName("Payables", extracted)
    }
    if (n.contains("inventory") || n.contains("dio")) {
        findValueForName("Inventory", extracted) ?: findValueForName("Closing Inventory", extracted)
    }
    if (n.contains("fixedasset") || n.contains("tangible")) {
        findValueForName("Tangible Assets", extracted) ?: findValueForName("Fixed Assets", extracted)
    }

    return null
}

/** Attempt to find a matching numeric value from extracted map for a token/name */
private fun findValueForName(name: String, extracted: Map<String, Double>): Double? {
    val n = normalize(name)
    // exact match
    extracted.forEach { (k, v) ->
        if (normalize(k) == n) return v
    }
    // direct contains / startsWith match
    extracted.forEach { (k, v) ->
        val nk = normalize(k)
        if (nk.contains(n) || n.contains(nk) || nk.startsWith(n) || n.startsWith(nk)) return v
    }
    // split tokens match
    val parts = n.split(Regex("\\s+"))
    extracted.forEach { (k, v) ->
        val nk = normalize(k)
        for (p in parts) {
            if (p.isNotBlank() && nk.contains(p)) return v
        }
    }
    return null
}

/** Very small expression evaluator supporting + - * / and parentheses. Returns null on error. */
private fun evaluateSimpleExpression(expr: String): Double? {
    return try {
        val parser = ExprParser(expr)
        parser.parseExpression()
    } catch (e: Exception) {
        Log.d("ExprEval", "Failed to eval '$expr' : ${e.message}")
        null
    }
}

/* Simple recursive-descent parser for arithmetic */
private class ExprParser(val s: String) {
    private var i = 0
    private val n = s.length

    private fun peek(): Char? = if (i < n) s[i] else null
    private fun eat(): Char = s[i++]

    fun parseExpression(): Double {
        val v = parseTerm()
        skipWhitespace()
        var result = v
        while (true) {
            skipWhitespace()
            val p = peek() ?: break
            if (p == '+' || p == '-') {
                eat()
                val rhs = parseTerm()
                result = if (p == '+') result + rhs else result - rhs
            } else break
        }
        return result
    }

    private fun parseTerm(): Double {
        skipWhitespace()
        var v = parseFactor()
        while (true) {
            skipWhitespace()
            val p = peek() ?: break
            if (p == '*' || p == '/') {
                eat()
                val rhs = parseFactor()
                v = if (p == '*') v * rhs else if (rhs == 0.0) Double.POSITIVE_INFINITY else v / rhs
            } else break
        }
        return v
    }

    private fun parseFactor(): Double {
        skipWhitespace()
        val p = peek() ?: throw IllegalArgumentException("Unexpected end")
        return when {
            p == '(' -> {
                eat()
                val v = parseExpression()
                skipWhitespace()
                if (peek() == ')') eat() else throw IllegalArgumentException("Missing )")
                v
            }
            p == '+' -> {
                eat()
                parseFactor()
            }
            p == '-' -> {
                eat()
                -parseFactor()
            }
            else -> parseNumber()
        }
    }

    private fun parseNumber(): Double {
        skipWhitespace()
        val start = i
        var dotSeen = false
        while (peek()?.let { it.isDigit() || (it == '.' && !dotSeen) } == true) {
            if (peek() == '.') dotSeen = true
            eat()
        }
        val token = s.substring(start, i)
        return token.toDoubleOrNull() ?: throw IllegalArgumentException("Invalid number '$token'")
    }

    private fun skipWhitespace() {
        while (peek()?.isWhitespace() == true) eat()
    }
}
