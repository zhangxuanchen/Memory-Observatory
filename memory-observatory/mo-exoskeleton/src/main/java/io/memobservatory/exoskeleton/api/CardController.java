package io.memobservatory.exoskeleton.api;

import io.memobservatory.exoskeleton.cards.CardActionService;
import io.memobservatory.exoskeleton.cards.DecisionCardService;
import io.memobservatory.exoskeleton.model.CardReport;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 决策卡片接口（§4.10）：出卡（生成）、看卡（清单）、点卡（作答）。
 *
 * <p>三个端点对应卡片生命周期的三段：生成 → 展示 → 作答。刻意<b>不做调度</b>——
 * 出卡节奏由人决定，自动出卡会把「卡片数」这个反向指标（§4.10.6）变成噪声。
 *
 * <p>卡片 id 形如 {@code T1:cluster:readonly|steps:1-3} 或 {@code T5:merged:1a2b3c}。
 * 作答时必须原样传回 id 与选项 key——选项 key 由服务端校验（固定三档，非法即拒）。
 */
@RestController
@RequestMapping("/api/v1/exoskeleton/cards")
public class CardController {

    private final DecisionCardService cards;
    private final CardActionService actions;

    public CardController(DecisionCardService cards, CardActionService actions) {
        this.cards = cards;
        this.actions = actions;
    }

    /**
     * 跑一轮生成（§4.10.5）：判据表 → 候选 → 选模板 → 填槽 → 合并同类 → 排序截断。
     *
     * <p>可重复调用（幂等）：未答的卡被刷新（保留 created_at），答过「下个周期再报」的被重新提出，
     * 其余已答的跳过。响应里的 {@code templates} 逐条列出六个模板的触发情况——
     * 未触发的原因也写出来，免得把「没触发」读成「没问题」。
     */
    @PostMapping("/generate")
    public CardReport.GenerateResult generate() {
        return cards.generate();
    }

    /**
     * 卡片清单。默认隐去已到期未答的卡（到期只是不再展示，不改变卡片状态）。
     *
     * @param includeExpired 传 true 时连到期未答的一起列出
     */
    @GetMapping
    public CardReport list(@RequestParam(required = false) Boolean includeExpired) {
        return includeExpired == null ? cards.list() : cards.list(includeExpired);
    }

    /**
     * 作答：三档里点一个（{@code answer} 为选项 key）。
     *
     * <p>后果由服务端执行，且与卡面上的 {@code effect} 文案一致：T1「采纳」走晋升闸门（不等于生效）、
     * 「驳回」停用并抑制该簇；T3「停用」置 RETIRED、「降命中面」升新版本退回影子；
     * 改配置的档位（T2 / T5 的放宽）本版只记录。
     */
    @PostMapping("/{cardId}/answer")
    public CardReport.AnswerResult answer(@PathVariable String cardId,
                                          @RequestBody AnswerRequest request) {
        return actions.answer(cardId, request == null ? null : request.answer());
    }

    /** 作答请求体：只收一个选项 key（逃逸口「看原始样本」不是选项，不走本接口）。 */
    public record AnswerRequest(String answer) {
    }
}