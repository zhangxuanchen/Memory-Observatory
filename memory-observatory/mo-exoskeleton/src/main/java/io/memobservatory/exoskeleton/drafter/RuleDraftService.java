package io.memobservatory.exoskeleton.drafter;

import io.memobservatory.exoskeleton.model.ClusterProfile;
import io.memobservatory.exoskeleton.model.RulesReport;
import io.memobservatory.exoskeleton.storage.RuleRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 规则起草：把「支撑度已达标但还没有规则」的簇交给撰写器，产出 SHADOW 草稿（§4.5）。
 *
 * 本期只解决「规则从哪来」。**刻意不做**的两件事：
 * <ul>
 *   <li>不做调度——起草由人触发（{@code POST /rules/draft}），不给生产默认加负载（§4.7）。</li>
 *   <li>不做上线——写进去的一律是 SHADOW；从 SHADOW 到 LIVE 的唯一入口是晋升闸门（§4.11）。</li>
 * </ul>
 * 因此本服务可以随时重跑：已起草过的簇不再进候选，重复调用是幂等的。
 */
@Service
public class RuleDraftService {

    private static final String NOTE =
            "本次只写 SHADOW 草稿：草稿不参与注入，能否上线由晋升闸门判定（§4.11）。"
                    + "重跑幂等——已有规则的簇不再进候选。";

    private final RuleRepository rules;
    private final RuleDrafter drafter;

    public RuleDraftService(RuleRepository rules, RuleDrafter drafter) {
        this.rules = rules;
        this.drafter = drafter;
    }

    /** 扫描候选簇，逐个起草并写入 SHADOW。 */
    public RulesReport.DraftResult draftMissing() {
        List<ClusterProfile> candidates = rules.draftCandidates();
        List<RulesReport.DraftOutcome> outcomes = new ArrayList<>(candidates.size());
        int created = 0;

        for (ClusterProfile profile : candidates) {
            RuleDrafter.Draft draft = drafter.draft(profile);
            if (!draft.produced()) {
                // 撰写器对该簇没有策略：如实报告**它给出的原因**（替身＝表里没有；LLM＝未配置 / 失败 / 空），
                // 不编一条凑数、也不用一句放之四海的「无对应策略」把真因掩掉（登记口 B20.1）
                outcomes.add(new RulesReport.DraftOutcome(
                        profile.clusterKey(), profile.turns(), null, false, draft.reason()));
                continue;
            }
            String body = draft.body().get();
            int n = rules.insertShadow(profile.clusterKey(), body);
            if (n > 0) {
                created++;
            }
            outcomes.add(new RulesReport.DraftOutcome(
                    profile.clusterKey(), profile.turns(), body, n > 0,
                    n > 0 ? "已写入 SHADOW" : "已存在，跳过"));
        }

        return new RulesReport.DraftResult(candidates.size(), created, outcomes, NOTE);
    }
}
