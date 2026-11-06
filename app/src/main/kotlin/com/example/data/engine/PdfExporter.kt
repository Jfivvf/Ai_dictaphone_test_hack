package com.example.data.engine

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.example.data.model.Recording
import java.io.File
import java.io.FileOutputStream

object PdfExporter {

    fun exportRecordingToPdf(context: Context, recording: Recording): File? {
        val pdfDocument = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create() // A4 size in points
        val page = pdfDocument.startPage(pageInfo)
        val canvas: Canvas = page.canvas

        val titlePaint = Paint().apply {
            color = Color.rgb(15, 23, 42) // Slate 900
            textSize = 18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val metaPaint = Paint().apply {
            color = Color.rgb(100, 116, 139) // Slate 500
            textSize = 10f
            typeface = Typeface.DEFAULT
            isAntiAlias = true
        }

        val sectionPaint = Paint().apply {
            color = Color.rgb(2, 132, 199) // Sky 600
            textSize = 13f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val textPaint = Paint().apply {
            color = Color.rgb(51, 65, 85) // Slate 700
            textSize = 10f
            typeface = Typeface.DEFAULT
            isAntiAlias = true
        }

        val speakerPaint = Paint().apply {
            color = Color.rgb(124, 58, 237) // Violet 600
            textSize = 10f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        var yPos = 50f
        val margin = 40f
        val maxTextWidth = 515f

        // Title
        canvas.drawText("AI Dictaphone — Стенограмма диалога", margin, yPos, titlePaint)
        yPos += 24f

        canvas.drawText("Тема: ${recording.title}", margin, yPos, sectionPaint)
        yPos += 16f
        canvas.drawText("Дата: ${recording.formattedDate} | Длительность: ${recording.formattedDuration} | Спикеров: ${recording.speakersCount}", margin, yPos, metaPaint)
        yPos += 20f

        // Draw separator
        val linePaint = Paint().apply {
            color = Color.rgb(226, 232, 240)
            strokeWidth = 1f
        }
        canvas.drawLine(margin, yPos, margin + maxTextWidth, yPos, linePaint)
        yPos += 20f

        // Summary section
        if (recording.summary.isNotBlank()) {
            canvas.drawText("КРАТКАЯ ВЫЖИМКА (EXECUTIVE SUMMARY)", margin, yPos, sectionPaint)
            yPos += 16f
            val summaryLines = wrapText(recording.summary.replace("\n", " "), textPaint, maxTextWidth)
            summaryLines.forEach { line ->
                canvas.drawText(line, margin, yPos, textPaint)
                yPos += 14f
            }
            yPos += 14f
        }

        // Action items section
        if (recording.actionItems.isNotEmpty()) {
            canvas.drawText("КЛЮЧЕВЫЕ ЗАДАЧИ И РЕШЕНИЯ", margin, yPos, sectionPaint)
            yPos += 16f
            recording.actionItems.take(5).forEach { item ->
                val check = if (item.isCompleted) "[x]" else "[ ]"
                val assignee = if (!item.assignee.isNullOrBlank()) " (${item.assignee})" else ""
                val taskText = "$check ${item.text}$assignee"
                canvas.drawText(taskText, margin, yPos, textPaint)
                yPos += 14f
            }
            yPos += 14f
        }

        // Transcript with Diarization
        canvas.drawText("СТРУКТУРИРОВАННАЯ СТЕНОГРАММА ДИАЛОГА", margin, yPos, sectionPaint)
        yPos += 18f

        recording.segments.take(12).forEach { segment ->
            if (yPos > 790f) return@forEach // Prevent drawing off page
            val header = "${segment.formattedTime}  •  ${segment.speaker}:"
            canvas.drawText(header, margin, yPos, speakerPaint)
            yPos += 14f
            val wrapped = wrapText(segment.text, textPaint, maxTextWidth)
            wrapped.forEach { line ->
                if (yPos > 800f) return@forEach
                canvas.drawText(line, margin + 12f, yPos, textPaint)
                yPos += 13f
            }
            yPos += 6f
        }

        pdfDocument.finishPage(page)

        val exportDir = File(context.filesDir, "pdf_exports").apply { if (!exists()) mkdirs() }
        val pdfFile = File(exportDir, "transcript_${recording.id}.pdf")

        return try {
            val outputStream = FileOutputStream(pdfFile)
            pdfDocument.writeTo(outputStream)
            outputStream.close()
            pdfDocument.close()
            pdfFile
        } catch (e: Exception) {
            e.printStackTrace()
            pdfDocument.close()
            null
        }
    }

    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var currentLine = StringBuilder()

        for (word in words) {
            val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
            if (paint.measureText(testLine) <= maxWidth) {
                currentLine = StringBuilder(testLine)
            } else {
                if (currentLine.isNotEmpty()) lines.add(currentLine.toString())
                currentLine = StringBuilder(word)
            }
        }
        if (currentLine.isNotEmpty()) lines.add(currentLine.toString())
        return lines
    }
}
