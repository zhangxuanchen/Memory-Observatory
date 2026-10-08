package io.memobservatory.exoskeleton;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 记忆外骨骼（L6 能学）入口。
 *
 * L6 的产物是「给 Agent 执行的规则」，不是给人看的告警——这是它与 L1–L5 的性质差别：
 * 误判会直接让执行变糟。所以规则上线只有一条路径：
 * 起草 → 影子 → 晋升闸门 → 决策卡片 → 人工采纳。
 * 设计见 learn/自校准闭环：设计与实现（L6 能学）.md。
 */
@SpringBootApplication
public class ExoskeletonApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExoskeletonApplication.class, args);
    }
}
