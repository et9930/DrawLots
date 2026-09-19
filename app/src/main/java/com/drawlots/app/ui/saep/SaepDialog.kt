package com.drawlots.app.ui.saep

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import org.json.JSONObject

/** 一条 SAEP 接口调用结果，直接展示给用户。 */
data class SaepResult(val title: String, val detail: String, val ok: Boolean)

/**
 * SAEP（Screen Automation Execution Protocol）运行时接口。
 *
 * 支持 SAEP 的系统（ObricUI 2.2+）会暴露 `android.security.obric.*` 的 Stub 与
 * `com.obric.agentrobots.provider`；本应用要能安装在普通安卓设备上，所以**全部用反射调用**，
 * 找不到就如实报告「不可用」，绝不影响其它功能（做法与官方 demo bytedance/SAEP-demo 一致）。
 */
object SaepClient {

    private const val POLICY_METADATA = "com.obric.agentrobots.POLICY_JSON"
    private const val POLICY_RESOURCE = "raw/agent_saep_policy"
    private val POLICY_URI: Uri = Uri.parse("content://com.obric.agentrobots.provider/policy")

    /** 静态策略：读清单 metadata 指向的 raw 资源。**不依赖 SAEP 运行时，任何设备都能读**。 */
    fun staticPolicy(context: Context): List<SaepResult> {
        val out = mutableListOf<SaepResult>()
        val info = runCatching {
            context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        }.getOrNull()
        val resId = info?.metaData?.getInt(POLICY_METADATA, 0) ?: 0
        if (resId == 0) {
            out += SaepResult("静态策略", "清单里没有 $POLICY_METADATA", false)
            return out
        }
        val bytes = runCatching { context.resources.openRawResource(resId).use { it.readBytes() } }.getOrNull()
        if (bytes == null) {
            out += SaepResult("静态策略", "读不到 $POLICY_RESOURCE", false)
            return out
        }
        val sizeOk = bytes.size <= 10 * 1024
        out += SaepResult(
            "静态策略",
            "@$POLICY_RESOURCE · ${bytes.size} 字节（上限 10240）",
            sizeOk,
        )
        val json = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull()
            ?: return out + SaepResult("策略解析", "JSON 解析失败", false)
        val schemaOk = json.optString("schema") == "AGRP-Policy/1.0"
        val packageOk = json.optString("package") == context.packageName
        out += SaepResult(
            "schema / 包名 / 版本",
            "${json.optString("schema")} · ${json.optString("package")} · v${json.optInt("policy_version")}",
            schemaOk && packageOk,
        )
        val app = json.optJSONObject("default_policy")?.optJSONObject("app")
        out += SaepResult(
            "默认规则（false = 允许自动化）",
            "全部禁用=${app?.optBoolean("global_disable")} · 截图禁用=${app?.optBoolean("screenshot_disable")} · 输入禁用=${app?.optBoolean("input_disable")}",
            true,
        )
        return out
    }

    /** SAEP 开关：`RobotsHelperStub.getInstance().isRobotsEnabled(context)` */
    fun enabled(context: Context): SaepResult {
        val stub = stub("android.security.obric.robots.RobotsHelperStub")
            ?: return unavailable("SAEP 开关", "RobotsHelperStub")
        val value = invoke(stub, "isRobotsEnabled", Context::class.java, context)
        return when (value) {
            is Boolean -> SaepResult("SAEP 开关", if (value) "已启用" else "未启用", value)
            null -> SaepResult("SAEP 开关", "调用失败（Stub 存在但不可用）", false)
            else -> SaepResult("SAEP 开关", "返回了意外类型：${value::class.simpleName}", false)
        }
    }

    /** 动态策略：`ContentResolver.update(content://com.obric.agentrobots.provider/policy, {policy, version})` */
    fun submitPolicy(context: Context, policyJson: String, version: Int): SaepResult {
        val values = ContentValues(2).apply {
            put("policy", policyJson)
            put("version", version)
        }
        val rows = runCatching { context.contentResolver.update(POLICY_URI, values, null, null) }
            .getOrElse { return SaepResult("动态策略", "失败：${it.message ?: it::class.simpleName}（需 com.obric.agentrobots.provider）", false) }
        return SaepResult(
            "动态策略",
            if (rows > 0) "已提交 v$version（返回行数 $rows）" else "未被接受（返回行数 $rows）",
            rows > 0,
        )
    }

    /** 本地审计日志：`SecurityAuditManagerStub.getInstance().queryLogs(start, end, intent, activity)` */
    fun auditLogs(): SaepResult {
        val stub = stub("android.security.obric.audit.SecurityAuditManagerStub")
            ?: return unavailable("本地审计日志", "SecurityAuditManagerStub")
        val list = invoke(
            stub, "queryLogs",
            Long::class.javaPrimitiveType, 0L,
            Long::class.javaPrimitiveType, 0L,
            String::class.java, "",
            String::class.java, "",
        ) as? List<*>
        if (list == null) return SaepResult("本地审计日志", "调用失败或返回非列表", false)
        val head = list.take(3).joinToString("\n") { it.toString() }
        return SaepResult(
            "本地审计日志",
            "共 ${list.size} 条" + if (head.isBlank()) "" else "\n$head",
            true,
        )
    }

    /** 已注册可信 Agent：`AgentManagerStub.getInstance().listRegisteredAgents()` */
    fun registeredAgents(): SaepResult {
        val stub = stub("android.security.obric.agentmanager.AgentManagerStub")
            ?: return unavailable("可信 Agent", "AgentManagerStub")
        val list = invoke(stub, "listRegisteredAgents") as? List<*>
        if (list == null) return SaepResult("可信 Agent", "调用失败或返回非列表", false)
        val head = list.take(5).joinToString("\n") { it.toString() }
        return SaepResult("可信 Agent", "共 ${list.size} 个" + if (head.isBlank()) "" else "\n$head", true)
    }

    // ---------- 反射小工具 ----------

    private fun stub(className: String): Any? = runCatching {
        val clazz = Class.forName(className)
        clazz.getMethod("getInstance").invoke(null)
    }.getOrNull()

    /** 按 (参数类型, 值) 交替传参调用；任一步失败返回 null。 */
    private fun invoke(target: Any, method: String, vararg args: Any?): Any? = runCatching {
        val types = ArrayList<Class<*>>()
        val values = ArrayList<Any?>()
        var i = 0
        while (i < args.size) {
            types += args[i] as Class<*>
            values += args[i + 1]
            i += 2
        }
        target.javaClass.getMethod(method, *types.toTypedArray()).invoke(target, *values.toTypedArray())
    }.getOrNull()

    private fun unavailable(title: String, stubName: String) = SaepResult(
        title,
        "系统未提供 $stubName（SAEP 需要 ObricUI 2.2+）",
        false,
    )
}

/**
 * SAEP 面板：展示静态策略与四个运行时接口的调用结果。
 * 不支持 SAEP 的设备上，除静态策略外都会显示「不可用」，这是预期行为。
 */
@Composable
fun SaepDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var results by remember { mutableStateOf(SaepClient.staticPolicy(context)) }

    fun add(result: SaepResult) {
        results = results + result
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
            ) {
                Text("SAEP", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "屏幕自动化执行协议（Screen Automation Execution Protocol）：应用侧的保护策略与运行时接口。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                results.forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (item.ok) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                            )
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(item.title, style = MaterialTheme.typography.labelLarge)
                            Text(
                                text = item.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { add(SaepClient.enabled(context)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("查询 SAEP 开关") }

                    OutlinedButton(
                        onClick = {
                            val policy = runCatching {
                                val info = context.packageManager.getApplicationInfo(
                                    context.packageName,
                                    PackageManager.GET_META_DATA,
                                )
                                val id = info.metaData?.getInt("com.obric.agentrobots.POLICY_JSON", 0) ?: 0
                                context.resources.openRawResource(id).use { it.readBytes().toString(Charsets.UTF_8) }
                            }.getOrNull()
                            if (policy == null) {
                                add(SaepResult("动态策略", "读不到本地静态策略，无法提交", false))
                            } else {
                                add(SaepClient.submitPolicy(context, policy, version = 2))
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("提交动态策略（v2）") }

                    OutlinedButton(
                        onClick = { add(SaepClient.auditLogs()) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("查询本地审计日志") }

                    OutlinedButton(
                        onClick = { add(SaepClient.registeredAgents()) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("列出可信 Agent") }
                }

                Spacer(Modifier.height(10.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("关闭") }
            }
        }
    }
}
