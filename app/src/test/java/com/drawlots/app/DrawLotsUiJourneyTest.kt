package com.drawlots.app

import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.drawlots.app.domain.DrawMode
import com.drawlots.app.domain.DrawRecord
import com.drawlots.app.domain.Lot
import com.drawlots.app.domain.LotKind
import com.drawlots.app.domain.PoolSnapshot
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * 整个 App 的端到端流程测试（Robolectric + Compose，在 JVM 上真的把界面渲染出来）。
 *
 * 有意只写**一个**测试方法：Robolectric 在同一个 sandbox 里会复用 Application/Activity 与
 * SharedPreferences，多个测试方法之间会互相污染；一个方法从头跑到尾就没有这个问题。
 *
 * 覆盖：读档（含已抽结果与备注）→ 签池列表与数量 → 抽签（含滚动动画）→
 * 结果位次与剩余数量 → 一行四个操作按钮 → 结果弹窗（备注输入框 / 放回这个签）→
 * 重置 → 清空签池 → 空状态 → 放回/不放回切换。
 *
 * 注意：Robolectric 的文字测量不完整，assertIsDisplayed 会误判，因此这里只断言节点存在；
 * 输入框里打字也会让 Robolectric 的 idle 检查卡住（光标是无限动画），所以只断言输入框存在，
 * 打字与保存由真机验证 + DrawSession 的单元测试覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class DrawLotsUiJourneyTest {

    /** 启动前写入签池和一条带备注的抽签结果（同时验证读档路径）。 */
    private val seedPool = object : ExternalResource() {
        override fun before() {
            val context = ApplicationProvider.getApplicationContext<Context>()
            TestState.reset(
                context,
                PoolSnapshot(
                    lots = listOf(
                        Lot(id = "lot-a", kind = LotKind.TEXT, text = "张三", quantity = 1),
                        Lot(id = "lot-b", kind = LotKind.TEXT, text = "李四", quantity = 2),
                    ),
                    results = listOf(
                        DrawRecord(
                            seq = 1,
                            lotId = "lot-a",
                            kind = LotKind.TEXT,
                            text = "张三",
                            imagePath = null,
                            consumedCopy = true,
                            mode = DrawMode.WITHOUT_REPLACEMENT,
                            atMillis = 1_700_000_000_000L,
                            note = "小明抽到",
                        ),
                    ),
                    mode = DrawMode.WITHOUT_REPLACEMENT,
                ),
            )
        }
    }

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seedPool).around(compose)

    /**
     * 抽签有滚动动画（协程里的 delay）。Robolectric 下要同时推进 Compose 的测试时钟和主线程时钟；
     * 动画期间 Compose 不会 idle，所以查询失败先忽略，继续推时钟。
     */
    private fun advanceUntilTextAppears(text: String, timeoutMillis: Long = 10_000): Boolean {
        var elapsed = 0L
        val step = 100L
        while (elapsed < timeoutMillis) {
            compose.mainClock.advanceTimeBy(step)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(step))
            elapsed += step
            val found = runCatching {
                compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
            if (found) return true
        }
        return false
    }

    @Test
    fun fullJourneyFromRestoredPoolToEmptyPool() {
        // 1. 读档：签池 3 支、上一条结果（张三）已经抽走 1 支，备注也读回来了
        compose.onNodeWithText("共 2 种签 · 共 3 支 · 剩余 2 支").assertExists()
        compose.onNodeWithText("1 个").assertExists()
        compose.onNodeWithText("小明抽到").assertExists()

        // 1.1 单机模式：顶栏有「联机」入口，但不显示任何房间状态条
        compose.onNodeWithText("联机").assertExists()
        compose.onAllNodesWithText("已连接").assertCountEquals(0)
        compose.onAllNodesWithText("等待其他人加入").assertCountEquals(0)
        compose.onAllNodesWithText("房主未开放编辑签池").assertCountEquals(0)

        // 2. 放回 / 不放回 切换
        compose.onNodeWithText("放回").performClick()
        compose.onNodeWithText("每次抽签都在完整的签池里抽，同一支签可能被重复抽到。").assertExists()
        compose.onNodeWithText("不放回").performClick()
        compose.onNodeWithText("抽走的签不再放回，每个签最多被抽到它的数量那么多次。").assertExists()

        // 3. 签池页：两个签、各自数量（张三已经抽走 1 支）
        compose.onNodeWithText("签池").performClick()
        compose.onNodeWithText("张三").assertExists()
        compose.onNodeWithText("李四").assertExists()
        compose.onAllNodesWithText("数量 ×1　已抽 1　剩余 0").assertCountEquals(1)
        compose.onAllNodesWithText("数量 ×2　已抽 0　剩余 2").assertCountEquals(1)

        // 4. 一行四个操作按钮
        compose.onNodeWithText("抽签").performClick()
        compose.onNodeWithText("撤销").assertExists()
        compose.onNodeWithText("重置").assertExists()
        compose.onNodeWithText("分享").assertExists()
        compose.onNodeWithText("复制").assertExists()

        // 5. 点结果卡片 → 弹窗里显示已读回来的备注，也能单独放回这一支
        //    （备注输入框要点「添加备注」才出现：Compose 文本框在 Robolectric 下会让 idle 检查卡住）
        compose.onNodeWithText("张三").performClick()
        compose.onNodeWithText("第 1 个签").assertExists()
        compose.onNodeWithText("备注：小明抽到").assertExists()
        compose.onNodeWithText("修改备注").assertExists()
        compose.onNodeWithText("放回这个签").performClick()
        compose.onNodeWithText("0 个").assertExists()
        compose.onNodeWithText("剩余 3/3").assertExists()

        // 6. 抽 1 支后再「重置」，效果与放回全部一致
        compose.onNodeWithText("抽 1 个签").performClick()
        assertTrue("抽签结果没有出现", advanceUntilTextAppears("1 个"))
        compose.onNodeWithText("重置").performClick()
        compose.onNodeWithText("剩余 3/3").assertExists()
        compose.onNodeWithText("0 个").assertExists()

        // 7. 清空签池（带确认框）
        compose.onNodeWithText("签池").performClick()
        compose.onNodeWithText("清空签池").performClick()
        compose.onNodeWithText("清空").performClick()
        compose.onNodeWithText("签池还是空的").assertExists()

        // 8. 空签池时抽签页给出提示
        compose.onNodeWithText("抽签").performClick()
        compose.onAllNodesWithText("没有可抽的签").assertCountEquals(2)
        compose.onNodeWithText("先去「签池」添加签吧").assertExists()
    }
}
