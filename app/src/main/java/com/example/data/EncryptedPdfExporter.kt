package com.example.data

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import com.example.physiology.PhysiologyEngine
import com.example.physiology.PhysiologyState
import com.example.physiology.SeverityLevel
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EncryptedPdfExporter(private val context: Context) {

    suspend fun exportEncryptedPdfToUri(
        destinationUri: Uri,
        state: PhysiologyState,
        passphrase: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            require(passphrase.length >= 4) { "Passphrase must be at least 4 characters." }
            val rawPdfBytes = renderClinicalPdfBytes(state)
            val encryptedBytes = encryptPdfBytes(rawPdfBytes, passphrase)
            context.contentResolver.openOutputStream(destinationUri)?.use { out ->
                out.write(encryptedBytes)
                out.flush()
            } ?: error("Unable to open destination file stream.")
            Result.success("Exported 128-bit password-encrypted clinical PDF (${encryptedBytes.size / 1024} KB).")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun encryptPdfBytes(rawPdf: ByteArray, passphrase: String): ByteArray {
        PDDocument.load(rawPdf).use { pdDoc ->
            val ap = AccessPermission().apply {
                setCanPrint(true)
                setCanExtractContent(false)
                setCanModify(false)
            }
            val spp = StandardProtectionPolicy(passphrase, passphrase, ap).apply {
                encryptionKeyLength = 128
            }
            pdDoc.protect(spp)
            val out = ByteArrayOutputStream()
            pdDoc.save(out)
            return out.toByteArray()
        }
    }

    private fun renderClinicalPdfBytes(state: PhysiologyState): ByteArray {
        val pdf = PdfDocument()
        val pageWidth = 595 // A4 points
        val pageHeight = 842

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F172A")
            textSize = 18f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val sectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0284C7")
            textSize = 12f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1E293B")
            textSize = 9.5f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        }
        val mutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#64748B")
            textSize = 8.5f
        }
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CBD5E1")
            strokeWidth = 1f
        }

        // Page 1: Biometrics, Vital Telemetry Matrix, and Trend Curve
        val page1 = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create())
        val c1 = page1.canvas
        var y = 42f
        val left = 36f
        val right = pageWidth - 36f

        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US).format(Date())
        c1.drawText("BIOCASCADE CLINICAL TELEMETRY REPORT", left, y, titlePaint)
        y += 16f
        c1.drawText("Generated: $dateStr  |  HIPAA-Aligned Encrypted Export  |  Sim Clock: T+${PhysiologyEngine.formatDuration(state.simElapsedSeconds)}", left, y, mutedPaint)
        y += 10f
        c1.drawLine(left, y, right, y, linePaint)
        y += 20f

        // Section 1: Linked Subject Biometrics
        val derived = state.profile.derivedStats()
        c1.drawText("1. SUBJECT BASELINE & LINKED PHYSIOLOGICAL CONSTANTS", left, y, sectionPaint)
        y += 15f
        c1.drawText(
            "Profile: ${state.profile.label} | Age: ${state.profile.ageYears}y | Sex: ${state.profile.biologicalSex} | Mass: ${String.format("%.1f", state.profile.weightKg)} kg | Height: ${String.format("%.0f", state.profile.heightCm)} cm",
            left, y, bodyPaint,
        )
        y += 13f
        c1.drawText(
            "BMI: ${String.format("%.1f", derived.bmi)} | BSA: ${String.format("%.2f", derived.bsaM2)} m² | Blood Vol: ${derived.totalBloodVolumeMl.toInt()} mL | TBW: ${String.format("%.1f", derived.totalBodyWaterL)} L | Base eGFR: ${String.format("%.1f", derived.baselineEgfr)} mL/min",
            left, y, bodyPaint,
        )
        y += 13f
        c1.drawText("Active Stimulus: ${state.stimulusSummary}", left, y, bodyPaint)
        y += 18f
        c1.drawLine(left, y, right, y, linePaint)
        y += 20f

        // Section 2: Quantitative Vital & Metabolic Readouts
        c1.drawText("2. REAL-TIME METRIC TELEMETRY & THRESHOLD DERIVATIVES", left, y, sectionPaint)
        y += 16f
        c1.drawText(String.format("%-18s %-12s %-11s %-13s %-14s %-9s", "PARAMETER", "VALUE", "NORMAL", "dX/dt (/min)", "THRESHOLD ETA", "STATUS"), left, y, mutedPaint)
        y += 8f
        c1.drawLine(left, y, right, y, linePaint)
        y += 14f

        state.metrics.values.forEach { m ->
            val statusColor = when (m.severity) {
                SeverityLevel.NORMAL -> Color.parseColor("#047857")
                SeverityLevel.CAUTION -> Color.parseColor("#B45309")
                SeverityLevel.CRITICAL -> Color.parseColor("#BE123C")
            }
            val rowPaint = Paint(bodyPaint).apply { color = statusColor }
            val etaStr = m.etaToCriticalSec?.let { PhysiologyEngine.formatDuration(it) } ?: "Stable"
            val valStr = "${PhysiologyEngine.formatCompact(m.value)} ${m.unit}"
            val rateStr = PhysiologyEngine.formatSigned(m.ratePerMin)
            val line = String.format(
                Locale.US,
                "%-18s %-12s %-11s %-13s %-14s %-9s",
                m.label.take(17),
                valStr.take(11),
                m.normalRange.take(10),
                rateStr.take(12),
                etaStr.take(13),
                m.severity.label,
            )
            c1.drawText(line, left, y, rowPaint)
            y += 14f
        }

        y += 12f
        c1.drawLine(left, y, right, y, linePaint)
        y += 20f

        // Section 3: Trend Sparkline Chart
        c1.drawText("3. MULTI-SYSTEM TREND TRAJECTORY (GLUCOSE, HR, MAP, K+)", left, y, sectionPaint)
        y += 12f
        drawTrendBox(c1, left, y, right, y + 140f, state, linePaint, mutedPaint)
        y += 160f

        // Section 4: Causal Chain Summary
        c1.drawText("4. MATHEMATICAL CAUSE-AND-EFFECT CASCADE", left, y, sectionPaint)
        y += 16f
        state.causalChain.take(6).forEach { node ->
            c1.drawText(
                "[#${node.stepOrder} T+${PhysiologyEngine.formatDuration(node.onsetTimeSec)}] ${node.sourceRegion.displayName} -> ${node.targetRegion.displayName}: ${node.title}",
                left, y, bodyPaint,
            )
            y += 12f
            c1.drawText("    Eq: ${node.equationSummary}  |  ${node.numericDelta}", left, y, mutedPaint)
            y += 14f
        }

        pdf.finishPage(page1)

        // Page 2: Organ-by-Organ Clinical Symptoms & Threshold Evaluation
        val page2 = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 2).create())
        val c2 = page2.canvas
        var y2 = 42f
        c2.drawText("BIOCASCADE — ANATOMICAL REGION & SYMPTOM LOG", left, y2, titlePaint)
        y2 += 16f
        c2.drawText("Deterministic Physiological Threshold Audit  |  Page 2 of 2", left, y2, mutedPaint)
        y2 += 12f
        c2.drawLine(left, y2, right, y2, linePaint)
        y2 += 22f

        state.regionTelemetry.values.forEach { reg ->
            val regColor = when (reg.severity) {
                SeverityLevel.NORMAL -> Color.parseColor("#047857")
                SeverityLevel.CAUTION -> Color.parseColor("#B45309")
                SeverityLevel.CRITICAL -> Color.parseColor("#BE123C")
            }
            val headerPaint = Paint(sectionPaint).apply { color = regColor }
            c2.drawText("${reg.region.displayName} [${reg.severity.label}] — ${reg.primaryNumber} | ${reg.secondaryNumber}", left, y2, headerPaint)
            y2 += 13f
            c2.drawText("Rate: ${reg.rateText}   |   Boundary: ${reg.etaText}", left, y2, mutedPaint)
            y2 += 13f
            reg.symptoms.forEach { sym ->
                c2.drawText("  • $sym", left, y2, bodyPaint)
                y2 += 12f
            }
            y2 += 10f
            c2.drawLine(left, y2, right, y2, linePaint)
            y2 += 16f
        }

        pdf.finishPage(page2)

        val rawOut = ByteArrayOutputStream()
        pdf.writeTo(rawOut)
        pdf.close()
        return rawOut.toByteArray()
    }

    private fun drawTrendBox(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        state: PhysiologyState,
        borderPaint: Paint,
        labelPaint: Paint,
    ) {
        canvas.drawRect(left, top, right, bottom, Paint(borderPaint).apply { style = Paint.Style.STROKE })
        val samples = state.history
        if (samples.size < 2) {
            canvas.drawText("Insufficient time-series samples collected yet.", left + 12f, (top + bottom) / 2f, labelPaint)
            return
        }
        val width = right - left - 16f
        val height = bottom - top - 24f
        val x0 = left + 8f
        val y0 = top + 12f

        fun plotSeries(values: List<Double>, minV: Double, maxV: Double, hexColor: String) {
            val path = Path()
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor(hexColor)
                style = Paint.Style.STROKE
                strokeWidth = 2f
            }
            val span = (maxV - minV).coerceAtLeast(1.0)
            values.forEachIndexed { idx, v ->
                val px = x0 + (idx.toFloat() / (values.size - 1).coerceAtLeast(1)) * width
                val norm = ((v - minV) / span).toFloat().coerceIn(0f, 1f)
                val py = y0 + height * (1f - norm)
                if (idx == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            canvas.drawPath(path, p)
        }

        plotSeries(samples.map { it.glucoseMgDl }, 40.0, 320.0, "#0284C7")
        plotSeries(samples.map { it.heartRateBpm }, 40.0, 170.0, "#BE123C")
        plotSeries(samples.map { it.mapMmHg }, 45.0, 150.0, "#D97706")
        plotSeries(samples.map { it.potassiumMeqL * 20.0 }, 40.0, 150.0, "#059669")
        canvas.drawText("Blue: Glucose | Red: HR | Amber: MAP | Green: K+", left + 8f, bottom - 4f, labelPaint)
    }
}
