package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

// 万象拼音更新的纯逻辑：版本比较、云端文件名白名单。
@RunWith(JUnit4::class)
class WanxiangUpdateLogicTest {

    @Test
    fun newerVersionIsDetectedByNumericSegments() {
        assertTrue(WanxiangUpdate.isNewer("5.10.0", "5.9.9"))
        assertFalse(WanxiangUpdate.isNewer("5.9.9", "5.10.0"))
        assertFalse(WanxiangUpdate.isNewer("5.4.9", "5.4.9"))
        assertTrue(WanxiangUpdate.isNewer("5.4.9", null))
        // 已装版本比云端长（用户加过后缀）不算有更新，别把兼容版降级。
        assertFalse(WanxiangUpdate.isNewer("5.4.9", "5.4.9-hotfix"))
        // 日期式 tag 也走同一套规则。
        assertTrue(WanxiangUpdate.isNewer("2025.11.25", "2025.11.2"))
    }

    @Test
    fun cloudBackupNamesMustStayInsideTheBackupDir() {
        assertEquals("CyIME-2026-11-25-1530.zip", WebDavBackup.requireSafeName("CyIME-2026-11-25-1530.zip"))
        assertEquals("CyIME-2026-11-25.zip", WebDavBackup.requireSafeName("/dav/CyIME-backup/CyIME-2026-11-25.zip"))
        listOf("../other.zip", "a/b.zip", "nozip.txt", "..").forEach { bad ->
            val failed = runCatching { WebDavBackup.requireSafeName(bad) }.isFailure
            assertTrue(bad + " 应被拒", failed)
        }
    }
}
