package io.memobservatory.exoskeleton.api;

import io.memobservatory.exoskeleton.criteria.CriteriaService;
import io.memobservatory.exoskeleton.model.CriteriaReport;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 判据重算入口（三期 P0）。
 *
 * 只做一件事：算 corrected（§2.4 隐式纠正）与 is_holdout（§2.5 对照组）并回写 memory_turns，
 * 顺手返回本次的分路径统计。手动触发、幂等（holdout 是确定性哈希，纠正率是确定性判定）。
 *
 * 与其他入口一样刻意不做调度，也不注入规则——「规则能不能上线」的唯一入口是晋升闸门（§4.11）。
 */
@RestController
@RequestMapping("/api/v1/exoskeleton/criteria")
public class CriteriaController {

    private final CriteriaService criteria;

    public CriteriaController(CriteriaService criteria) {
        this.criteria = criteria;
    }

    @PostMapping("/recompute")
    public CriteriaReport recompute() {
        return criteria.recompute();
    }
}
