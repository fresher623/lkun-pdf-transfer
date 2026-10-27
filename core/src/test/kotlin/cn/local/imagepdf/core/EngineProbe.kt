package cn.local.imagepdf.core

import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.max

/** Host-only structural probe. These are 900×1200 synthetic numbered JPEGs, NOT 12MP device benchmarks. */
object EngineProbe {
    @JvmStatic fun main(args: Array<String>) {
        val output = File(args.firstOrNull() ?: "build/engine-probe").apply { mkdirs() }
        val counts = args.drop(1).map { it.toInt() }.ifEmpty { listOf(100, 300, 500) }
        val rows = mutableListOf<String>()
        for (count in counts) {
            validatePageCount(count)
            val target = File(output, "host-$count.pdf")
            val temp = File(output, "current-page.jpg")
            val runtime = Runtime.getRuntime()
            var peak = 0L
            val samples = mutableListOf<String>()
            val start = System.nanoTime()
            try {
                StreamingPdfWriter(target, count).use { writer ->
                    repeat(count) { page ->
                        val image = BufferedImage(900, 1200, BufferedImage.TYPE_INT_RGB)
                        val graphics = image.createGraphics()
                        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                        graphics.color = Color(230, 241, 248)
                        graphics.fillRect(0, 0, 900, 1200)
                        graphics.color = Color((page * 31) % 180, (page * 67) % 180, (page * 13) % 180)
                        graphics.fillOval(150, 180, 600, 600)
                        graphics.color = Color.WHITE
                        graphics.font = Font(Font.SANS_SERIF, Font.BOLD, 130)
                        graphics.drawString("%03d".format(page + 1), 320, 535)
                        graphics.color = Color.DARK_GRAY
                        graphics.font = Font(Font.SANS_SERIF, Font.PLAIN, 30)
                        graphics.drawString("HOST STRUCTURAL PROBE", 240, 930)
                        graphics.drawString("900 x 1200 synthetic JPEG", 245, 985)
                        graphics.dispose()
                        check(ImageIO.write(image, "JPEG", temp))
                        image.flush()
                        writer.addPage(temp, 900, 1200,
                            LayoutCalculator.calculate(900, 1200, OutputSettings(pageNumbers = true)), page + 1)
                        val used = runtime.totalMemory() - runtime.freeMemory()
                        peak = max(peak, used)
                        if ((page + 1) % 100 == 0) samples += "{\"page\":${page + 1},\"usedJavaBytes\":$used}"
                    }
                    writer.finish()
                }
            } finally { temp.delete() }
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            val row = "{\"pages\":$count,\"milliseconds\":$elapsedMs,\"pdfBytes\":${target.length()},\"sampledPeakJavaBytes\":$peak,\"samples\":[${samples.joinToString(",")}] }"
            rows += row
            println(row)
        }
        File(output, "host-metrics.json").writeText("{\n\"kind\":\"host-structural-probe\",\n\"source\":\"numbered synthetic 900x1200 RGB JPEG\",\n\"jvm\":\"${System.getProperty("java.version")}\",\n\"warning\":\"Not an Android/device/12MP performance result; JVM GC creates a sawtooth heap curve\",\n\"runs\":[${rows.joinToString(",\n")}]\n}\n")
    }
}
