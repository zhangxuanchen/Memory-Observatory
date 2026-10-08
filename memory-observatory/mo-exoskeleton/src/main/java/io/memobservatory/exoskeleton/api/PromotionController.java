package io.memobservatory.exoskeleton.api;

import io.memobservatory.exoskeleton.model.PromotionDecision;
import io.memobservatory.exoskeleton.promote.PromotionService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 晋升入口（三期 P1，§4.11）：{@code state=LIVE} 的唯一写入路径。
 *
 * <p>返回的是**逐层判决**而不只是一个布尔：停在 ① 还是 ② 还是 ③，本身就是结论。
 * 判为拦截时不改任何状态——「没有放行」与「没有执行」在这条路径上是同一件事，
 * 因为放行就是唯一的副作用。
 *
 * <p>刻意不接受「强制放行」之类参数：闸门一旦可被参数绕过，它就不再是闸门（§4.11 纪律 1）。
 */
@RestController
@RequestMapping("/api/v1/exoskeleton/rules")
public class PromotionController {

    private final PromotionService promotion;

    public PromotionController(PromotionService promotion) {
        this.promotion = promotion;
    }

    /** 判一条规则能不能上线；判为放行则落库。 */
    @PostMapping("/{ruleId}/promote")
    public PromotionDecision promote(@PathVariable String ruleId) {
        return promotion.promote(ruleId);
    }
}
