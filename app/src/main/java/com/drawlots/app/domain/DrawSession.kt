package com.drawlots.app.domain

import kotlin.random.Random

/**
 * 签池 + 抽签历史，纯 Kotlin 实现（不依赖 Android），方便单元测试。
 *
 * 规则：
 * - 每种签有 [Lot.quantity] 支，抽到它的概率与支数成正比。
 * - [DrawMode.WITH_REPLACEMENT]（放回）：抽走的签立即放回，池子永远是满的。
 * - [DrawMode.WITHOUT_REPLACEMENT]（不放回）：抽走的支数记在 [drawnCountOf] 里，
 *   剩余为 0 的签不会再被抽到。
 * - 结果按抽出的顺序追加到 [results]，可以逐支撤销（等于放回）。
 */
class DrawSession(
    initialLots: List<Lot> = emptyList(),
    initialResults: List<DrawRecord> = emptyList(),
    initialMode: DrawMode = DrawMode.WITHOUT_REPLACEMENT,
    private val random: Random = Random.Default,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val lotList = initialLots.toMutableList()
    private val recordList = initialResults.toMutableList()

    /** lotId -> 已经被抽走的支数。 */
    private val consumed = LinkedHashMap<String, Int>()

    var mode: DrawMode = initialMode
        private set

    init {
        // 从历史记录里恢复“已抽走支数”，保证重新打开 App 后接着抽。
        for (record in recordList) {
            if (record.consumedCopy) consumed[record.lotId] = (consumed[record.lotId] ?: 0) + 1
        }
        pruneConsumed()
    }

    /**
     * 签池快照。对外只给不可变副本：联机时成员副本会被整体覆盖，
     * 直接暴露内部列表会让正在遍历的调用方撞上 ConcurrentModificationException。
     */
    val lots: List<Lot> get() = lotList.toList()

    /** 结果快照（同上，对外不可变）。 */
    val results: List<DrawRecord> get() = recordList.toList()

    val nextSeq: Int get() = (recordList.maxOfOrNull { it.seq } ?: 0) + 1

    val totalQuantity: Int get() = lotList.sumOf { it.quantity }

    val totalRemaining: Int get() = lotList.sumOf { remainingOf(it.id) }

    fun lotById(id: String): Lot? = lotList.firstOrNull { it.id == id }

    fun drawnCountOf(id: String): Int = consumed[id] ?: 0

    /** 这支签还能被抽到几次（放回模式下等同于数量）。 */
    fun remainingOf(id: String): Int {
        val quantity = lotById(id)?.quantity ?: return 0
        return (quantity - drawnCountOf(id)).coerceAtLeast(0)
    }

    fun availableLots(): List<Lot> = lotList.filter { remainingOf(it.id) > 0 }

    fun canDraw(): Boolean = when (mode) {
        DrawMode.WITH_REPLACEMENT -> totalQuantity > 0
        DrawMode.WITHOUT_REPLACEMENT -> totalRemaining > 0
    }

    /** 本次真正能抽出多少支（不放回模式下不会超过剩余数量）。 */
    fun plannedDrawCount(requested: Int): Int {
        val wanted = requested.coerceAtLeast(0)
        return when (mode) {
            DrawMode.WITH_REPLACEMENT -> if (totalQuantity > 0) wanted else 0
            DrawMode.WITHOUT_REPLACEMENT -> minOf(wanted, totalRemaining)
        }
    }

    fun setMode(newMode: DrawMode) {
        mode = newMode
    }

    /**
     * 抽一支签，池子空了返回 null。
     *
     * [drawnBy] 只有联机模式才传（房主应用请求时写入抽签人名称），单机模式留空。
     */
    fun drawOne(drawnBy: String = ""): DrawRecord? {
        val candidates = lotList.filter { lot ->
            lot.quantity > 0 && (mode == DrawMode.WITH_REPLACEMENT || remainingOf(lot.id) > 0)
        }
        if (candidates.isEmpty()) return null

        val weights = candidates.map { lot ->
            if (mode == DrawMode.WITH_REPLACEMENT) lot.quantity else remainingOf(lot.id)
        }
        val total = weights.sum()
        if (total <= 0) return null

        // 按支数加权随机：先把每支签看成一张票，再随机抽一张票。
        var ticket = random.nextInt(total)
        var index = 0
        while (index < candidates.lastIndex && ticket >= weights[index]) {
            ticket -= weights[index]
            index++
        }

        val lot = candidates[index]
        val copyTaken = mode == DrawMode.WITHOUT_REPLACEMENT
        if (copyTaken) {
            consumed[lot.id] = drawnCountOf(lot.id) + 1
        }

        val record = DrawRecord(
            seq = nextSeq,
            lotId = lot.id,
            kind = lot.kind,
            text = lot.text,
            imagePath = lot.imagePath,
            consumedCopy = copyTaken,
            mode = mode,
            atMillis = clock(),
            drawnBy = drawnBy.trim(),
        )
        recordList += record
        return record
    }

    /** 连续抽 [count] 支；不放回模式下抽完就停。 */
    fun draw(count: Int = 1, drawnBy: String = ""): List<DrawRecord> {
        val planned = plannedDrawCount(count)
        val drawn = ArrayList<DrawRecord>(planned)
        repeat(planned) {
            val record = drawOne(drawnBy) ?: return drawn
            drawn += record
        }
        return drawn
    }

    /** 撤销最后一次抽签（等于把最后那支放回池子）。 */
    fun undoLast(): DrawRecord? = recordList.lastOrNull()?.let { putBack(it.seq) }

    /**
     * 把指定的那一条结果放回签池：从结果里删掉它，并归还它当时占用的那一支。
     *
     * 放回模式下签池本来就是满的，所以只把这条结果移除。
     * 找不到对应记录时返回 null，不做任何改动。
     */
    fun putBack(seq: Int): DrawRecord? {
        val index = recordList.indexOfFirst { it.seq == seq }
        if (index < 0) return null
        val record = recordList.removeAt(index)
        if (record.consumedCopy) {
            val left = drawnCountOf(record.lotId) - 1
            if (left <= 0) consumed.remove(record.lotId) else consumed[record.lotId] = left
        }
        return record
    }

    fun undoLast(count: Int): List<DrawRecord> {
        val undone = ArrayList<DrawRecord>(count)
        repeat(count.coerceAtLeast(0)) {
            val record = undoLast() ?: return undone
            undone += record
        }
        return undone
    }

    /**
     * 给某一条结果写备注（例如记下这是谁抽到的）。空备注等于清掉备注。
     * 找不到对应记录时返回 null，不做任何改动。
     */
    fun setNote(seq: Int, note: String): DrawRecord? {
        val index = recordList.indexOfFirst { it.seq == seq }
        if (index < 0) return null
        val updated = recordList[index].copy(note = note.trim())
        recordList[index] = updated
        return updated
    }

    /** 重新开始：清空结果，所有签放回池子（数量不变）。 */
    fun resetPool() {
        consumed.clear()
        recordList.clear()
    }

    /**
     * 用一份快照整体覆盖当前状态（联机时成员接收房主广播的副本）。
     *
     * 会按历史记录重建「已抽走支数」，所以覆盖后剩余数量与房主一致。
     */
    fun replaceWith(snapshot: PoolSnapshot) {
        lotList.clear()
        lotList += snapshot.lots
        recordList.clear()
        recordList += snapshot.results
        mode = snapshot.mode
        consumed.clear()
        for (record in recordList) {
            if (record.consumedCopy) consumed[record.lotId] = (consumed[record.lotId] ?: 0) + 1
        }
        pruneConsumed()
    }

    /** 添加一种签。 */
    fun addLot(lot: Lot) {
        lotList += lot.normalized()
        pruneConsumed()
    }

    /** 添加多种签。 */
    fun addLots(lots: List<Lot>) {
        lots.forEach { lotList += it.normalized() }
        pruneConsumed()
    }

    /** 修改一种签（找不到就新增）。 */
    fun updateLot(lot: Lot) {
        val index = lotList.indexOfFirst { it.id == lot.id }
        val normalized = lot.normalized()
        if (index >= 0) lotList[index] = normalized else lotList += normalized
        pruneConsumed()
    }

    /** 修改数量；数量变小时，多抽走的支数会自动放回。 */
    fun setQuantity(id: String, quantity: Int) {
        val index = lotList.indexOfFirst { it.id == id }
        if (index < 0) return
        lotList[index] = lotList[index].copy(quantity = quantity.coerceIn(1, MAX_QUANTITY))
        pruneConsumed()
    }

    /** 删除一种签；历史结果保留（结果里保存了内容快照）。 */
    fun removeLot(id: String) {
        lotList.removeAll { it.id == id }
        consumed.remove(id)
        pruneConsumed()
    }

    /** 清空整个签池和抽签结果。 */
    fun clearLots() {
        lotList.clear()
        consumed.clear()
        recordList.clear()
    }

    fun snapshot(): PoolSnapshot = PoolSnapshot(lotList.toList(), recordList.toList(), mode)

    /** 数量变小或签被删除后，修正已抽走支数。 */
    private fun pruneConsumed() {
        val ids = lotList.mapTo(HashSet()) { it.id }
        consumed.keys.retainAll(ids)
        for (lot in lotList) {
            val count = consumed[lot.id] ?: continue
            if (count <= 0) {
                consumed.remove(lot.id)
            } else if (count > lot.quantity) {
                consumed[lot.id] = lot.quantity
            }
        }
    }
}

private fun Lot.normalized(): Lot = copy(
    text = text.trim(),
    quantity = quantity.coerceIn(1, MAX_QUANTITY),
)
