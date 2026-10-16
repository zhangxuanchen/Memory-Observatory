package io.memobservatory.exoskeleton.skills;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VersionPolicy} 的演进判决（纯逻辑，本项功能的主闸门）：
 * 两条件同时满足才出新版——内容真变 + 证据确有增长。缺任一都 {@code KEEP}。
 *
 * <p><b>为什么这两条才是要害</b>：只认「内容变」会被模型的措辞漂移刷版本（同一份证据重跑，
 * 一句话换个说法就成一版）；只认「证据涨」则内容一字未改也刷版本。两条一起卡，才既容得下
 * 真实演进、又压得住噪声。
 */
class VersionPolicyTest {

    @Test
    @DisplayName("内容未变：即便证据涨了很多，也不出新版")
    void 内容未变不出新版() {
        VersionPolicy policy = new VersionPolicy(10);

        VersionPolicy.Decision d = policy.decide("hash-a", "hash-a", 100, 999);

        assertEquals(VersionPolicy.KEEP, d.outcome());
        assertFalse(d.revise());
        assertTrue(d.reason().contains("内容未变"), d.reason());
    }

    @Test
    @DisplayName("内容变了但证据增长不足：压住不出新版（防措辞漂移刷版本）")
    void 证据增长不足不出新版() {
        VersionPolicy policy = new VersionPolicy(10);

        VersionPolicy.Decision d = policy.decide("hash-a", "hash-b", 100, 109);

        assertEquals(VersionPolicy.KEEP, d.outcome());
        assertTrue(d.reason().contains("证据增长不足"), d.reason());
    }

    @Test
    @DisplayName("内容变了且证据刚好涨到阈值：出新版（边界含等号）")
    void 证据刚好达标则出新版() {
        VersionPolicy policy = new VersionPolicy(10);

        VersionPolicy.Decision d = policy.decide("hash-a", "hash-b", 100, 110);

        assertEquals(VersionPolicy.REVISE, d.outcome());
        assertTrue(d.revise());
        assertTrue(d.reason().contains("出新版"), d.reason());
    }

    @Test
    @DisplayName("阈值可配：阈值 0 时，只要内容变就出新版")
    void 阈值可配() {
        VersionPolicy policy = new VersionPolicy(0);

        assertEquals(VersionPolicy.REVISE, policy.decide("a", "b", 100, 100).outcome());
    }

    @Test
    @DisplayName("纯判决：同一输入永远同一结果（不碰库、无随机、无时间依赖）")
    void 判决确定() {
        VersionPolicy policy = new VersionPolicy(10);

        assertEquals(policy.decide("a", "b", 100, 110).outcome(),
                policy.decide("a", "b", 100, 110).outcome());
        assertEquals(policy.decide("a", "a", 100, 999).outcome(),
                policy.decide("a", "a", 100, 999).outcome());
    }
}