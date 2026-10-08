package io.memobservatory.exoskeleton.drafter;

import io.memobservatory.exoskeleton.cluster.ShapeBucketer;
import io.memobservatory.exoskeleton.model.ClusterProfile;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 确定性替身撰写器：按簇键查表产出固定文本（设计文档 §4.5 的「lab 实现」）。
 *
 * 为什么默认用它而不是 LLM：**闭环机制成不成立，与撰写器是不是 LLM 无关。**
 * 替身可以在零依赖、无密钥、可复现的条件下把「起草 → 影子 → 晋升」这条链跑通；
 * 等机制稳了，换一个 {@link RuleDrafter} 实现即可，取数与闸门都不用动。
 *
 * 表里只写记忆策略（该记什么 / 该忘什么 / 何时读回 / 用哪个工具），不含任何推理步骤（§1.4）。
 * 遇到表里没有的簇键返回 {@link Optional#empty()}——宁可不出规则，也不要出一条编的。
 *
 * <p>簇键是两维交叉（{@code shape|steps:band}，见 {@link ShapeBucketer}），逐条穷举要 4×4 句。
 * 故拆成两张小表再拼：shape 定「这一轮的记忆用法」，steps 定「力度」。这样每一段都是单独可读、
 * 可争论的，而不是二十条各写各的相似句子。
 */
@Component
public class TemplateRuleDrafter implements RuleDrafter {

    private static final String SEP = "|steps:";

    /** shape → 记忆用法。{@code unknown} 刻意不在表里：老数据该不该按它出规则，存疑，宁缺勿编。 */
    private static final Map<String, String> BY_SHAPE = buildByShape();

    /** steps 档 → 力度。 */
    private static final Map<String, String> BY_STEPS = buildBySteps();

    @Override
    public Optional<String> draft(ClusterProfile profile) {
        if (profile == null || profile.clusterKey() == null) {
            return Optional.empty();
        }
        String key = profile.clusterKey();
        int sep = key.indexOf(SEP);
        if (sep <= 0) {
            return Optional.empty();
        }
        String usage = BY_SHAPE.get(key.substring(0, sep));
        String effort = BY_STEPS.get(key.substring(sep + SEP.length()));
        if (usage == null || effort == null) {
            return Optional.empty();
        }
        return Optional.of(usage + "；" + effort);
    }

    private static Map<String, String> buildByShape() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(ShapeBucketer.READONLY,
                "只读未写：命中即用，不为留痕额外写回；同一个记忆键被读两次以上，改写回一条合并后的记忆");
        m.put(ShapeBucketer.READWRITE,
                "边读边写：读到的先归并再落笔，别把半成品写进记忆；写回时用任务名做记忆键");
        m.put(ShapeBucketer.WRITEONLY,
                "只写未读：落笔前先读一次同主题记忆，命中就更新那一条，不新增");
        m.put(ShapeBucketer.NONE,
                "没有工具读写：纯推理或对话，不主动开记忆；仅当用户明确指向此前内容时读回一次");
        return Map.copyOf(m);
    }

    private static Map<String, String> buildBySteps() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("1-3", "步数少（1–3 步）：一趟做完，不额外加读写");
        m.put("4-10", "步数中等（4–10 步）：只读回与当前任务直接相关的条目，读完即用");
        m.put("11+", "步数多（11 步以上）：动手前先把相关记忆汇总一次，避免重复推导；收尾写回一条可复用结论");
        m.put("unknown", "步数未知：按中等长度处理");
        return Map.copyOf(m);
    }
}
