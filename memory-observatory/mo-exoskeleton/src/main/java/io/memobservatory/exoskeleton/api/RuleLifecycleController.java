package io.memobservatory.exoskeleton.api;

import io.memobservatory.exoskeleton.audit.RuleLifecycleService;
import io.memobservatory.exoskeleton.model.RuleAuditReport;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 规则的监控与生命周期入口（§2.6 / §2.7 / §5.2）。
 *
 * <p>四个都是手动入口，与起草 / 晋升同一形态——本模块只在 §4.7 的调度线程里自动跑审计，
 * 其余动作一律由人触发，不在请求路径外偷偷改状态。
 *
 * <p>注意与晋升闸门的分工：这里<b>只降不升</b>。把规则升上去的唯一入口是 {@code POST /rules/{id}/promote}
 * （闸门），且被降级的规则还须先 {@code clear-demotion} 清掉印记才能再次过闸门（§2.6 / A5）。
 */
@RestController
@RequestMapping("/api/v1/exoskeleton/rules")
public class RuleLifecycleController {

    private final RuleLifecycleService lifecycle;

    public RuleLifecycleController(RuleLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    /**
     * 跑一次审计：§2.6 三道限制 + §2.7 churn 撤回。
     *
     * 幂等（读数确定、动作由状态谓词守住不重复生效）；可重复调用。会改状态——这是刻意的，
     * 审计的职责就是执行自动降级，判定与执行不分开会产生「判了但没人落」的悬空结论。
     */
    @PostMapping("/audit")
    public RuleAuditReport audit() {
        return lifecycle.audit();
    }

    /** 内容变更 → 新版本（§2.7）。旧版停用保留，新版回到 SHADOW，须重新过闸门。 */
    @PostMapping("/{ruleId}/revise")
    public Action revise(@PathVariable String ruleId, @RequestBody ReviseRequest req) {
        if (req == null || req.body() == null || req.body().isBlank()) {
            return new Action(ruleId, -1, 0, "规则正文为空：内容变更不产生新版本。");
        }
        int version = lifecycle.revise(ruleId, req.body());
        if (version < 0) {
            return new Action(ruleId, -1, 0, "库里没有这条规则：修订不新建规则（起草走 POST /draft）。");
        }
        return new Action(ruleId, version, 1, "已写入 v" + version + "（SHADOW），旧版置 RETIRED；上线须重新过闸门。");
    }

    /** 人工停用（§5.2 撤销）：最新版本 → RETIRED。 */
    @PostMapping("/{ruleId}/retire")
    public Action retire(@PathVariable String ruleId) {
        int version = lifecycle.retire(ruleId);
        return version < 0
                ? new Action(ruleId, -1, 0, "库里没有这条规则。")
                : new Action(ruleId, version, 1, "已停用 v" + version + "（RETIRED，不可逆）。");
    }

    /** 人工批准回升（§2.6）：清掉降级印记，使规则重新可被闸门审视。不直接改 state。 */
    @PostMapping("/{ruleId}/clear-demotion")
    public Action clearDemotion(@PathVariable String ruleId) {
        int version = lifecycle.clearDemotion(ruleId);
        return version < 0
                ? new Action(ruleId, -1, 0, "库里没有这条规则。")
                : new Action(ruleId, version, 1, "已清除 v" + version + " 的降级印记；回升仍须过闸门（promote）。");
    }

    /** 修订请求体。单独包一层是为了给「内容变更」留一个明确的契约，而不是裸字符串。 */
    public record ReviseRequest(String body) {
    }

    /** 人工操作的结果。affected=0 即未生效（状态不允许 / 规则不存在），如实报出。 */
    public record Action(String ruleId, int version, int affected, String note) {
    }
}
