package io.memobservatory.exoskeleton.skills;

import io.memobservatory.exoskeleton.model.SkillEffectReport;
import io.memobservatory.exoskeleton.model.SkillEffectReport.Bucket;
import io.memobservatory.exoskeleton.model.SkillEffectReport.Item;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 技能效果验证闸门（采纳前后对照）。
 *
 * <p>验四件事：三项都「越低越好」、判「不劣化即通过」、样本不足如实报不可测、
 * 变差就判不通过（哪怕另外两项变好）。纯函数测试，不起 Spring 上下文——闸门刻意不碰数据库。
 */
class SkillEffectGateTest {

    private static final int MIN_SAMPLE = 30;
    private static final double RATE_TOL = 0.05;
    private static final double ROUNDS_TOL = 0.10;

    private final SkillEffectGate gate = new SkillEffectGate(MIN_SAMPLE, RATE_TOL, ROUNDS_TOL);

    /** 一组达标的读数：纠正率 / 失败率 / 回合数 + 样本量（>= 门槛即达标）。 */
    private static Bucket ok(long turns, double corrected, double failure, double rounds) {
        return new Bucket(turns, turns, corrected, failure, rounds, turns >= MIN_SAMPLE);
    }

    private static Item itemOf(SkillEffectGate.Verdict v, String label) {
        return v.items().stream().filter(i -> i.label().contains(label)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("G0 三项都变好：通过，且无 tripwire")
    void 变好则通过() {
        SkillEffectGate.Verdict v = gate.check(ok(100, 0.30, 0.20, 4.0), ok(100, 0.15, 0.10, 3.0));

        assertEquals(SkillEffectReport.PASS, v.verdict());
        assertTrue(v.tripwires().isEmpty(), "三项都下降不该有 tripwire：" + v.tripwires());
        assertEquals(SkillEffectReport.PASS, itemOf(v, "准").outcome());
        assertEquals(SkillEffectReport.PASS, itemOf(v, "失败").outcome());
        assertEquals(SkillEffectReport.PASS, itemOf(v, "回合").outcome());
    }

    @Test
    @DisplayName("G1 完全持平：也算通过（本闸门判的是「有没有变差」），但 tripwire 要喊出来")
    void 持平通过但喊话() {
        Bucket b = ok(100, 0.30, 0.20, 4.0);
        SkillEffectGate.Verdict v = gate.check(b, b);

        assertEquals(SkillEffectReport.PASS, v.verdict(), "持平不是变差，应通过");
        assertEquals(3, v.tripwires().size(), "三项都没降，三条 tripwire 都该喊：" + v.tripwires());
    }

    @Test
    @DisplayName("G2 纠正率明显变差（+10pp，越过 5pp 容忍）：不通过——哪怕失败率与回合数变好")
    void 一项变差即不通过() {
        SkillEffectGate.Verdict v = gate.check(ok(100, 0.30, 0.20, 4.0), ok(100, 0.40, 0.05, 2.0));

        assertEquals(SkillEffectReport.FAIL, v.verdict());
        assertEquals(SkillEffectReport.FAIL, itemOf(v, "准").outcome());
        assertEquals(SkillEffectReport.PASS, itemOf(v, "失败").outcome(), "另两项变好不影响结论");
    }

    @Test
    @DisplayName("G3 容忍之内不算变差：+4pp 是持平，+20% 回合数才是变差")
    void 容忍之内不算变差() {
        // 纠正率 +4pp（< 5pp 容忍）→ 过；回合数 4.0 → 4.3（+7.5% < 10%）→ 过
        assertEquals(SkillEffectReport.PASS,
                gate.check(ok(100, 0.30, 0.20, 4.0), ok(100, 0.34, 0.20, 4.3)).verdict());
        // 回合数 4.0 → 4.5（+12.5% > 10%）→ 不过
        assertEquals(SkillEffectReport.FAIL,
                gate.check(ok(100, 0.30, 0.20, 4.0), ok(100, 0.30, 0.20, 4.5)).verdict());
    }

    @Test
    @DisplayName("G4 样本不足：如实报不可测，不假装通过也不假装失败")
    void 样本不足则不可测() {
        // 采纳后只有 5 个 turn，低于门槛 30 —— 该组判不了，整体不可测
        SkillEffectGate.Verdict v = gate.check(ok(100, 0.30, 0.20, 4.0), ok(5, 0.10, 0.00, 1.0));

        assertEquals(SkillEffectReport.UNKNOWN, v.verdict());
        assertTrue(v.items().stream().allMatch(i -> SkillEffectReport.UNKNOWN.equals(i.outcome())));
        assertTrue(v.tripwires().isEmpty(), "不可测就不该喊 tripwire");
    }

    @Test
    @DisplayName("G5 缺一组（未采纳 / 无数据）：同样不可测")
    void 缺一组则不可测() {
        assertEquals(SkillEffectReport.UNKNOWN,
                gate.check(null, ok(100, 0.10, 0.00, 2.0)).verdict());
        assertEquals(SkillEffectReport.UNKNOWN,
                gate.check(ok(100, 0.10, 0.00, 2.0), null).verdict());
    }
}