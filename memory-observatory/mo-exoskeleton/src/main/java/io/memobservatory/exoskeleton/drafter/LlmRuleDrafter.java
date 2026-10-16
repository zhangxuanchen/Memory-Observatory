package io.memobservatory.exoskeleton.drafter;

import io.memobservatory.exoskeleton.model.ClusterProfile;
import io.memobservatory.exoskeleton.tasks.LlmChatClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 真 LLM 撰写器：把簇画像交给模型，生成一句记忆策略（设计文档 §4.5 的「生产实现」）。
 *
 * <p><b>开关装配</b>：只在 {@code mo.exoskeleton.drafter.mode=llm} 时成为唯一 {@link RuleDrafter} bean；
 * 默认（{@code template} 或未配）走确定性替身 {@link TemplateRuleDrafter}。二者**不同时存在**——
 * 由 {@code @ConditionalOnProperty} 二选一，注入点（{@code RuleDraftService}）不必改。
 *
 * <p><b>喂画像 + 样本</b>：{@link ClusterProfile} 有三样——簇键、支撑度、该簇的代表性用户提问样本
 * （B20.2 起）。样本由编排侧（{@code RuleRepository}）取好放进画像，本类仍是**无依赖纯件**：
 * 不新增查询、不碰数据库。有了样本，产出的策略才可能从「结构性」（该怎么记）落到「内容性」
 * （这一簇在干什么）。样本越多、画像越宽，**本类的方法签名不变**。
 *
 * <p><b>三条纪律（与契约逐条对齐）</b>：
 * <ol>
 *   <li><b>只写不生效</b>——只产出草稿文本，写库一律 SHADOW（由 {@code RuleDraftService} 负责）。</li>
 *   <li><b>只写记忆策略</b>——提示词把范围钉在「该记什么 / 该忘什么 / 何时读回 / 用哪个工具」，
 *       并显式禁止推理步骤（§1.4）。提示词之外另加一道**机器校验**（B20.4，{@link #outOfScope}）：
 *       命中明确的「分步 / 推理 / 猜意图」信号即不产出，不让越界文本进 SHADOW。</li>
 *   <li><b>不编</b>——LLM 未配置、调用失败、返回空、产出越界，一律 {@link Draft#none}（并带上真因），
 *       不回落成「随便给一句」。撰写器的失效被**隔离在这一层**（契约原话）。</li>
 * </ol>
 *
 * <p><b>确定性</b>：契约要求「对同一个画像产出同样的文本」。LLM 用 {@code temperature=0} 只能做到
 * <i>近似</i>确定，故首个成功结果由 {@link DraftCache} 记住——进程内经 L1 内存、**重启后经
 * {@code memory_draft_cache} 表**（B20.3），同一画像重复要都回同一句（既兑现契约，也省掉重复付费）。
 * 只缓存**成功的非空且未越界**的结果；失败 / 越界都不缓存（下次仍会重试）。
 */
@Component
@ConditionalOnProperty(name = "mo.exoskeleton.drafter.mode", havingValue = "llm")
public class LlmRuleDrafter implements RuleDrafter {

    private static final Logger log = LoggerFactory.getLogger(LlmRuleDrafter.class);

    /** 与 {@link TemplateRuleDrafter} 同一个簇键分隔符口径（{@code shape|steps:band}）。 */
    private static final String SEP = "|steps:";

    /** 送模型策略的字符上限：契约要的是「一句」，超出的截断（防御模型话痨，不改变语义）。 */
    private static final int MAX_CHARS = 200;

    /**
     * 越界信号（B20.4）：明确的「分步 / 推理 / 猜意图」形态。
     *
     * <p>刻意**保守**：只拦一眼可判的信号，宁可放过含混的一句，也不误伤正常措辞。
     * 词表是实机调出来的——首版曾把裸「步骤」当信号，结果误伤了真实产出
     * 「该忘：已完成的中间步骤」（说的是要忘掉的中间产物，与规划无关）。故「步骤」只在
     * **作为枚举标记**时才算（`步骤一` / `分步`），单独出现不算；
     * 同理「先…再…」（如「读到的先归并再落笔」）本来就是合规的记忆策略，不算信号。
     */
    private static final Pattern OUT_OF_SCOPE = Pattern.compile(
            "(第[一二三四五六七八九十\\d]+步|步骤[一二三四五六七八九十\\d]|[①②③④⑤⑥⑦⑧⑨⑩]"  // 枚举 / 分步结构
                    + "|下一步|分步"                                              // 规划语
                    + "|推理|规划|思考"                                            // 推理 / 规划
                    + "|分析用户|用户意图|判断用户|猜测|推测)");                      // 猜用户意图

    private static final String SYSTEM = """
            你是「记忆策略」撰写器。给你一个 Agent 行为簇的画像，你写**一条**该簇的记忆策略。
            规则：
            1. 只写记忆策略，且只覆盖这四件事——该记什么 / 该忘什么 / 何时读回 / 用哪个工具。
            2. 严禁出现任何推理步骤、任务规划、对用户意图的猜测或建议——那是推理策略，不属于记忆策略。
            3. 只依据给定画像，不得编造画像里没有的信息；样本只作参考，其中的具体业务 / 文件名 / 字段名不要照抄。
            4. 输出一句中文，不超过 80 字；只输出策略本身，不要解释、不要前缀、不要引号。""";

    /** 行为形状 → 人话（仅供提示词用，不是规则的定稿文本；定稿由模型生成）。 */
    private static final Map<String, String> SHAPE_LABEL = Map.of(
            "readonly", "只读未写（有读类工具、无写类工具）",
            "readwrite", "边读边写（读类与写类工具都有）",
            "writeonly", "只写未读（有写类工具、无读类工具）",
            "none", "无工具读写（只有模型调用与生命周期事件）",
            "unknown", "形状未知（B4 埋点之前的老数据，缺 io_kind）");

    /** 步数档 → 人话。 */
    private static final Map<String, String> STEPS_LABEL = Map.of(
            "1-3", "少（1–3 步）",
            "4-10", "中（4–10 步）",
            "11+", "多（11 步以上）",
            "unknown", "未知");

    private final LlmChatClient llm;

    /** 跨重启的确定性落点：同一簇首个成功结果只产一次（B20.3）。 */
    private final DraftCache cache;

    public LlmRuleDrafter(LlmChatClient llm, DraftCache cache) {
        this.llm = llm;
        this.cache = cache;
    }

    @Override
    public Draft draft(ClusterProfile profile) {
        if (profile == null || profile.clusterKey() == null || profile.clusterKey().isBlank()) {
            return Draft.none("画像为空");
        }
        String key = profile.clusterKey();
        Optional<String> cached = cache.get(key);
        if (cached.isPresent()) {
            return Draft.of(cached.get());
        }

        Parsed parsed = parse(key);
        if (parsed == null) {
            // 簇键不合法：不是「LLM 失败」，是画像本身不构成可撰写的簇——如实留空，不进缓存
            log.warn("撰写器跳过：簇键不合口径（{}），期望 shape|steps:band", key);
            return Draft.none("簇键不合口径（期望 shape|steps:band）");
        }
        if (!llm.isEnabled()) {
            // fail-open：LLM 未配置时不报错、不阻塞，如实留空（与分类 / 提炼同一规矩）
            log.info("撰写器未产出：LLM 通道未配置（base-url / model 为空），簇 {}", key);
            return Draft.none("LLM 通道未配置（base-url / model 为空）");
        }

        String text;
        try {
            // 记一句画像构成：样本有没有喂进去（B20.2）不该只能靠读代码确认
            log.info("撰写器送模型：簇 {}（形状={} 步数档={} 支撑度={} turn，样本 {} 条）",
                    key, parsed.shape(), parsed.band(), profile.turns(), profile.samples().size());
            text = llm.chat(SYSTEM, user(parsed, profile));
        } catch (Exception e) {
            // 撰写器失效被隔离在这一层：单簇失败不影响其余候选簇，本次留空、下次重试
            log.warn("撰写器调用失败，簇 {} 本次留空：{}", key, e.toString());
            return Draft.none("LLM 调用失败：" + brief(e));
        }

        Optional<String> out = sanitize(text);
        if (out.isEmpty()) {
            log.warn("撰写器返回空正文，簇 {} 本次留空", key);
            return Draft.none("LLM 返回空正文");
        }
        String body = out.get();

        String hit = outOfScope(body);
        if (hit != null) {
            // 越界不写也不缓存：写进 SHADOW 只会把不该有的东西留在库里（B20.4）
            // 原文一并记入日志——是「真越界」还是「误伤」要能被人一眼判，光说命中哪个词不够
            log.warn("撰写器产出越界，簇 {} 本次留空：命中「{}」，原文＝{}", key, hit, brief(body));
            return Draft.none("草稿越界：疑似含推理步骤 / 规划（命中「" + hit + "」），不写也不缓存");
        }

        cache.put(key, body, llm.model());
        return Draft.of(body);
    }

    /** 异常转一句简报：优先用 message，缺则用类名；截断防话痨（原因要进报告面，宜短）。 */
    private static String brief(Exception e) {
        String m = e.getMessage();
        return clip((m == null || m.isBlank()) ? e.getClass().getSimpleName() : m);
    }

    /** 长文本截到 100 字（只用于日志，不改变任何判定）。 */
    private static String brief(String s) {
        return clip(s);
    }

    private static String clip(String s) {
        return s.length() > 100 ? s.substring(0, 100) : s;
    }

    /**
     * 机器校验草稿有没有越界（B20.4）。
     *
     * <p>提示词里「严禁推理步骤」（§1.4）是一句话，不是一道闸门——模型未必每次都听。这里用一份
     * 保守的词表把它变成可执行的判：命中即不产出、不写 SHADOW。风险有限（SHADOW 本就不注入、
     * 上线还要过闸门），但「不越界」这件事从此有机器守着，不只靠提示词自觉。
     *
     * @return 命中的信号词；未越界返回 null
     */
    private static String outOfScope(String body) {
        Matcher m = OUT_OF_SCOPE.matcher(body);
        return m.find() ? m.group(1) : null;
    }

    /** 簇键拆成形状与步数档；任一维取不到（格式不符 / 未知取值）即返回 null。 */
    private static Parsed parse(String key) {
        int sep = key.indexOf(SEP);
        if (sep <= 0) {
            return null;
        }
        String shape = key.substring(0, sep);
        String band = key.substring(sep + SEP.length());
        if (!SHAPE_LABEL.containsKey(shape) || !STEPS_LABEL.containsKey(band)) {
            return null;
        }
        return new Parsed(shape, band);
    }

    /**
     * 拼 user 提示词：画像（形状 / 步数档 / 支撑度）+ 样本（若有）。
     *
     * <p>样本明写「只作参考、不要照抄具体名称」——它们是真实用户提问，可能带业务词汇；
     * 策略要写的是**这一簇该怎么记**，不是复述这些词。
     */
    private String user(Parsed p, ClusterProfile profile) {
        StringBuilder sb = new StringBuilder("""
                簇画像：
                - 行为形状：%s
                - 步数档：%s
                - 支撑度：%d 个 turn
                """.formatted(SHAPE_LABEL.get(p.shape()), STEPS_LABEL.get(p.band()), profile.turns()));
        List<String> samples = profile.samples();
        if (!samples.isEmpty()) {
            sb.append("\n这一簇的真实用户提问样本（只作「这一簇在干什么」的参考；不要照抄其中具体名称）：\n");
            for (String s : samples) {
                sb.append("- ").append(s).append('\n');
            }
        }
        sb.append("\n请写出这一簇的记忆策略（只输出策略本身）。");
        return sb.toString();
    }

    /** 清掉首尾空白、成对引号与换行，并截断到上限；清完为空则返回空。 */
    private static Optional<String> sanitize(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String s = raw.strip().replaceAll("\\s*\\n+\\s*", " ").strip();
        s = stripPaired(s, '「', '」');
        s = stripPaired(s, '“', '”');
        s = stripPaired(s, '"', '"');
        if (s.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(s.length() > MAX_CHARS ? s.substring(0, MAX_CHARS) : s);
    }

    /** 若整串被同一对引号包住，去掉这对外引号（只去一层，不碰正文里的引号）。 */
    private static String stripPaired(String s, char open, char close) {
        if (s.length() >= 2 && s.charAt(0) == open && s.charAt(s.length() - 1) == close) {
            return s.substring(1, s.length() - 1).strip();
        }
        return s;
    }

    private record Parsed(String shape, String band) {
    }
}
