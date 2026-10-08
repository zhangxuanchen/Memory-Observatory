package io.memobservatory.exoskeleton.tasks;

/**
 * 任务类型的封闭词表（二级筛选维度，session 粒度）。
 *
 * <p><b>为什么是封闭词表 + 自由备注</b>：封闭词表让统计可聚合、可复现；自由备注让人能看懂
 * 「模型为什么这么判」，用于人工抽检标定。备注不参与任何判定——参照 {@code leak_context}
 * 三例全错的教训，分类器不能自证，必须留一条人眼检查的通道。
 *
 * <p><b>为什么是意图/交付物向，而不是动作性质向</b>：动作性质（读代码 / 改代码 / 跑命令）与
 * §4.4 的 {@code shape} 分桶语义高度重叠，加进来等于把同一个维度数两遍。这里问的是
 * 「这段对话在做什么任务」，与 {@code shape} 正交，才是真正的二级筛选。
 *
 * <p><b>为什么它不违反 E2 的推翻结论</b>：E2 推翻的是「用语义簇当分簇键」——那三条判据要求
 * 真实事件流里不存在的 {@code layer='prompt'} 与 {@code writes==0}，故恒不命中。本词表由 LLM
 * 读原始提问判定，不依赖事件词汇；且它<b>只作筛选维度，不作分簇键</b>。桶键与支撑度闸门一律不变。
 *
 * <p>词表随响应返回（{@link #vocabularyNote()}），口径可审计——与 {@code ShapeBucketer.DIMENSIONS} 同规矩。
 */
public enum TaskType {

    FIX_BUG("fix_bug", "缺陷修复", "多数提问是让报错 / 异常 / 与预期不符的行为消失；少数修复提问不改变整体归类，但多数提问都在修 bug 时仍归此类"),
    ADD_FEATURE("add_feature", "新增功能", "多数提问是新增此前不存在的能力 / 接口 / 页面 / 命令；整个会话是「**从零**把一个产品 / 系统做出来」的主线开发（先设计、再实现）也归此。**仅更名、搬目录、换配置、重新打包、写文案，都不算新增功能**"),
    REFACTOR("refactor", "重构整理", "多数提问是不改变外部行为地整理**代码 / 工程**结构、去重复、**调命名、改目录、迁移**；**「给项目 / 模块改名」属此类，不属 add_feature**。对**文档 / 书籍**做结构调整、格式统一、措辞优化**不算此类**（改的是文字，属 doc_write）"),
    EXPLAIN("explain", "答疑解释", "全程**绝大多数**提问是纯知识性问答（了解 / 是什么 / 为什么 / 能不能），且没有任何代码 / 配置 / 文档被改动；只要会话里实际产出过东西，就不算此类"),
    CONFIG_ENV("config_env", "配置环境", "有实际的配置 / 依赖 / 环境变量 / 部署脚本被改动；只是问「能不能」属 explain"),
    TEST_VERIFY("test_verify", "测试验证", "多数提问是写测试 / 跑测试 / 复现并验证某个行为；发布前检查（完整性 / 敏感信息）不算"),
    DOC_WRITE("doc_write", "文档撰写", "多数提问是撰写 / 修改文字（文档 / 注释 / 说明 / README / 简介 / 文案）；对**文档 / 书籍**做结构调整、格式统一、措辞优化、内容增删**也属此类**（改的是文字）；仅出现「文档」字样不算"),
    OTHER("other", "其他", "各类条数接近、装不进单一类型");

    private final String value;
    private final String label;
    private final String criterion;

    TaskType(String value, String label, String criterion) {
        this.value = value;
        this.label = label;
        this.criterion = criterion;
    }

    /** 落库与筛选用的英文标识（封闭词表的取值面）。 */
    public String value() {
        return value;
    }

    /** 中文标签，只用于展示。 */
    public String label() {
        return label;
    }

    /** 判据描述，只用于 prompt。 */
    public String criterion() {
        return criterion;
    }

    /**
     * 归一化 LLM 给出的取值：未知 / 空 / 脏值一律落到 {@link #OTHER}，<b>不抛异常</b>。
     * 与 {@code RuleRepository.state(...)} 同规矩——只读与分析路径不该因一条脏值整体失败。
     *
     * <p>注意：{@code null} 落 {@code OTHER} 只用于「模型给了但认不出」的情形；
     * 「根本没判出来」（LLM 不可达 / 解析失败）在库里是 <b>NULL</b>，两者刻意分开——
     * 「没量到」不能当成「量到了」（与 {@code unknown} vs {@code none} 同一逻辑）。
     */
    public static TaskType fromOrOther(String s) {
        if (s == null) {
            return OTHER;
        }
        String v = s.strip();
        for (TaskType t : values()) {
            if (t.value.equalsIgnoreCase(v) || t.name().equalsIgnoreCase(v) || t.label.equals(v)) {
                return t;
            }
        }
        return OTHER;
    }

    /** 词表渲染成 prompt 里的选项清单（`value`：标签 —— 判据）。 */
    public static String renderVocabulary() {
        StringBuilder sb = new StringBuilder();
        for (TaskType t : values()) {
            sb.append("- ").append(t.value).append("（").append(t.label).append("）：")
                    .append(t.criterion).append('\n');
        }
        return sb.toString();
    }

    /**
     * 四对最易混取值的区分说明（追加在词表之后送模型）。
     *
     * <p>为什么单列：实测全量复核里错判集中在四对——test_verify↔explain、fix_bug↔doc_write、
     * config_env↔add_feature、doc_write↔refactor（末对为 2026-10-07 实测新增：摘要上限提高到能送
     * 全量会话后，书稿的「章节结构调整 / 格式统一」被误读成工程重构）。光有正向判据不够，
     * 必须给出「同一场景两种解读」的取舍反例；
     * 故在四对之外，另附**五条指名反例**（前四条逐条对应 2026-10-06 实测错判：全问不改→explain、
     * 开头写文档后全在修→fix_bug、打包文案非验证→不算 test_verify、主线开发会话夹带修复→add_feature；
     * 第五条对应 2026-10-07 实测的 `add_feature` 偏置：改名 / 搬迁不是新增能力）。
     * 与 {@link #vocabularyNote()} 同规矩：词表口径的文字都留在枚举里，便于审计。
     */
    public static String disambiguation() {
        return """

                四对最易混，务必按下面的反例区分（拿不准时看「哪类提问条数最多」与「改的是什么」）：
                - test_verify vs explain：要「跑一遍、看结果、证明它能用」→ test_verify；
                  只是「问为什么 / 是什么、要一段解释、并不要求改动」→ explain。
                - fix_bug vs doc_write：改的是**代码行为**、让报错消失 → fix_bug；
                  改的是**文字**（README / 注释 / 说明 / 简介 / 文案）→ doc_write。
                  给代码补注释属 doc_write；修 bug 时顺手写注释仍属 fix_bug。
                - doc_write vs refactor：改的是**文字**（把书 / 文档的章节结构调整、格式统一、措辞优化、内容增删）→ doc_write；
                  改的是**代码 / 工程**结构（抽出公共、去重复、改目录、改模块名）→ refactor。
                - config_env vs add_feature：只动配置、依赖、环境变量、部署 / 构建脚本，不改业务能力 → config_env；
                  新增了此前不存在的**能力 / 接口 / 页面 / 命令**，即便过程里也改了配置 → add_feature。

                五条指名反例（按此判，不要按字面词判）：
                - 全部提问都是「了解 / 询问 / 能不能把某个东西整合进系统」→ **explain**，不是 config_env（是问不是改）。
                - 会话开头写了一份评估文档、之后绝大多数提问都在修 bug → **fix_bug**，不是 doc_write（看条数占比）。
                - 「打包安装包 / 写简介 / 改分类预设」既非测试也非验证；发布前的「检查完整性 / 检查敏感信息」**不算** test_verify。
                - 项目从无到有的主线开发会话（先设计架构、再实现功能），即便夹带大量修复 → **add_feature**。
                - 「给项目 / 模块改名」「搬目录 / 迁移仓库」→ **refactor**（改的是名字与位置，不是能力）；
                  重新打包出安装包、上线前检查、写简介文案 → **doc_write 或 other**。以上都**不是** add_feature。
                """;
    }

    /** 口径说明（随报告返回，便于审计，避免被误读成分簇键）。 */
    public static String vocabularyNote() {
        StringBuilder sb = new StringBuilder("封闭 ").append(values().length)
                .append(" 值（session 粒度，由 LLM 读会话内用户提问判定，只作筛选维度、不作分簇键）：");
        for (TaskType t : values()) {
            sb.append(t.value).append('=').append(t.label).append("；");
        }
        sb.append("NULL=未分类（尚未跑到 / LLM 不可达 / 解析失败），与 other（判过、归不进）刻意分开。");
        return sb.toString();
    }
}