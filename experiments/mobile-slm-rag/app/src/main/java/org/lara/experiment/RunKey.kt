package org.lara.experiment

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import org.json.JSONObject
import org.lara.experiment.rag.Flags
import org.lara.experiment.rag.KeyItem
import org.lara.experiment.rag.Msg
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Batch evaluation of the 30-question verified key. Writes results.csv (per question) and runs.csv (per run) into the app's files dir. */
class RunKey(private val ctx: Context, private val engine: Engine) {
    private val dir = engine.dir
    private val results = File(dir, "results.csv")
    private val runs = File(dir, "runs.csv")
    private val marker = File(dir, "run_marker.json")

    companion object {
        const val RESULT_HEADER = "run_id,id,type,question,key_pages,retrieved_pages,top1_hit,hit_in_budget,answer_in_budget,guard_blocked,prompt_tokens," +
            "retrieve_ms,ttft_s,prompt_eval_ms,gen_tokens,tok_per_s,leak,raw_leak,points,clue,declined_ok,bad_cite,misfire,redacted_sentences,reply,raw_reply"
        const val RUN_HEADER = "run_id,started,model,quant,threads,ctx,device,ram_total_mb,ram_avail_start_mb,model_load_s,status,questions_done,questions_total," +
            "peak_pss_mb,peak_java_heap_mb,peak_native_heap_mb,batt_temp_before_c,batt_temp_after_c,exit_reason"

        fun csv(vararg v: Any?) = v.joinToString(",") { x ->
            val s = when (x) { null -> ""; is Double -> if (x.isNaN()) "" else "%.3f".format(Locale.US, x); else -> x.toString() }
            if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
        }

        fun quantOf(name: String) = Regex("(?i)(Q\\d(?:_[A-Z0-9]+)*|F16|BF16)").findAll(name).lastOrNull()?.value?.uppercase() ?: "unknown"
    }

    private fun append(f: File, header: String, line: String) { if (!f.exists()) f.writeText(header + "\n"); f.appendText(line + "\n") }

    /** A marker left by a run that never finished means the process died (low-memory kill, crash, battery pull). Called at app start. */
    fun recordKilledRun(): String? {
        if (!marker.exists()) return null
        val m = JSONObject(marker.readText())
        val reason = if (Build.VERSION.SDK_INT >= 30)
            (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getHistoricalProcessExitReasons(ctx.packageName, 0, 5)
                .firstOrNull()?.let { exitReason(it.reason) } ?: "unknown" else "unknown (needs Android 11+)"
        append(runs, RUN_HEADER, csv(m.getString("run_id"), m.getString("started"), m.getString("model"), m.getString("quant"), m.getInt("threads"), m.getInt("ctx"), m.getString("device"),
            m.getLong("ram_total_mb"), m.getLong("ram_avail_start_mb"), m.getDouble("model_load_s"), "KILLED_OR_CRASHED", m.getInt("done"), m.getInt("total"),
            m.getDouble("peak_pss"), m.getDouble("peak_java"), m.getDouble("peak_native"), m.getDouble("batt_before"), null, reason))
        marker.delete()
        return m.getString("run_id")
    }

    private fun exitReason(r: Int) = when (r) {
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        else -> "reason_$r"
    }

    /** Runs the whole key; [cancelled] is polled between questions. */
    fun run(model: File, threads: Int, loadSeconds: Double, progress: (Int, Int, String) -> Unit, cancelled: () -> Boolean): String {
        val key = KeyItem.parse(ctx.assets.open("wells_biology_key.json").bufferedReader().readText())
        val chunks = engine.lesson!!.chunks
        val runId = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val started = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
        val quant = quantOf(model.name)
        val battBefore = Device.batteryTempC(ctx)
        val mem = MemSampler().also { it.start() }
        val base = JSONObject().put("run_id", runId).put("started", started).put("model", model.name).put("quant", quant).put("threads", threads).put("ctx", engine.nCtx)
            .put("device", Device.name).put("ram_total_mb", Device.ramTotalMb(ctx)).put("ram_avail_start_mb", Device.ramAvailMb(ctx)).put("model_load_s", loadSeconds)
            .put("total", key.size).put("batt_before", battBefore)
        fun mark(done: Int) { marker.writeText(base.put("done", done).put("peak_pss", mem.peakPssMb).put("peak_java", mem.peakJavaMb).put("peak_native", mem.peakNativeMb).toString()) }
        mark(0)
        var done = 0
        var status = "OK"
        try {
            for (k in key) {
                if (cancelled()) { status = "CANCELLED"; break }
                progress(done, key.size, k.id)
                val t = engine.answer(k.question, emptyList<Msg>())
                val pages = t.built.sources.map { it.page }
                val top1 = t.built.rank.firstOrNull()?.let { id -> chunks.first { it.id == id }.page }
                val hit = k.pages.any { it in pages }
                val ans = !k.isOutOfScope && Flags.hasAnswer(k.tokens, t.built.sources.joinToString(" ") { it.text })
                val f = Flags.flags(k, t.shown, pages.toSet(), chunks)
                append(results, RESULT_HEADER, csv(runId, k.id, k.type, k.question, k.pages.sorted().joinToString(" "), pages.joinToString(" "),
                    if (k.isOutOfScope) null else top1 in k.pages, if (k.isOutOfScope) null else hit, if (k.isOutOfScope) null else ans, t.built.guardBlocked, t.built.promptTokens,
                    t.retrieveMs, t.ttftS, t.stats?.promptMs, t.stats?.genTokens, t.stats?.tokPerSec, f.leak, Flags.flags(k, t.raw, pages.toSet(), chunks).leak, f.points, f.clue, f.declinedOk, f.badCite, f.misfire, t.redactedSentences, t.shown, t.raw))
                done++
                mark(done)
            }
        } catch (e: Throwable) {
            status = "ERROR: ${e.javaClass.simpleName}"
        } finally {
            mem.stop()
        }
        append(runs, RUN_HEADER, csv(runId, started, model.name, quant, threads, engine.nCtx, Device.name, Device.ramTotalMb(ctx), base.getLong("ram_avail_start_mb"), loadSeconds, status, done, key.size,
            mem.peakPssMb, mem.peakJavaMb, mem.peakNativeMb, battBefore, Device.batteryTempC(ctx), null))
        marker.delete()
        return runId
    }
}
